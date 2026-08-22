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

The project makes substantive use of Spring AI `ChatClient`, Advisors, Chat
Memory, Modular RAG, Tool Calling, and `ToolContext`. It does not maintain a
parallel hand-written Chat loop. The implementation now adds token-aware prompt
planning, logical-request-level model/tool budgets, durable summary storage
with CAS, coordinated TTL cleanup, and a public server-owned tool-provider SPI.
The accurate status is that Spring AI supplies the orchestration primitives while
the project owns authorization, context packing, persistence, and bounded
execution policy.

## 2. Version And Production Entry Point

The current implementation baseline is:

- Spring Boot `3.5.16`
- Spring AI `1.1.8`
- Java `21`
- production chain:
  `RagChatController -> ChatCommandMapper -> ChatExecutionService ->
  ModeAwareChatClientFactory`

The `1.1.8` API migration is handled explicitly: production no longer binds a
conversation ID on `MessageChatMemoryAdvisor.Builder`; it passes the
server-derived `ChatMemory.CONVERSATION_ID` through each request's advisor
context. This preserves principal/session isolation for both non-streaming and
streaming requests. Spring AI's `ToolCallAdvisor` still loops until the model
stops requesting tools, so the project wraps model and tool execution with its
own logical-request budget.

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
Application-level retries and fallback candidates create fresh request-local
ChatClient, Memory, advisor, retrieval, and tool state from the same committed
baseline. Failed attempt state is discarded; model, tool, and retrieval costs
remain charged to the shared logical-request budget. This prevents a failed
advisor invocation from adding a duplicate user message to the successful
attempt's durable JDBC Memory.

### 5.1 Actual Window Semantics

Spring AI `MessageWindowChatMemory` remains the request-local recent-message
store, but production Chat now plans its input before the model call. The
project-owned planner uses model context metadata when valid, reserves output
and safety tokens, and bounds history, summary, RAG evidence, and tool schemas.
When adaptive packing is disabled, the legacy baseline message behavior remains
available, while the model-call prompt hard gate and execution budget remain
active. The underlying Spring AI window still does not:

- budget against the selected model's `contextWindow`;
- reserve output, RAG evidence, or tool-schema tokens;
- summarize evicted history;
- distinguish one very long message from one short message.

Model configuration includes `context-window` and `max-tokens`. Missing limits
use a conservative fallback with diagnostics; explicitly non-positive limits
make that candidate unavailable. Invalid limits are never interpreted as
unlimited context or zero-cost output. Prompt planning degrades in a fixed
order: trim lower-priority evidence/tool output, reduce older history while
preserving minimum recent turns, retain the summary, then return a typed context
budget error if mandatory input still cannot fit.

### 5.2 TTL And The Active-Chat Concurrency Boundary

`rag_chat_history`, Spring AI JDBC Memory, and the session lease are three
related pieces of state for one conversation. If TTL deletes old history
without coordinating the other two, the model Memory can retain expired data.
More seriously, a Chat request can read an old baseline, TTL can delete it,
and that request can later write the old baseline back.

The implemented TTL cleanup discovers candidate sessions in
bounded batches and acquires a separate maintenance token through
`rag_chat_session_lease`. A valid Chat lease is skipped immediately; cleanup
does not wait for or preempt it. After acquiring the maintenance lease, a short
transaction consumes it with token fencing and deletes the owned expired
history, summary, and JDBC Memory derived from the same principal/session rule.
On commit the maintenance lease disappears; on rollback the related state
remains consistent. A Chat commit must
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
compaction**. The project now also has an optional, project-owned durable
summary compaction path. The two mechanisms remain separate:

- `CompressionQueryTransformer` does not summarize old turns into durable
  memory, reduce the final Chat prompt's history tokens, or establish the
  summary hierarchy.
- `ConversationSummaryService` compacts only committed COMPLETE turns before
  the protected recent-turn window, stores a forward-only history cursor, and
  updates the V46 summary row with optimistic version CAS. Keyed requests
  additionally persist their V47 durable turn operation and replay snapshot.
