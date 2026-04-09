package com.springairag.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * SpringDoc OpenAPI global configuration.
 * Enhances API documentation with example responses, RFC 7807 error schemas,
 * and Swagger UI snippet settings for curl/Java/Python code generation.
 */
@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private int serverPort;

    @Bean
    public OpenAPI springRagOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Spring AI RAG Service API")
                        .description("""
                                General-purpose RAG (Retrieval-Augmented Generation) service framework API.

                                ## Core Capabilities
                                - **Document Management**: CRUD, batch operations, chunking, embedding vector generation
                                - **Retrieval**: Vector search + full-text hybrid retrieval with configurable weights
                                - **Q&A**: Non-streaming + SSE streaming, multi-turn conversation memory
                                - **Evaluation**: Precision@K / MRR / NDCG retrieval quality metrics
                                - **A/B Testing**: Experiment framework for comparing retrieval strategies
                                - **Monitoring**: Alerts, SLO checks, performance benchmarks

                                ## Model-Agnostic
                                Supports OpenAI, DeepSeek, Anthropic, and other LLMs — switch via configuration.

                                ## API Versioning
                                All endpoints are prefixed with `/api/v1/`. Breaking changes will be introduced in `/api/v2/`.

                                ## Try It Out
                                Use the **Try it out** button in Swagger UI to make live API calls.
                                """)
                        .version("1.0.0-SNAPSHOT")
                        .contact(new Contact()
                                .name("Spring AI RAG")
                                .url("https://github.com/yangjiefeng/spring-ai-rag"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server().url("http://localhost:" + serverPort).description("Local development")));
    }

    /**
     * Adds RFC 7807 ErrorResponse schema and 400/500 responses to all endpoints.
     */
    @Bean
    public OpenApiCustomizer globalResponseCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new io.swagger.v3.oas.models.Components());
            }
            Map<String, Schema> schemas = openApi.getComponents().getSchemas();
            if (schemas == null) {
                schemas = new java.util.HashMap<>();
                openApi.getComponents().setSchemas(schemas);
            }
            schemas.put("ErrorResponse", createErrorResponseSchema());

            openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(operation -> {
                    ApiResponses responses = operation.getResponses();
                    if (responses == null) {
                        responses = new ApiResponses();
                        operation.setResponses(responses);
                    }
                    if (!responses.containsKey("400")) {
                        responses.addApiResponse("400", new ApiResponse()
                                .description("Request validation error (RFC 7807 Problem Details)"));
                    }
                    if (!responses.containsKey("500")) {
                        responses.addApiResponse("500", new ApiResponse()
                                .description("Internal server error (RFC 7807 Problem Details)"));
                    }
                }));
        };
    }

    /**
     * Adds realistic example responses to key endpoints so Swagger UI shows
     * sample payloads when "Try it out" is not used.
     */
    @Bean
    public OpenApiCustomizer exampleResponseCustomizer() {
        return openApi -> {
            openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(operation -> {
                    String opId = operation.getOperationId();
                    if (opId == null) return;

                    ApiResponses responses = operation.getResponses();
                    if (responses == null) return;

                    applyExamples(operation, responses, opId);
                }));
        };
    }

    private void applyExamples(io.swagger.v3.oas.models.Operation operation,
                               ApiResponses responses, String opId) {
        // Table-driven example responses keyed by operationId
        ExampleDef example = switch (opId) {
            case "chatAsk" -> new ExampleDef("200",
                """
                    { "answer": "Spring AI is a Spring Framework extension...", "traceId": "a1b2c3d4e5f6",
                      "sources": [{ "documentId": "doc-123", "chunkText": "Spring AI provides a consistent API...", "score": 0.92 }],
                      "metadata": { "sessionId": "conv-123", "model": "deepseek-chat", "retrievalTimeMs": 45 },
                      "stepMetrics": [
                        { "stepName": "QueryRewrite", "durationMs": 120, "resultCount": 1 },
                        { "stepName": "HybridSearch", "durationMs": 38, "resultCount": 10 },
                        { "stepName": "Rerank", "durationMs": 55, "resultCount": 5 }
                      ] }""", "application/json");
            case "chatStream" -> new ExampleDef("200",
                """
                    data: {"answer":"Spring","done":false,"traceId":"abc123","stepMetrics":[]}
                    data: {"answer":" Spring AI","done":false,"traceId":"abc123","stepMetrics":[]}
                    data: {"answer":" is a","done":false,"traceId":"abc123","stepMetrics":[]}
                    data: {"answer":" framework...","done":true,"traceId":"abc123","sources":[{"documentId":"doc-1","chunkText":"...","score":0.9}],"stepMetrics":[{"stepName":"HybridSearch","durationMs":42,"resultCount":8}]}""",
                "text/event-stream");
            case "search" -> new ExampleDef("200",
                """
                    { "results": [
                        { "documentId": 1, "content": "Spring AI provides a consistent API...", "score": 0.92, "highlights": ["Spring AI provides <em>a consistent API</em>"] },
                        { "documentId": 2, "content": "Getting started with Spring AI RAG...", "score": 0.85, "highlights": [] }
                      ], "query": "What is Spring AI?", "totalResults": 2, "traceId": "xyz789" }""", "application/json");
            case "listDocuments" -> new ExampleDef("200",
                """
                    { "documents": [
                        { "id": 1, "title": "Spring AI Guide", "contentType": "text/plain",
                          "processingStatus": "COMPLETED", "enabled": true, "chunkCount": 5,
                          "createdAt": "2026-04-06T10:00:00Z", "updatedAt": "2026-04-06T10:05:00Z" }
                      ], "totalElements": 1, "totalPages": 1, "currentPage": 0 }""", "application/json");
            case "batchCreateDocuments" -> new ExampleDef("200",
                """
                    { "successful": 3, "failed": 0,
                      "results": [
                        { "id": 1, "title": "doc1.txt", "status": "CREATED", "message": null },
                        { "id": 2, "title": "doc2.txt", "status": "CREATED", "message": null },
                        { "id": 3, "title": "doc3.txt", "status": "CREATED", "message": null }
                      ], "traceId": "batch-123" }""", "application/json");
            case "createCollection" -> new ExampleDef("201",
                """
                    { "id": 1, "name": "My Knowledge Base", "description": "Company documentation",
                      "dimensions": 1024, "documentCount": 0,
                      "createdAt": "2026-04-06T10:00:00Z", "updatedAt": "2026-04-06T10:00:00Z" }""", "application/json");
            case "getChatHistory" -> new ExampleDef("200",
                """
                    { "history": [
                        { "messageId": "msg-1", "role": "user", "content": "What is RAG?", "timestamp": "2026-04-06T10:00:00Z" },
                        { "messageId": "msg-2", "role": "assistant", "content": "RAG combines retrieval with generation...", "timestamp": "2026-04-06T10:00:01Z" }
                      ], "totalMessages": 2, "sessionId": "conv-123" }""", "application/json");
            case "getCacheStats" -> new ExampleDef("200",
                """
                    { "enabled": true, "hitCount": 1523, "missCount": 287,
                      "hitRate": 0.841, "totalRequests": 1810, "totalBytesSaved": 4567890 }""", "application/json");
            case "getRagMetrics" -> new ExampleDef("200",
                """
                    { "totalRequests": 5420, "successfulRequests": 5300, "failedRequests": 120,
                      "totalRetrievalResults": 32100, "averageRetrievalResults": 5.92,
                      "totalLlmTokens": 892340, "averageLatencyMs": 342 }""", "application/json");
            default -> null;
        };

        if (example != null) {
            addExampleResponse(responses, example.code, example.json, example.mediaType);
        }
    }

    private record ExampleDef(String code, String json, String mediaType) {}

    private void addExampleResponse(ApiResponses responses, String code, String example, String mediaType) {
        ApiResponse resp = responses.get(code);
        if (resp == null) {
            resp = new ApiResponse();
            responses.addApiResponse(code, resp);
        }
        Content content = resp.getContent();
        if (content == null) {
            content = new Content();
            resp.setContent(content);
        }
        MediaType media = new MediaType();
        Schema<?> schema = new Schema<>();
        schema.setType("object");
        schema.setDescription("Example " + code + " response");
        media.setSchema(schema);
        io.swagger.v3.oas.models.examples.Example ex = new io.swagger.v3.oas.models.examples.Example();
        ex.setValue(example);
        ex.setSummary("Example " + code + " response");
        media.setExamples(Map.of("default", ex));
        content.addMediaType(mediaType, media);
    }

    /**
     * RFC 7807 Problem Details schema for error responses.
     * Spring Boot 3.x uses ProblemDetail for error handling.
     */
    private Schema<?> createErrorResponseSchema() {
        Schema<?> schema = new Schema<>();
        schema.setType("object");
        schema.setDescription("RFC 7807 Problem Details error response");
        schema.addProperty("type", new Schema<>().type("string").description("Error type URI"));
        schema.addProperty("title", new Schema<>().type("string").description("Error title"));
        schema.addProperty("status", new Schema<>().type("integer").format("int32").description("HTTP status code"));
        schema.addProperty("detail", new Schema<>().type("string").description("Error detail"));
        schema.addProperty("instance", new Schema<>().type("string").description("Error instance URI"));
        schema.addProperty("error", new Schema<>().type("string").description("Error code"));
        schema.addProperty("message", new Schema<>().type("string").description("Error message"));
        schema.addProperty("timestamp", new Schema<>().type("string").format("date-time").description("Error timestamp"));
        schema.addProperty("path", new Schema<>().type("string").description("Request path"));
        return schema;
    }
}
