# Chat Memory, RAG, And Tool Calling

> [English](chat-memory-rag-tool-calling.md) | [中文](chat-memory-rag-tool-calling-zh-CN.md)
>
> This document records how production Chat uses Spring AI, which safeguards are
> project-owned, the current gaps, and the recommended direction. See
> [Configuration](configuration.md), [REST API](rest-api.md), and
> [SSE Protocol](SSE-PROTOCOL.md) for normative runtime contracts.

## 1. Questions And Conclusion

This research answers:

1. Does Chat fully use Spring AI conversation history, memory, and context facilities?
2. What does current “compression” compress, and is it durable conversation summarization?
3. Is document retrieval implemented as Function Calling or dedicated Spring AI RAG?
4. How are infinite tool loops prevented, and are the current budgets complete?
5. How should non-embedding tools, such as relational-data lookup, be added?

The project already makes substantive use of Spring AI `ChatClient`, Advisors,
Chat Memory, Modular RAG, Tool Calling, and `ToolContext`. It does not maintain a
parallel hand-written Chat loop. However, token-aware prompt budgeting, durable
summary memory, logical-request-level model/tool budgets, and a public
server-owned tool-provider SPI are still missing. The accurate status is:
Spring AI orchestration is reused well, while production context and tool
governance still need strengthening.

## 2. Version And Production Entry Point

The research baseline is:

- Spring Boot `3.5.3`
- Spring AI `1.1.4`
- Java `21`
- production chain:
  `RagChatController -> ChatCommandMapper -> ChatExecutionService ->
  ModeAwareChatClientFactory`

Spring AI `1.1.8` is the later patch to use when remaining on the Boot 3.5 /
Spring AI 1.1 maintenance line. Its release baseline moved to Boot `3.5.15`,
while Boot `3.5.16` is available on that maintenance line as of 2026-08-21.
The dependency slice should therefore validate a Boot patch upgrade together
with Spring AI instead of layering Spring AI `1.1.8` onto Boot `3.5.3` and
assuming that combination is supported. This still does not deliver context
budgets or tool governance automatically. The 1.1.8 `ToolCallAdvisor` recurses
until the model stops requesting tools and has no stable built-in total-call
budget.

An isolated dependency probe also confirmed an API migration that must be
handled explicitly in 1.1.8:
`MessageChatMemoryAdvisor.Builder.conversationId(...)` no longer exists.
Production `ModeAwareChatClientFactory` must stop binding the conversation ID
on the advisor builder and instead pass `ChatMemory.CONVERSATION_ID` through
the advisor context of every non-streaming and streaming request. After that
migration, core compile and test-compile passed, as did 30 focused tests across
`ModeAwareChatClientFactoryTest`, `ChatExecutionServiceTest`, and
`ChatMemoryMultiTurnTest`. This is a framework API adaptation only; it must not
change principal/session isolation or request-local Memory semantics.

Key code:

- [`ChatMode`](../spring-ai-rag-api/src/main/java/com/springairag/api/enums/ChatMode.java)
- [`ChatExecutionService`](../spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatExecutionService.java)
- [`ModeAwareChatClientFactory`](../spring-ai-rag-core/src/main/java/com/springairag/core/chat/ModeAwareChatClientFactory.java)
- [`ChatSessionCoordinator`](../spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatSessionCoordinator.java)

## 3. Three Chat Modes

Production APIs use explicit modes instead of language-specific retrieval intent
heuristics:

| Mode | Retrieval behavior | Spring AI mechanism | Intended semantics |
|---|---|---|---|
| `PLAIN` | None | `ChatClient` + Memory | Ordinary model conversation |
| `KNOWLEDGE` | Always retrieves | `RetrievalAugmentationAdvisor` | Strong grounding and stable sources |
| `AGENT` | Model invokes zero or more tools | `ToolCallAdvisor` | Multi-tool exploration and dynamic decisions |

Keeping all three is deliberate:

- Deterministic document Q&A should not become agentic merely for uniformity.
- Agent retrieval should not silently add cost to an explicitly plain task.
- Retrieval policy is an execution choice, not an unconditional Chat pre-step.