- Summary generation is disabled by default, bounded by the shared model-call
  budget and deadline, and degrades to the main Chat path on timeout, provider
  failure, output overflow, or CAS conflict. A summary is memory, not citation
  evidence.

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

The project adds these logical-request and tool-policy limits through
`BudgetedChatModel`, `BudgetedToolCallingManager`, `ChatExecutionBudget`,
`RagChatToolRegistry`, and `RetrievalTraceCollector`:

- maximum tool rounds: `3` by default;
- maximum uncached retrievals: `3` by default;
- maximum results per retrieval: `10` by default;
- maximum unique sources: `20` by default;
- maximum serialized output per tool call: `24,000` characters by default;
- maximum total tool calls: `6` by default;
- maximum calls per tool name: `3` by default;
- maximum total tool-result characters: `48,000` by default;
- logical request deadline: `120s` non-streaming, `180s` streaming.

The shared budget spans candidate attempts, retries/fallbacks, model calls,
tool rounds, tool calls, per-name calls, cumulative tool-result characters,
retrieval trace, and summary compaction. `BudgetedToolCallingManager` reserves
an entire tool-call batch before execution and settles the actual
`ToolExecutionResult`; policy wrappers additionally enforce per-tool timeout,
executor saturation, read-only policy, and result limits. Exceeding a budget
returns a bounded tool error or a typed Chat error before unbounded work.

Built-in model declarations may still disable Tool Calling. An external model
must explicitly advertise `capabilities.toolCalling=true` and pass provider
verification before the WebUI or API uses `AGENT`.

## 8. External Function Calls And SQL Retrieval

The core registry owns built-in `searchKnowledge` and optional
`searchJsonRecords` tools and discovers additional server-owned
`RagChatToolProvider` beans. Providers declare supported modes/domains, callback
definitions, and optional restrictive policies; startup validation rejects
duplicate names, invalid schemas/metadata, `returnDirect=true`, and unknown
policy keys. The registry injects principal/session/deadline and budget through
Spring AI `ToolContext`.

The OpenAI compatibility endpoint still rejects
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

The generic framework provides the extension SPI and safe context, but does not
ship a core tool that can query arbitrary business databases. The SQL demo
implements the recommended shape with fixed PostgreSQL `SELECT`, named
parameters, server-owned principal filtering, a 20-row cap, a policy-bounded
statement timeout, and a serialized result cap.

## 9. Current Architecture And Remaining Work

### 9.1 Token-Aware Prompt Budget

The implemented candidate-specific budget uses `contextWindow` and `maxTokens`,
then
measure or reserve:

1. system/domain instructions;
2. current user input;
3. tool schemas;
4. output tokens;
5. safety margin;
6. RAG evidence or tool output;
7. conversation summary;
8. recent raw turns.

When space is insufficient, the implementation degrades deterministically:

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
become citations. The V46 schema and CAS-backed service implement this path;
V47 adds the durable keyed-turn operation and replay boundary;
compaction is opt-in.

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

A shared budget does not imply shared mutable attempt state. Candidate fallback
and same-model retry receive fresh request-local Memory, advisor chain, and tool
conversation. Failed local messages do not enter the next attempt, while
already-consumed model, tool, and retrieval budget remains spent.

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

The built-in search tools now use the same registry and policy wrapper as
external tools, so built-in and external tools share the safety boundary.

## 10. Follow-Up Work

1. Keep the Boot/Spring AI compatibility matrix and provider capability
   declarations current as dependencies evolve.
2. Add production observability for budget exhaustion, tool policy outcomes,
   summary degradation, and per-provider cost/latency.
3. Expand external-provider examples and real Tool Calling coverage without
   allowing client-defined tool passthrough.
4. Decide whether durable compaction should be enabled by default only after
   production latency, quality, and retention evidence supports it.
5. Continue PostgreSQL and isolated real-LLM regression coverage when changing
   shared Chat, Memory, retrieval, or tool contracts.

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
