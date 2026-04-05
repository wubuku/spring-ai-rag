package com.springairag.core.contract;

import com.springairag.api.service.AbTestService;
import com.springairag.core.metrics.ComponentHealthService;
import com.springairag.core.repository.*;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.service.*;
import com.springairag.core.config.RagChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI Contract Tests
 *
 * <p>Validates that the running application exposes a valid OpenAPI spec
 * and that all critical schemas and endpoints are properly documented.
 *
 * <p>This is a "producer contract" test — it validates the API documentation
 * matches what the application actually exposes, ensuring API consumers
 * can rely on the published spec.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration," +
                "org.springframework.ai.model.minimax.autoconfigure.MiniMaxChatAutoConfiguration," +
                "org.springframework.ai.model.minimax.autoconfigure.MiniMaxEmbeddingAutoConfiguration," +
                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration"
})
@DisplayName("OpenAPI Contract Tests")
class OpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String OPENAPI_SPEC_PATH = "/v3/api-docs";

    // Schemas that MUST be defined (critical DTOs for API contract)
    private static final java.util.Set<String> REQUIRED_SCHEMAS = java.util.Set.of(
            "ChatRequest",
            "ChatResponse",
            "SearchRequest",
            "RetrievalResult",
            "DocumentRequest",
            "CollectionRequest",
            "ErrorResponse",
            "HealthResponse",
            "BatchDocumentRequest",
            "BatchCreateResponse"
    );

    // Endpoints that MUST be documented
    private static final java.util.Set<String> REQUIRED_PATH_SUFFIXES = java.util.Set.of(
            "/rag/chat/ask",
            "/rag/search",
            "/rag/documents",
            "/rag/collections",
            "/rag/health",
            "/rag/models"
    );

    // ==================== Mock all external dependencies ====================
    // Chat (RagChatService is in config package)
    @MockBean
    private RagChatService ragChatService;

    @MockBean
    private ChatExportService chatExportService;

    @MockBean
    private RagChatHistoryRepository historyRepository;

    // Search
    @MockBean
    private HybridRetrieverService hybridRetrieverService;

    // Document
    @MockBean
    private RagDocumentRepository documentRepository;

    @MockBean
    private RagEmbeddingRepository embeddingRepository;

    @MockBean
    private EmbeddingBatchService embeddingBatchService;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private VectorStore vectorStore;

    @MockBean
    private DocumentEmbedService documentEmbedService;

    @MockBean
    private BatchDocumentService batchDocumentService;

    @MockBean
    private DocumentVersionService documentVersionService;

    // Collection
    @MockBean
    private RagCollectionRepository collectionRepository;

    // AB Test
    @MockBean
    private AbTestService abTestService;

    // Evaluation
    @MockBean
    private RetrievalEvaluationService evaluationService;

    @MockBean
    private UserFeedbackService userFeedbackService;

    // Alert
    @MockBean
    private AlertService alertService;

    @MockBean
    private SloConfigRepository sloConfigRepository;

    @MockBean
    private RagSilenceScheduleRepository ragSilenceScheduleRepository;

    // Health
    @MockBean
    private ComponentHealthService componentHealthService;

    // Model
    @MockBean
    private com.springairag.core.config.ModelRegistry modelRegistry;

    // AI / Chat
    @MockBean
    private ChatModel chatModel;

    // EmbeddingModel (multiple implementations, mock the interface)
    @MockBean
    private org.springframework.ai.embedding.EmbeddingModel embeddingModel;

    @Nested
    @DisplayName("Spec Accessibility")
    class SpecAccessibility {

        @Test
        @DisplayName("GET /v3/api-docs returns 200 with valid OpenAPI 3.0 JSON")
        void specEndpoint_returns200() throws Exception {
            mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andExpect(result -> {
                        String content = result.getResponse().getContentAsString();
                        assertThat(content).contains("\"openapi\"");
                        assertThat(content).contains("\"3.");
                    });
        }

        @Test
        @DisplayName("Spec is valid JSON with required top-level fields")
        void specHasRequiredFields() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());

            assertThat(spec.has("openapi")).isTrue();
            assertThat(spec.has("info")).isTrue();
            assertThat(spec.has("paths")).isTrue();
            assertThat(spec.has("components")).isTrue();

            // openapi must be 3.x
            String openapiVersion = spec.get("openapi").asText();
            assertThat(openapiVersion).startsWith("3.");
        }

        @Test
        @DisplayName("Info section contains title and version")
        void infoSection_valid() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode info = spec.get("info");

            assertThat(info.has("title")).isTrue();
            assertThat(info.has("version")).isTrue();
            assertThat(info.get("title").asText()).isNotBlank();
            assertThat(info.get("version").asText()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("Schema Definitions")
    class SchemaDefinitions {

        @Test
        @DisplayName("All required schemas are defined in components/schemas")
        void requiredSchemas_exist() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode schemas = spec.get("components").get("schemas");

            assertThat(schemas).isNotNull();
            for (String requiredSchema : REQUIRED_SCHEMAS) {
                assertThat(schemas.has(requiredSchema))
                        .as("Schema '%s' must be defined", requiredSchema)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("ErrorResponse schema has required error fields")
        void errorResponseSchema_hasRequiredFields() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode errorSchema = spec.get("components").get("schemas").get("ErrorResponse");

            assertThat(errorSchema).isNotNull();
            JsonNode properties = errorSchema.get("properties");

            // ErrorResponse should document its RFC 7807 fields
            assertThat(properties.has("type")).isTrue();
            assertThat(properties.has("title")).isTrue();
            assertThat(properties.has("status")).isTrue();
        }

        @Test
        @DisplayName("Schema type consistency: all defined schemas use valid JSON types")
        void schemasUseValidTypes() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode schemas = spec.get("components").get("schemas");

            if (schemas != null) {
                Iterator<String> it = schemas.fieldNames();
                while (it.hasNext()) {
                    String schemaName = it.next();
                    JsonNode schema = schemas.get(schemaName);
                    if (schema.has("type")) {
                        String type = schema.get("type").asText();
                        assertThat(type)
                                .as("Schema '%s' should have a valid type", schemaName)
                                .isIn("string", "object", "array", "number", "integer", "boolean");
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Endpoint Documentation")
    class EndpointDocumentation {

        @Test
        @DisplayName("All required path suffixes are documented")
        void requiredPaths_exist() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode paths = spec.get("paths");

            assertThat(paths).isNotNull();
            for (String suffix : REQUIRED_PATH_SUFFIXES) {
                boolean found = false;
                Iterator<String> pathIt = paths.fieldNames();
                while (pathIt.hasNext() && !found) {
                    found = pathIt.next().endsWith(suffix);
                }
                assertThat(found)
                        .as("Path ending with '%s' must be documented", suffix)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("GET /rag/health is documented with 200 response")
        void healthEndpoint_has200Response() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode paths = spec.get("paths");

            // Find health path
            String healthPath = null;
            Iterator<String> it = paths.fieldNames();
            while (it.hasNext()) {
                String path = it.next();
                if (path.contains("/rag/health")) {
                    healthPath = path;
                    break;
                }
            }

            assertThat(healthPath).isNotNull();
            JsonNode getOp = paths.get(healthPath).get("get");
            assertThat(getOp).isNotNull();
            assertThat(getOp.has("responses")).isTrue();
            assertThat(getOp.get("responses").has("200")).isTrue();
        }

        @Test
        @DisplayName("POST /rag/chat has request body documented")
        void postEndpoints_haveRequestBody() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode paths = spec.get("paths");

            // Find chat path
            String chatPath = null;
            Iterator<String> it = paths.fieldNames();
            while (it.hasNext()) {
                String path = it.next();
                if (path.contains("/rag/chat")) {
                    chatPath = path;
                    break;
                }
            }

            if (chatPath != null) {
                JsonNode postOp = paths.get(chatPath).get("post");
                if (postOp != null) {
                    assertThat(postOp.has("requestBody"))
                            .as("POST /rag/chat should document request body")
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("All operations have operationId or summary for client code generation")
        void endpointsHaveIdentification() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode paths = spec.get("paths");

            Iterator<String> pathIt = paths.fieldNames();
            while (pathIt.hasNext()) {
                String path = pathIt.next();
                JsonNode pathItem = paths.get(path);
                Iterator<String> methodIt = pathItem.fieldNames();
                while (methodIt.hasNext()) {
                    String method = methodIt.next();
                    if (method.matches("get|post|put|delete|patch")) {
                        String currentPath = path;
                        String currentMethod = method;
                        JsonNode op = pathItem.get(currentMethod);
                        boolean hasId = op.has("operationId");
                        boolean hasSummary = op.has("summary");
                        assertThat(hasId || hasSummary)
                                .as("Path '%s' %s should have operationId or summary", currentPath, currentMethod)
                                .isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("All responses use application/json content type")
        void responsesUseJsonContentType() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode paths = spec.get("paths");

            Iterator<String> pathIt = paths.fieldNames();
            while (pathIt.hasNext()) {
                String path = pathIt.next();
                JsonNode pathItem = paths.get(path);
                Iterator<String> methodIt = pathItem.fieldNames();
                while (methodIt.hasNext()) {
                    String method = methodIt.next();
                    if (!method.matches("get|post|put|delete|patch")) continue;
                    String currentPath = path;
                    String currentMethod = method;
                    JsonNode op = pathItem.get(currentMethod);
                    JsonNode responses = op.get("responses");
                    if (responses != null) {
                        Iterator<String> statusIt = responses.fieldNames();
                        while (statusIt.hasNext()) {
                            String statusCode = statusIt.next();
                            String currentStatus = statusCode;
                            JsonNode response = responses.get(currentStatus);
                            if (response.has("content")) {
                                JsonNode content = response.get("content");
                                // Skip SSE (text/event-stream) and HTML responses - they don't produce JSON
                                boolean isNonJson = content.has("text/event-stream") || content.has("text/html");
                                if (isNonJson) continue;
                                // Accept application/json OR */* (springdoc uses */* for Map<String,Object> return types)
                                boolean hasJsonOrWildcard = content.has("application/json") || content.has("*/*");
                                assertThat(hasJsonOrWildcard)
                                        .as("Response %s for %s %s should specify application/json or */*",
                                                currentStatus, currentMethod, currentPath)
                                        .isTrue();
                            }
                        }
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Tag Organization")
    class TagOrganization {

        @Test
        @DisplayName("Spec defines tags for grouping endpoints (optional)")
        void specHasTags() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode tags = spec.get("tags");

            // Tags are optional but recommended for organization
            // If present, should not be empty
            if (tags != null && tags.isArray()) {
                assertThat(tags.size()).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("At least some endpoints are tagged for categorization")
        void endpointsAreTagged() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode paths = spec.get("paths");

            int taggedCount = 0;

            Iterator<String> pathIt = paths.fieldNames();
            while (pathIt.hasNext()) {
                String path = pathIt.next();
                JsonNode pathItem = paths.get(path);
                Iterator<String> methodIt = pathItem.fieldNames();
                while (methodIt.hasNext()) {
                    String method = methodIt.next();
                    if (method.matches("get|post|put|delete|patch")) {
                        JsonNode op = pathItem.get(method);
                        if (op.has("tags") && op.get("tags").size() > 0) {
                            taggedCount++;
                        }
                    }
                }
            }

            // At least some operations should be tagged
            assertThat(taggedCount)
                    .as("At least some endpoints should be tagged for organization")
                    .isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Server Configuration")
    class ServerConfiguration {

        @Test
        @DisplayName("Spec declares at least one server (optional but recommended)")
        void specHasServer() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());

            // Server declaration is recommended but not required
            // If present, should have url
            if (spec.has("servers")) {
                JsonNode servers = spec.get("servers");
                assertThat(servers.isArray()).isTrue();
                assertThat(servers.size()).isGreaterThan(0);
                assertThat(servers.get(0).has("url")).isTrue();
            }
        }
    }

    // ========================================================================
    // B9-1: Additional Schema Contract Tests (OpenAPI Schema Validation)
    // ========================================================================

    @Nested
    @DisplayName("B9-1 — Error Response RFC 7807 Schema Contract")
    class ErrorResponseSchemaContract {

        @Test
        @DisplayName("ErrorResponse schema has RFC 7807 fields: type, title, status")
        void errorResponseSchema_hasRFC7807Fields() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode errorSchema = spec.path("components").path("schemas").path("ErrorResponse");

            assertThat(errorSchema.isMissingNode()).isFalse();
            JsonNode props = errorSchema.path("properties");
            assertThat(props.has("type"))
                    .as("ErrorResponse must have 'type' field (RFC 7807)")
                    .isTrue();
            assertThat(props.has("title") || props.has("detail"))
                    .as("ErrorResponse must have 'title' or 'detail' field (RFC 7807)")
                    .isTrue();
            assertThat(props.has("status"))
                    .as("ErrorResponse must have 'status' field (RFC 7807)")
                    .isTrue();
        }

        @Test
        @DisplayName("ErrorResponse schema has no invalid type values")
        void errorResponseSchema_validTypes() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode errorSchema = spec.path("components").path("schemas").path("ErrorResponse");

            if (errorSchema.isMissingNode()) return;
            JsonNode props = errorSchema.path("properties");
            for (var field : iterable(props.fieldNames())) {
                JsonNode fieldSchema = props.get(field);
                if (fieldSchema.has("type")) {
                    String type = fieldSchema.get("type").asText();
                    assertThat(java.util.List.of("string", "integer", "boolean", "object", "array", "null"))
                            .as("Field '%s' must have a valid JSON Schema type", field)
                            .contains(type);
                }
            }
        }


    }

    @Nested
    @DisplayName("B9-1 — Request Schema Field Completeness")
    class RequestSchemaFieldContract {

        @Test
        @DisplayName("SearchRequest schema exists and has query field")
        void searchRequestSchema_hasQueryField() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode schemas = spec.path("components").path("schemas");
            assertThat(schemas.has("SearchRequest"))
                    .as("SearchRequest schema must be defined")
                    .isTrue();
            assertThat(schemas.get("SearchRequest").path("properties").has("query"))
                    .as("SearchRequest must have 'query' field")
                    .isTrue();
        }

        @Test
        @DisplayName("CollectionRequest schema exists and has name field")
        void collectionRequestSchema_hasNameField() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode schemas = spec.path("components").path("schemas");
            if (!schemas.isMissingNode() && schemas.has("CollectionRequest")) {
                assertThat(schemas.get("CollectionRequest").path("properties").has("name"))
                        .as("CollectionRequest must have 'name' field")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("All request schemas use 'object' type (not primitive or array)")
        void requestSchemas_areObjects() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode schemas = spec.path("components").path("schemas");

            for (String schemaName : java.util.List.of("ChatRequest", "SearchRequest", "CollectionRequest", "DocumentRequest")) {
                if (schemas.has(schemaName)) {
                    JsonNode schema = schemas.get(schemaName);
                    if (schema.has("type")) {
                        assertThat(schema.get("type").asText())
                                .as("'%s' schema type must be 'object'", schemaName)
                                .isEqualTo("object");
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("B9-1 — OpenAPI Spec Completeness")
    class SpecCompletenessContract {

        @Test
        @DisplayName("All required schemas are defined")
        void allRequiredSchemasExist() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode schemas = spec.path("components").path("schemas");

            for (String required : REQUIRED_SCHEMAS) {
                assertThat(schemas.has(required))
                        .as("Required schema '%s' must be defined", required)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("paths are not empty (at least 5 endpoints documented)")
        void pathsNotEmpty() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode paths = spec.get("paths");
            assertThat(paths.isObject()).isTrue();
            assertThat(paths.size())
                    .as("At least 5 endpoints should be documented in the spec")
                    .isGreaterThanOrEqualTo(5);
        }

        @Test
        @DisplayName("All operations have operationId or summary")
        void operationsHaveIdentity() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode paths = spec.get("paths");

            int operationsWithoutId = 0;
            for (var pathKey : iterable(paths.fieldNames())) {
                JsonNode pathItem = paths.get(pathKey);
                for (String method : java.util.List.of("get", "post", "put", "delete", "patch")) {
                    JsonNode op = pathItem.get(method);
                    if (op == null) continue;
                    boolean hasId = op.has("operationId");
                    boolean hasSummary = op.has("summary");
                    if (!hasId && !hasSummary) {
                        operationsWithoutId++;
                    }
                }
            }
            assertThat(operationsWithoutId)
                    .as("No operation should be missing both operationId and summary")
                    .isEqualTo(0);
        }
    }

    private static <T> Iterable<T> iterable(Iterator<T> it) {
        return () -> it;
    }
}