## 4. Document Retrieval Has Two Paths

### 4.1 KNOWLEDGE: Spring AI Modular RAG

`KNOWLEDGE` does not use Function Calling. Spring AI
`RetrievalAugmentationAdvisor` composes:

```text
history-aware query transformation
  -> optional multi-query expansion
  -> ProjectDocumentRetriever
  -> project hybrid vector/full-text retrieval
  -> project rerank
  -> CitationQueryAugmenter
  -> ChatModel
```

`ProjectDocumentRetriever` adapts the project stack to Spring AI's
`DocumentRetriever` contract rather than replacing it with a simple vector-store
retriever. The production path retains:

- Collection, Document, and API-key ACLs;
- vector and Chinese/English full-text retrieval;
- RRF/weighted fusion;
- reranking;
- metadata/payload filters;
- citations and diagnostics.

This path is appropriate when retrieval must happen, because latency, source,
and failure semantics are more predictable.

### 4.2 AGENT: Spring AI Tool Calling

`AGENT` uses Spring AI `ToolCallAdvisor` and server-owned tools:

- `searchKnowledge`: authorized project hybrid document retrieval;
- `searchJsonRecords`: optional structured JSON-record retrieval, disabled by default.

Model-visible arguments contain only query terms, bounded result counts, and
permitted narrowing filters. Collection, Document, ACL, principal, and
request-level filters are injected through server-owned `ToolContext` and cannot
be expanded by model arguments.

The same retrieval engine therefore supports two orchestration semantics:

```text
KNOWLEDGE -> server-enforced Modular RAG
AGENT     -> model-selected searchKnowledge tool calls
```

## 5. Conversation History And Chat Memory

The project has two persistence layers with different responsibilities:

| Store | Purpose | Current ownership |
|---|---|---|
| `rag_chat_history` | Business history, owner, source snapshots, mode, model, usage, audit | Application transaction |
| `spring_ai_chat_memory` | Recent model-context message window | Spring AI JDBC Chat Memory |

For each production request:

1. acquire a principal/session database lease for single-flight execution;
2. load a baseline from committed business history;
3. create request-local `MessageWindowChatMemory`;
4. use `MessageChatMemoryAdvisor` to add history to the prompt;
5. keep failed candidates, retries, and tools out of durable memory;
6. commit the successful business turn and JDBC Memory atomically.

This is safer than allowing each fallback attempt to mutate shared Chat Memory.
There is still a retry-isolation gap. The non-streaming `RetryTemplate` invokes
the same request-local attempt repeatedly. Spring AI
`MessageChatMemoryAdvisor.before()` adds the current user message to local
Memory before delegating to the model. If that invocation then fails, the next
retry inherits the local mutation and adds the user message again. A later
successful commit can therefore carry duplicate context left by the failed
invocation into JDBC Memory. This is not a failed turn being committed
directly, but it still violates attempt isolation. Each application-level retry
should create a new request-local ChatClient and Memory from the same committed
baseline, discard failed attempt state, and keep all model, tool, and retrieval
cost charged to the shared logical-request budget.

### 5.1 Actual Window Semantics

`rag.memory.max-messages=20` is a message-count limit, not a token limit.
Spring AI `MessageWindowChatMemory` evicts older non-system messages when the
count is exceeded. It does not:

- budget against the selected model's `contextWindow`;
- reserve output, RAG evidence, or tool-schema tokens;
- summarize evicted history;
- distinguish one very long message from one short message.

Model configuration already includes `context-window` and `max-tokens`, but
production Chat does not yet use them for candidate-specific prompt packing,
and external `models.json` entries do not reject explicit zero or negative
values. The implementation should distinguish missing metadata from invalid
metadata: missing values use a conservative fallback with diagnostics, while
an explicitly non-positive value makes that candidate unavailable. Invalid
limits must never be interpreted as unlimited context or zero-cost output.

### 5.2 TTL And The Active-Chat Concurrency Boundary

