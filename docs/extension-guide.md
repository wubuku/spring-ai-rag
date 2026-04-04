# Domain Extension Guide

> 📖 English | 📖 中文

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
| `DomainRagExtension` | Domain Prompt + retrieval config + answer post-processing | ✅ |
| `PromptCustomizer` | Fine-grained Prompt control (chainable) | Optional |

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
                You are a medical health knowledge assistant. Answer the user's question based on the retrieved medical literature.

                Rules:
                1. Strictly base on reference materials; do not fabricate medical information
                2. Clearly mark "For reference only, not medical advice"
                3. When medication advice is involved, remind users to consult a professional doctor

                References:
                {context}
                """;
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

    @Override
    public String postProcessAnswer(String answer) {
        // Auto-append disclaimer
        if (!answer.contains("disclaimer")) {
            return answer + "\n\n---\n⚠️ Disclaimer: The above is for reference only and does not constitute medical advice."
                    + " For health concerns, please consult a professional doctor.";
        }
        return answer;
    }

    @Override
    public boolean isApplicable(String query) {
        // Simple keyword check (use NLP classifier in production)
        String lower = query.toLowerCase();
        return lower.contains("disease") || lower.contains("symptom")
                || lower.contains("medicine") || lower.contains("treatment")
                || lower.contains("doctor") || lower.contains("health");
    }
}
```

### 2. Registration is Automatic

`@Component` annotation → Spring Boot auto-discovery → `DomainExtensionRegistry` auto-registers.

No extra configuration needed.

### 3. Specify domainId When Calling

```bash
curl -X POST http://localhost:8080/api/v1/rag/chat/ask \
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
| `getSystemPromptTemplate()` | ✅ | System prompt template; `{context}` placeholder is replaced by retrieved documents |
| `getRetrievalConfig()` | | Retrieval config (default: 10 results, 0.5 threshold, hybrid search) |
| `postProcessAnswer()` | | Answer post-processing (default: return as-is) |
| `isApplicable()` | | Query domain detection (default: accept all) |

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

Requests select via `domainId`; unspecified uses the first registered extension.

---

## Extension and RAG Pipeline Collaboration

```
Request → QueryRewriteAdvisor (query rewrite, domain-agnostic)
    → HybridSearchAdvisor (hybrid retrieval, uses domain RetrievalConfig)
    → RerankAdvisor (reranking, uses domain RetrievalConfig)
    → Assemble Prompt (domain System Prompt + retrieval context)
    → PromptCustomizer chain (fine-grained customization)
    → ChatClient.call/stream()
    → DomainRagExtension.postProcessAnswer()
    → Response
```

Domain extensions affect 3 stages:
1. **Retrieval stage**: `getRetrievalConfig()` controls retrieval parameters
2. **Prompt assembly**: `getSystemPromptTemplate()` provides domain Prompt
3. **Result processing**: `postProcessAnswer()` post-processes the answer

---

## Best Practices

### Prompt Templates

- Include `{context}` placeholder, which RAG replaces with retrieved document fragments
- Clearly define role and answer rules
- Keep under 20 lines; complex logic belongs in `postProcessAnswer()`

### Retrieval Config

- Medical/legal/professional domains: increase `minScore` (0.6+) for accuracy
- Customer service/FAQ scenarios: lower `minScore` (0.3) for higher recall
- Long-document domains: increase `maxResults` (15+) for more complete context

### Answer Post-Processing

- Append disclaimers (medical, legal domains)
- Format output (add Markdown structure)
- Quality check (verify key information is not missing)

---

## Further Reading

- [Architecture Design](architecture.md) — RAG Pipeline and module structure
- [Configuration Reference](configuration.md) — Retrieval parameter configuration
- [REST API Reference](rest-api.md) — API endpoint documentation
