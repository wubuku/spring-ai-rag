# Domain Extension Guide

> [English](extension-guide.md) | [中文](extension-guide-zh-CN.md)

> Core design philosophy of spring-ai-rag: **Domain Decoupling**. One general-purpose RAG engine supports N vertical domains.

---

## Extension Mechanism Overview

```
User Request (domainId=medical)
    ↓
DomainExtensionRegistry → finds MedicalDomainExtension
    ↓
Inject domain System Prompt + domain retrieval config
    ↓
RAG Pipeline executes normally
```

Extension points:

| Interface | Purpose | Required |
|-----------|---------|----------|
| `DomainRagExtension` | Mode-safe domain Prompt + retrieval config | Yes |
| `PromptCustomizer` | Fine-grained Prompt control (chainable) | Optional |
| `RagAdvisorProvider` | Inject Spring AI Advisors by mode and scope | Optional |

---

## Quick Start: 3 Steps to Plug in a Domain

### 1. Implement DomainRagExtension

```java
@Component
public class MedicalDomainExtension implements DomainRagExtension {

    @Override
    public String getDomainId() {
        return "medical";
    }

    @Override
    public String getDomainName() {
        return "Healthcare";
    }

    @Override
    public String getSystemPromptTemplate() {
        return """
                You are a healthcare assistant.

                Rules:
                1. Clearly state that the answer is for reference, not medical advice
                2. For medication questions, direct users to a qualified clinician
                """;
    }

    @Override
    public String getSystemPromptTemplate(ChatMode mode) {
        return getSystemPromptTemplate();
    }

    @Override
    public RetrievalConfig getRetrievalConfig() {
        return RetrievalConfig.builder()
                .maxResults(15)        // Medical domain needs more context
                .minScore(0.6)         // Higher similarity threshold
                .useHybridSearch(true)
                .useRerank(true)
                .vectorWeight(0.7)      // Favor semantic retrieval
                .fulltextWeight(0.3)
                .build();
    }

}
```

### 2. Registration is Automatic

`@Component` annotation → Spring Boot auto-discovery → `DomainExtensionRegistry` auto-registers.

No extra configuration needed.

### 3. Specify domainId When Calling

```bash
curl -X POST http://localhost:8081/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{
    "message": "What are the symptoms of hypertension?",
    "sessionId": "s1",
    "domainId": "medical"
  }'
```

---

## Interface Details

### DomainRagExtension

| Method | Required | Description |
|--------|----------|-------------|
| `getDomainId()` | ✅ | Unique domain identifier (English, e.g., `medical`, `legal`) |
| `getDomainName()` | ✅ | Domain display name (e.g., "Healthcare") |
| `getSystemPromptTemplate()` | ✅ | Compatibility entry point; role, safety, and style instructions only |
| `getSystemPromptTemplate(ChatMode)` | | Mode-safe instructions for `KNOWLEDGE`, `AGENT`, and `PLAIN` |
| `getRetrievalConfig()` | | Retrieval config (default: 10 results, 0.5 threshold, hybrid search) |
| `postProcessAnswer()` | deprecated | Not called by production Chat; whole-answer transforms are unsafe for true streaming |
| `isApplicable()` | deprecated | Chat uses caller-selected `domainId` and performs no implicit domain classification |

`CitationQueryAugmenter` and `KnowledgeSearchTool` inject evidence, so new
templates must not contain `{context}`. Legacy templates remain compatible in
`KNOWLEDGE`; `AGENT/PLAIN` returns `DOMAIN_MODE_UNSUPPORTED` until the
extension overrides the mode-aware method.

### PromptCustomizer

For finer-grained Prompt control; multiple implementations are sorted by `getOrder()` and called in chain.

```java
@Component
public class SafetyPromptCustomizer implements PromptCustomizer {

    @Override
    public String customizeSystemPrompt(String originalSystemPrompt,
                                         String context,
                                         Map<String, Object> metadata) {
        return originalSystemPrompt + "\n\nPlease ensure answers comply with safety guidelines and contain no harmful content.";
    }

    @Override
    public int getOrder() {
        return 100; // Execute last
    }
}
```

---

## Multiple Domains Coexist

One service can register multiple domain extensions:

```java
@Configuration
public class DomainConfig {

    @Bean
    public DomainRagExtension medicalExtension() {
        return new MedicalDomainExtension();
    }

    @Bean
    public DomainRagExtension legalExtension() {
        return new LegalDomainExtension();
    }

    @Bean
    public DomainRagExtension financeExtension() {
        return new FinanceDomainExtension();
    }
}
```

Requests select an extension explicitly through `domainId`. Omitting it uses
generic Chat defaults rather than the first registered extension. Unknown IDs
return `UNKNOWN_DOMAIN`.

---

## Extension and RAG Pipeline Collaboration

```
Request → ChatCommandMapper (merge explicit domain RetrievalConfig)
    → ChatExecutionService
      → KNOWLEDGE: RetrievalAugmentationAdvisor + project retrieval
      → AGENT: ToolCallAdvisor + authorized searchKnowledge
      → PLAIN: ChatClient + Memory
    → mode instruction + domain instruction
    → PromptCustomizer chain
    → ChatClient.call/stream()
    → Response
```

Domain extensions affect 2 stages:
1. **Retrieval stage**: `getRetrievalConfig()` controls retrieval parameters
2. **Prompt assembly**: the mode-aware Prompt method provides domain instructions

### RagAdvisorProvider

Existing providers default to `KNOWLEDGE + ATTEMPT`. Opt into other modes
explicitly:

```java
@Override
public Set<ChatMode> supportedModes() {
    return Set.of(ChatMode.KNOWLEDGE, ChatMode.PLAIN);
}

@Override
public AdvisorScope advisorScope() {
    return AdvisorScope.ATTEMPT;
}
```

- `ATTEMPT`: once per model candidate/retry, outside Memory and the mode advisor.
- `MODEL_CALL`: inside the mode advisor, so AGENT executes it for every model
  call in the tool loop. Use only repeatable, idempotent advisors without
  turn-level state.
- `getOrder()` determines relative order inside one scope; the framework maps
  providers into stable, non-overlapping order bands.

---

## Best Practices

### Prompt Templates

- Do not include `{context}`; RAG or Tool Calling injects evidence
- Clearly define role and answer rules
- Keep instructions safe for all three modes; override the mode-aware method only when needed

### Retrieval Config

- Medical/legal/professional domains: increase `minScore` (0.6+) for accuracy
- Customer service/FAQ scenarios: lower `minScore` (0.3) for higher recall
- Long-document domains: increase `maxResults` (15+) for more complete context

Medical, legal, and similar disclaimers belong in domain system instructions
or a streaming-safe Advisor, not in the deprecated whole-answer hook.

---

## Further Reading

- [Architecture Design](architecture.md) — RAG Pipeline and module structure
- [Configuration Reference](configuration.md) — Retrieval parameter configuration
- [REST API Reference](rest-api.md) — API endpoint documentation