`rag_chat_history`, Spring AI JDBC Memory, and the session lease are three
related pieces of state for one conversation. If TTL deletes old history
without coordinating the other two, the model Memory can retain expired data.
More seriously, a Chat request can read an old baseline, TTL can delete it,
and that request can later write the old baseline back.

The future TTL implementation must therefore discover candidate sessions in
bounded batches and acquire a separate maintenance token through
`rag_chat_session_lease`. A valid Chat lease is skipped immediately; cleanup
does not wait for or preempt it. After acquiring the maintenance lease, a short
transaction must consume it with token fencing and delete the batch of owned
expired history, the corresponding summary, and the JDBC Memory derived from
the same principal/session rule. On commit the maintenance lease disappears;
on rollback all four pieces of state roll back together. A Chat commit must
also pass its own token fence, so an active request cannot re-submit a baseline
read before TTL. This protocol uses conditional writes, leases, and bounded
batches; it does not introduce `FOR UPDATE`, `SKIP LOCKED`, or advisory locks.

## 6. Two Different Meanings Of Compression

Spring AI `CompressionQueryTransformer` can combine prior conversation and a
follow-up into a standalone retrieval query. For example:

```text
History: the user is discussing BGE-M3
Follow-up: what is its dimension?
Retrieval query: BGE-M3 embedding dimension
```

This is **retrieval-query compression**, not **conversation-context
compaction**. It does not:

- summarize old turns into durable memory;
- reduce the final Chat prompt's history tokens;
- establish a summary-plus-recent-turns memory hierarchy;
- run outside the `KNOWLEDGE` pre-retrieval path.

The base `application.yml` uses `query-transformer: none`, while the
`postgresql` and `prod` profiles enable `spring-ai`. Runtime conclusions must
therefore account for the active profile.

## 7. Current Tool Loop And Budgets

Spring AI `ToolCallAdvisor` implements the standard call and stream loop:

```text
model returns tool calls
  -> ToolCallingManager executes tools
  -> tool results enter conversation history
  -> model is called again
  -> repeat until no tool calls remain
```

The project adds these attempt-level limits through
`BudgetedToolCallAdvisor` and `RetrievalTraceCollector`:

- maximum tool rounds: `3`
- maximum uncached retrievals: `3`
- maximum results per retrieval: `10`
- maximum unique sources: `20`
- maximum serialized output per tool call: `24,000` characters
- logical request deadline: `120s` non-streaming, `180s` streaming

These controls stop the most direct infinite loop, but they are not a complete
budget:

1. One model response can contain multiple parallel tool calls.
2. The character cap is per call, not cumulative across the logical request.
3. Retry and fallback attempts can receive fresh attempt budgets.
4. There are no per-tool call, SQL time/row, or cost budgets.
5. Query transform, query expansion, summary, and answer model calls are not
   counted in one place.
6. No focused test directly proves that round four is rejected before execution.
7. Tool callbacks currently record `toolCallId=null`.

All built-in model declarations currently use `tool-calling: false`. The AGENT
path exists in code, but only an external `models.json` entry that explicitly
enables a provider-verified model should make it eligible.

## 8. External Function Calls And SQL Retrieval

`ChatExecutionService` currently hard-codes two server-owned tools and there is
no public provider SPI. The OpenAI compatibility endpoint also rejects
client-supplied `tools`, `tool_choice`, `functions`, `function_call`, and
tool/function messages.

That is an intentional trust boundary. Client-defined tool passthrough requires
the server to manage external callback lifecycle, identity propagation,
timeouts, and result trust. It is a different product from server-owned tools
and should not be enabled incidentally.

### 8.1 Recommended SQL Shape

Do not expose:

```text
executeSql(sql)
```

Expose typed, read-only, parameterized domain tools instead:

```text
lookupInventory(sku, warehouse, maxResults)
searchOrders(status, createdAfter, maxResults)
queryAssetStatus(assetIds)
```

Rules:

- expose business arguments, never SQL, table names, or credentials;
- use fixed SQL or approved query templates;
- bind parameters instead of concatenating model input;
- use a dedicated read-only database role;
- allow-list schemas/views and fields;
- inject principal ownership predicates on the server; if a consumer has
  tenant semantics, resolve them through a server-owned principal/ACL mapping,
  never from request metadata or model arguments;
