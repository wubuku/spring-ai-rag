# Demo: Domain Extension

Demonstrates how to add domain-specific capabilities to the generic RAG framework via `DomainRagExtension` + `PromptCustomizer`.

This example uses **medical consultation** as the domain, but the same pattern applies to any domain.

## Three Steps to Add a New Domain

### Step 1: Implement DomainRagExtension

```java
@Component
public class MedicalRagExtension implements DomainRagExtension {

    @Override
    public String getDomainId() {
        return "medical";  // Unique domain identifier
    }

    @Override
    public String getSystemPromptTemplate() {
        return """
            You are a professional medical consultation assistant...
            Reference materials: {context}
            """;  // Use {context} placeholder
    }

    @Override
    public RetrievalConfig getRetrievalConfig() {
        return RetrievalConfig.builder()
            .maxResults(15)     // High recall rate
            .minScore(0.3)
            .build();
    }
}
```

### Step 2 (Optional): Implement PromptCustomizer

```java
@Component
public class MedicalPromptCustomizer implements PromptCustomizer {

    @Override
    public String customizeUserMessage(String original, Map<String, Object> metadata) {
        if ("medical".equals(metadata.get("domainId"))) {
            return "[Consultation Mode] User inquiry: " + original;
        }
        return original;
    }
}
```

### Step 3: Pass domainId When Calling

```java
// Method 1: Via ChatRequest
ChatRequest request = new ChatRequest();
request.setMessage("What should I do for a headache?");
request.setDomainId("medical");  // Specify domain
ChatResponse response = ragChatService.chat(request);

// Method 2: Direct call
String answer = ragChatService.chat("What should I do for a headache?", sessionId, "medical", null);
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/medical/consult` | Medical consultation (full mode with citations) |
| GET | `/api/v1/medical/quick?q=headache` | Quick consultation (simplified) |
| POST | `/api/v1/medical/general` | General Q&A (no domain extension, for comparison) |

### Request Examples

```bash
# Medical consultation
curl -X POST http://localhost:8081/api/v1/medical/consult \
  -H "Content-Type: application/json" \
  -d '{"message": "I have been having headaches lately, especially in the afternoon. What could be the cause?"}'

# Quick consultation
curl "http://localhost:8081/api/v1/medical/quick?q=I have a fever of 38.5 degrees, what should I do?"

# Comparison: General Q&A (no domain extension)
curl -X POST http://localhost:8081/api/v1/medical/general \
  -H "Content-Type: application/json" \
  -d '{"message": "What causes headaches?"}'
```

## Prerequisites

Same as [demo-basic-rag](../demo-basic-rag):
- PostgreSQL + pgvector
- DeepSeek API Key
- SiliconFlow API Key (Embedding Model)

## Startup

```bash
# First, build the main project and install to local repository
cd /path/to/spring-ai-rag
mvn clean install -DskipTests

# Start the demo
cd demos/demo-domain-extension
mvn spring-boot:run -Dspring-boot.run.arguments="--DEEPSEEK_API_KEY=sk-xxx --SILICONFLOW_API_KEY=sk-xxx"
```

## Extending to More Domains

Adding a new domain only requires:
1. Create `XxxRagExtension implements DomainRagExtension`
2. Add `@Component` annotation
3. Pass the corresponding `domainId` when calling

No framework code needs to be modified. Each domain extension is developed, tested, and deployed independently.

## Extension Mechanism

| Component | Role | Auto-discovery |
|-----------|------|----------------|
| `DomainRagExtension` | Domain Prompt template + retrieval config | @Component auto-registers |
| `PromptCustomizer` | Prompt chain customization | @Component auto-registers |
| `RagAdvisorProvider` | Custom Advisor injection | @Component auto-registers |

Multiple implementations are sorted by `getOrder()`, and the Starter automatically scans and assembles them.