- enforce statement timeout, row caps, and serialized-byte caps;
- allow one read-only statement and no DDL/DML;
- audit tool, argument summary, duration, row count, and budget outcome;
- do not misrepresent relational results as document citations.

The generic framework should provide the extension SPI and safe context. It
should not ship a core tool that can query arbitrary business databases.

## 9. Recommended Target Architecture

### 9.1 Token-Aware Prompt Budget

Create a candidate-specific budget from `contextWindow` and `maxTokens`, then
measure or reserve:

1. system/domain instructions;
2. current user input;
3. tool schemas;
4. output tokens;
5. safety margin;
6. RAG evidence or tool output;
7. conversation summary;
8. recent raw turns.

When space is insufficient, degrade deterministically:

```text
trim lower-priority RAG/tool results
  -> reduce recent history while preserving minimum turns
  -> retain the summary
  -> return a typed error if mandatory prompt content still does not fit
```

Provider context-overflow errors should not be the normal control mechanism.

### 9.2 Durable Summary Plus Recent Raw Turns

Retain:

```text
rag_chat_history       -> complete business history and audit
spring_ai_chat_memory  -> recent raw message window
chat summary table     -> durable old-history summary and compaction cursor
```

The summary is conversation memory, not external evidence. `KNOWLEDGE` answers
must remain grounded in retrieved sources. Summary facts do not automatically
become citations.

If compaction fails, deterministic token-based truncation should preserve the
main path and record degraded status without deleting raw history. Model calls
must not occur while a database transaction is open.

### 9.3 Logical-Request-Level Budget

One budget must be shared across candidates, retries, and the tool loop:

- deadline;
- candidate attempts;
- total model calls;
- tool rounds;
- total tool calls;
- calls per tool;
- cumulative tool-result characters/tokens;
- unique sources;
- tool-specific duration, rows, and cost.

When a model emits multiple tool calls, reserve the complete batch atomically
before executing any callback. Reject the whole batch if it cannot fit.

A shared budget must not imply shared mutable attempt state. Candidate fallback
and same-model retry should each receive a fresh request-local Memory, advisor
chain, and tool conversation. Failed local messages must not enter the next
attempt, while already-consumed model, tool, and retrieval budget remains
spent.

### 9.4 Server-Owned Tool Provider SPI

Spring Bean providers should declare tools, while a registry handles:

- mode/domain selection;
- duplicate-name fail-fast validation;
- startup validation of tool definitions, metadata, and input schemas;
- deterministic ordering;
- common budget, timeout, output, and diagnostics wrappers;
- credential-free principal/session context;
- server-owned Collection/ACL scope;
- a stable extension surface for external modules.

The built-in search tools should migrate to the same registry so built-in and
external tools do not have different safety mechanisms.

## 10. Recommended Implementation Order

1. Upgrade and verify Spring Boot `3.5.16` and Spring AI `1.1.8` together;
   characterize existing behavior.
2. Implement logical-request-level model/tool budgets and focused call/stream tests.
3. Add a Tool Provider SPI and migrate both built-in tools.
4. Add a parameterized read-only SQL extension example, never arbitrary SQL.
5. Use model metadata for token-aware prompt packing.
6. Add durable summary schema, compaction service, CAS, TTL, and clear behavior.
7. Complete metadata, metrics, verification scripts, real Tool Calling, and
   real compaction smoke tests.

See the [active next-high-value implementation plan](drafts/NEXT_HIGH_VALUE_FEATURES_PLAN.md).

## 11. References

- [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI Modular RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
- [Spring AI 1.1.8 Release](https://github.com/spring-projects/spring-ai/releases/tag/v1.1.8)
- [Spring Boot 3.5.16 Release](https://github.com/spring-projects/spring-boot/releases/tag/v3.5.16)
- [Architecture](architecture.md)
- [Configuration](configuration.md)
- [Testing Guide](testing-guide.md)
