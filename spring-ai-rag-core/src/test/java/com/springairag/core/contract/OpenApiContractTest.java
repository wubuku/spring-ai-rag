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
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

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
        "rag.embedding-jobs.enabled=false",
        "rag.embedding.api-key=test-embedding-key",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration," +
                "org.springframework.ai.model.minimax.autoconfigure.MiniMaxChatAutoConfiguration," +
                "org.springframework.ai.model.minimax.autoconfigure.MiniMaxEmbeddingAutoConfiguration," +
                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
        "management.health.db.enabled=false",
        "management.endpoint.health.validate-group-membership=false"
})
@DisplayName("OpenAPI Contract Tests")
class OpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CollectionProvisioningService collectionProvisioningService;

    @Autowired
    private AuditLogService auditLogService;

    private static final String OPENAPI_SPEC_PATH = "/v3/api-docs";

    // Schemas that MUST be defined (critical DTOs for API contract)
    private static final java.util.Set<String> REQUIRED_SCHEMAS = java.util.Set.of(
            "ChatRequest",
            "ChatResponse",
            "SearchRequest",
            "CollectionScopeMode",
            "CollectionAccessMode",
            "RetrievalResult",
            "DocumentRequest",
            "CollectionRequest",
            "CollectionCloneRequest",
            "CollectionUpdateRequest",
            "ApiKeyCreateRequest",
            "ApiKeyCreatedResponse",
            "ApiKeyIdentityResponse",
            "IntegrationCapabilitiesResponse",
            "IntegrationObservabilityResponse",
            "DocumentSyncRunItemPageResponse",
            "DocumentSyncRunItemReceiptResponse",
            "DocumentSyncRunItemCurrentSummary",
            "ErrorResponse",
            "HealthResponse",
            "BatchDocumentRequest",
            "BatchCreateResponse",
            "ExternalDocumentUpsertRequest",
            "ExternalDocumentBatchUpsertRequest",
            "ExternalDocumentUpsertResponse",
            "ExternalDocumentBatchUpsertResponse",
            "ExternalDocumentDeleteResponse",
            "ExternalDocumentRelocateRequest",
            "ExternalDocumentRelocateResponse",
            "RetrievalFilterRequest",
            "RetrievalTracePageResponse",
            "RetrievalTraceDetailResponse",
            "EmbeddingJobPageResponse",
            "CollectionEmbeddingReadinessResponse",
            "DerivationReadinessResponse",
            "DerivationReadinessPageResponse",
            "DerivationReadinessDocument",
            "DerivationRepairPreviewRequest",
            "DerivationRepairPreviewResponse",
            "DerivationRepairApplyRequest",
            "DerivationRepairStatusResponse",
            "EvaluationSuiteCreateRequest",
            "EvaluationSuiteResponse",
            "EvaluationSuiteVersionCreateRequest",
            "EvaluationSuiteVersionResponse",
            "EvaluationRunCreateRequest",
            "EvaluationRunResponse",
            "EvaluationCompareResponse",
            "SemanticEvaluationRequest",
            "SemanticEvaluationResponse",
            "LlmUsageResponse"
    );

    // Endpoints that MUST be documented
    private static final java.util.Set<String> REQUIRED_PATH_SUFFIXES = java.util.Set.of(
            "/rag/chat/ask",
            "/rag/search",
            "/rag/documents",
            "/rag/collections",
            "/rag/documents/upsert",
            "/rag/documents/batch-upsert",
            "/rag/documents/by-external-id",
            "/rag/documents/relocate",
            "/rag/auth/me",
            "/rag/api-keys",
            "/rag/integration-capabilities",
            "/rag/integration-observability",
            "/rag/document-sync-runs/{runId}/items",
            "/rag/health",
            "/rag/models",
            "/rag/retrieval-traces",
            "/rag/embedding-jobs",
            "/rag/collections/embedding-readiness",
            "/rag/collections/derivation-readiness",
            "/rag/collections/derivation-readiness/documents",
            "/rag/collections/derivation-repairs/preview",
            "/rag/collections/derivation-repairs/apply",
            "/rag/evaluation/suites",
            "/rag/evaluation/runs",
            "/rag/evaluation/semantic",
            "/rag/usage"
    );

    // ==================== Mock all external dependencies ====================
    // Chat (RagChatService is in config package)
    @MockBean
    private RagChatService ragChatService;

    @MockBean
    private ChatExportService chatExportService;

    @MockBean
    private RagChatHistoryRepository historyRepository;

    @MockBean
    private JdbcChatMemoryRepository chatMemoryRepository;

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
    private PlatformTransactionManager transactionManager;

    @MockBean
    private DocumentEmbedService documentEmbedService;

    @MockBean
    private BatchDocumentService batchDocumentService;

    @MockBean
    private DocumentVersionService documentVersionService;

    @MockBean
    private JsonRecordService jsonRecordService;

    @MockBean
    private DocumentRelocationService documentRelocationService;

    // Collection
    @MockBean
    private RagCollectionRepository collectionRepository;

    @MockBean
    private CollectionProvisioningOperationRepository collectionProvisioningOperationRepository;

    @MockBean
    private RagAuditLogRepository auditLogRepository;

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

    // Client Error
    @MockBean
    private RagClientErrorRepository ragClientErrorRepository;

    // API Key Management
    @MockBean
    private com.springairag.core.repository.RagApiKeyRepository ragApiKeyRepository;

    @MockBean
    private com.springairag.core.repository.RagApiPrincipalRepository ragApiPrincipalRepository;

    @MockBean
    private com.springairag.core.service.ApiKeyManagementService apiKeyManagementService;

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

    @MockBean
    private com.springairag.core.repository.FsFileRepository fsFileRepository;

    @MockBean
    private FsImportBatchRepository fsImportBatchRepository;

    @Nested
    @DisplayName("Application Wiring")
    class ApplicationWiring {

        @Test
        @DisplayName("Collection provisioning service is registered in the runtime context")
        void collectionProvisioningService_isRegistered() {
            assertThat(collectionProvisioningService).isNotNull();
            assertThat(auditLogService).isNotNull();
        }
    }

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
            assertThat(info.get("version").asText()).isEqualTo("1.0.0");
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
        @DisplayName("RetrievalResult schema exposes file provenance fields")
        void retrievalResultSchema_hasFileProvenanceFields() throws Exception {
            JsonNode properties = loadSpec()
                    .path("components")
                    .path("schemas")
                    .path("RetrievalResult")
                    .path("properties");

            assertThat(properties.has("source")).isTrue();
            assertThat(properties.has("originalFilename")).isTrue();
            assertThat(properties.has("fileDirectoryPath")).isTrue();
            assertThat(properties.has("indexedFilePath")).isTrue();
            assertThat(properties.has("originalFilePath")).isTrue();
        }

        @Test
        @DisplayName("CollectionScopeMode exposes all supported values")
        void collectionScopeModeSchema_hasSupportedValues() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode schemas = objectMapper.readTree(
                    result.getResponse().getContentAsString())
                    .get("components").get("schemas");
            JsonNode values = schemas.get("CollectionScopeMode").get("enum");

            assertThat(values).isNotNull();
            assertThat(java.util.stream.StreamSupport
                    .stream(values.spliterator(), false)
                    .map(JsonNode::asText)
                    .toList())
                    .containsExactly(
                            "CALLER_VISIBLE",
                            "ANY_COLLECTION",
                            "SELECTED_COLLECTIONS");
        }

        @Test
        @DisplayName("API identity schema exposes role and Collection access contract")
        void apiKeyIdentitySchema_hasAccessContract() throws Exception {
            JsonNode schemas = loadSpec().path("components").path("schemas");
            JsonNode identity = schemas.path("ApiKeyIdentityResponse");
            JsonNode properties = identity.path("properties");

            assertThat(identity.isMissingNode()).isFalse();
            assertThat(properties.has("principalRole")).isTrue();
            assertThat(properties.has("collectionAccessMode")).isTrue();
            assertThat(properties.has("allowedCollectionKeys")).isTrue();

            JsonNode accessValues = schemas.path("CollectionAccessMode").path("enum");
            assertThat(java.util.stream.StreamSupport
                    .stream(accessValues.spliterator(), false)
                    .map(JsonNode::asText)
                    .toList())
                    .containsExactly("RESTRICTED", "UNRESTRICTED");
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
        @DisplayName("GET /rag/usage is documented with the durable usage response")
        void usageEndpoint_hasContract() throws Exception {
            JsonNode usagePath = findPath(loadSpec().path("paths"), "/rag/usage");
            JsonNode get = usagePath.path("get");

            assertThat(get.isMissingNode()).isFalse();
            assertThat(get.path("responses").has("200")).isTrue();
            assertThat(get.path("responses").has("400")).isTrue();
            assertThat(get.path("responses").has("403")).isTrue();
            assertThat(get.path("responses").path("200").path("content")
                    .path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/LlmUsageResponse");
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
        @DisplayName("All responses use a supported JSON content type")
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
                                // RFC 7807 errors use application/problem+json. Springdoc uses */* for
                                // some Map<String, Object> response types.
                                boolean hasJsonOrWildcard = content.has("application/json")
                                        || content.has("application/problem+json")
                                        || content.has("*/*");
                                assertThat(hasJsonOrWildcard)
                                        .as("Response %s for %s %s should specify a supported JSON content type",
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
    @DisplayName("Collection Key Contract")
    class CollectionKeyContract {

        @Test
        void collectionCreateSchemaDefinesRequiredVisibleAsciiKey() throws Exception {
            JsonNode spec = loadSpec();
            JsonNode schema = spec.path("components").path("schemas")
                    .path("CollectionRequest");
            JsonNode key = schema.path("properties").path("collectionKey");

            assertThat(key.isMissingNode()).isFalse();
            assertThat(key.path("type").asText()).isEqualTo("string");
            assertThat(key.path("minLength").asInt()).isEqualTo(1);
            assertThat(key.path("maxLength").asInt()).isEqualTo(128);
            assertThat(key.path("pattern").asText())
                    .isEqualTo("^[\\x21-\\x7E]{1,128}$");
            assertThat(iterable(schema.path("required").elements()))
                    .extracting(JsonNode::asText)
                    .contains("collectionKey");
        }

        @Test
        void collectionUpdateSchemaDoesNotExposeImmutableKey() throws Exception {
            JsonNode schemas = loadSpec().path("components").path("schemas");
            JsonNode update = schemas.path("CollectionUpdateRequest");

            assertThat(update.isMissingNode()).isFalse();
            assertThat(update.path("properties").has("collectionKey")).isFalse();
        }

        @Test
        void collectionKeyFieldsExistAndLegacyIdsAreDeprecated() throws Exception {
            JsonNode schemas = loadSpec().path("components").path("schemas");

            for (String schemaName : java.util.List.of(
                    "ChatRequest", "SearchRequest")) {
                JsonNode properties = schemas.path(schemaName).path("properties");
                assertThat(properties.has("collectionKeys")).isTrue();
                assertThat(properties.path("collectionIds")
                        .path("deprecated").asBoolean()).isTrue();
            }

            JsonNode document = schemas.path("DocumentRequest").path("properties");
            assertThat(document.has("collectionKey")).isTrue();
            assertThat(document.path("collectionId")
                    .path("deprecated").asBoolean()).isTrue();

            JsonNode apiKey = schemas.path("ApiKeyCreateRequest").path("properties");
            assertThat(apiKey.has("allowedCollectionKeys")).isTrue();
            assertThat(apiKey.has("capabilities")).isTrue();
            assertThat(apiKey.path("allowedCollectionIds")
                    .path("deprecated").asBoolean()).isTrue();

            JsonNode jsonUpsert = schemas.path("JsonRecordUpsertRequest")
                    .path("properties");
            assertThat(jsonUpsert.has("collectionKey")).isTrue();
            assertThat(jsonUpsert.path("collectionId")
                    .path("deprecated").asBoolean()).isTrue();

            JsonNode jsonSearch = schemas.path("JsonRecordSearchRequest")
                    .path("properties");
            assertThat(jsonSearch.has("collectionKeys")).isTrue();
            assertThat(jsonSearch.path("collectionIds")
                    .path("deprecated").asBoolean()).isTrue();

            for (String schemaName : java.util.List.of(
                    "JsonRecordUpsertResponse",
                    "JsonRecordDetailResponse",
                    "JsonRecordSearchResult")) {
                assertThat(schemas.path(schemaName).path("properties")
                        .has("collectionKey")).isTrue();
            }
        }

        @Test
        void byKeyAndCloneRoutesDocumentStringKeyAndConflictResponses()
                throws Exception {
            JsonNode paths = loadSpec().path("paths");
            JsonNode byKey = findPath(paths, "/rag/collections/by-key");
            assertThat(byKey.isMissingNode()).isFalse();
            JsonNode collectionKeyParameter = byKey.path("get")
                    .path("parameters").get(0);
            assertThat(collectionKeyParameter.path("name").asText())
                    .isEqualTo("collectionKey");
            assertThat(collectionKeyParameter.path("schema")
                    .path("type").asText()).isEqualTo("string");

            JsonNode create = findPath(paths, "/rag/collections").path("post");
            assertThat(create.path("responses").has("409")).isTrue();

            JsonNode clone = findPath(
                    paths, "/rag/collections/{id}/clone").path("post");
            assertThat(clone.path("responses").has("400")).isTrue();
            assertThat(clone.path("responses").has("403")).isTrue();
            assertThat(clone.path("responses").has("409")).isTrue();

            JsonNode cloneByKey = findPath(
                    paths, "/rag/collections/clone").path("post");
            assertThat(cloneByKey.isMissingNode()).isFalse();
            assertThat(cloneByKey.path("responses").has("400")).isTrue();
            assertThat(cloneByKey.path("responses").has("403")).isTrue();
            assertThat(cloneByKey.path("responses").has("409")).isTrue();

            JsonNode cloneRequest = loadSpec().path("components").path("schemas")
                    .path("CollectionCloneRequest");
            assertThat(cloneRequest.path("required"))
                    .anySatisfy(node ->
                            assertThat(node.asText()).isEqualTo("sourceCollectionKey"));
            assertThat(cloneRequest.path("required"))
                    .anySatisfy(node ->
                            assertThat(node.asText()).isEqualTo("collectionKey"));
        }

        @Test
        void legacyNumericCollectionOperationsAndParametersAreDeprecated()
                throws Exception {
            JsonNode paths = loadSpec().path("paths");

            JsonNode collectionById = findPath(
                    paths, "/rag/collections/{id}");
            for (String method : java.util.List.of("get", "put", "delete")) {
                assertThat(collectionById.path(method)
                        .path("deprecated").asBoolean()).isTrue();
            }
            for (String path : java.util.List.of(
                    "/rag/collections/{id}/restore",
                    "/rag/collections/{id}/clone",
                    "/rag/collections/{id}/export")) {
                assertThat(findPath(paths, path).path("post").isMissingNode()
                        ? findPath(paths, path).path("get")
                                .path("deprecated").asBoolean()
                        : findPath(paths, path).path("post")
                                .path("deprecated").asBoolean())
                        .isTrue();
            }
            JsonNode collectionDocuments = findPath(
                    paths, "/rag/collections/{id}/documents");
            assertThat(collectionDocuments.path("get")
                    .path("deprecated").asBoolean()).isTrue();
            assertThat(collectionDocuments.path("post")
                    .path("deprecated").asBoolean()).isTrue();

            JsonNode documents = findPath(paths, "/rag/documents")
                    .path("get").path("parameters");
            assertThat(iterable(documents.elements()))
                    .anySatisfy(parameter -> {
                        assertThat(parameter.path("name").asText())
                                .isEqualTo("collectionId");
                        assertThat(parameter.path("deprecated").asBoolean())
                                .isTrue();
                    });

            JsonNode search = findPath(paths, "/rag/search")
                    .path("get").path("parameters");
            assertThat(iterable(search.elements()))
                    .anySatisfy(parameter -> {
                        assertThat(parameter.path("name").asText())
                                .isEqualTo("collectionIds");
                        assertThat(parameter.path("deprecated").asBoolean())
                                .isTrue();
                    });
        }

        @Test
        void jsonRecordExternalIdentityParametersExposeProductionLimits()
                throws Exception {
            JsonNode path = findPath(
                    loadSpec().path("paths"),
                    "/rag/json-records/by-external-id");

            JsonNode lookup = path.path("get");
            assertParameter(lookup, "collectionKey", true, 1, 128);
            assertParameter(lookup, "sourceNamespace", false, 0, 128);
            assertThat(findParameter(lookup, "sourceNamespace")
                    .path("description").asText())
                    .contains("default");
            assertParameter(lookup, "externalId", true, 1, 255);

            JsonNode tombstone = path.path("delete");
            assertParameter(tombstone, "collectionKey", true, 1, 128);
            assertParameter(tombstone, "sourceNamespace", false, 0, 128);
            assertParameter(tombstone, "externalId", true, 1, 255);
            assertParameter(tombstone, "sourceRevision", true, 1, 255);
            assertParameter(
                    tombstone, "expectedSourceRevision", false, 0, 255);
        }
    }

    @Nested
    @DisplayName("B9-1 — OpenAPI Spec Completeness")
    class SpecCompletenessContract {

        @Test
        @DisplayName("Provisioning and capability discovery contracts expose required responses")
        void provisioningAndCapabilityDiscoveryContractsAreComplete() throws Exception {
            JsonNode paths = loadSpec().path("paths");

            JsonNode create = findPath(paths, "/rag/api-keys").path("post");
            assertThat(create.isMissingNode()).isFalse();
            assertThat(findParameter(create, "Idempotency-Key")
                    .path("in").asText()).isEqualTo("header");
            assertThat(findParameter(create, "Idempotency-Key")
                    .path("required").asBoolean()).isFalse();
            for (String responseCode : java.util.List.of(
                    "200", "201", "400", "409", "503")) {
                assertThat(create.path("responses").has(responseCode))
                        .as("POST /api-keys must document %s", responseCode)
                        .isTrue();
            }

            JsonNode collectionCreate =
                    findPath(paths, "/rag/collections").path("post");
            assertThat(collectionCreate.isMissingNode()).isFalse();
            assertThat(findParameter(collectionCreate, "Idempotency-Key")
                    .path("in").asText()).isEqualTo("header");
            assertThat(findParameter(collectionCreate, "Idempotency-Key")
                    .path("required").asBoolean()).isFalse();
            for (String responseCode : java.util.List.of(
                    "200", "201", "400", "409", "503")) {
                assertThat(collectionCreate.path("responses").has(responseCode))
                        .as("POST /collections must document %s", responseCode)
                        .isTrue();
            }

            JsonNode capabilities = findPath(
                    paths, "/rag/integration-capabilities").path("get");
            assertThat(capabilities.isMissingNode()).isFalse();
            for (String responseCode : java.util.List.of("200", "401", "503")) {
                assertThat(capabilities.path("responses").has(responseCode))
                        .as("GET /integration-capabilities must document %s",
                                responseCode)
                        .isTrue();
            }

            JsonNode observability = findPath(
                    paths, "/rag/integration-observability").path("get");
            assertThat(observability.isMissingNode()).isFalse();
            for (String parameter : java.util.List.of(
                    "from",
                    "to",
                    "bucket",
                    "operation",
                    "collectionKey",
                    "principalId")) {
                assertThat(findParameter(observability, parameter).isMissingNode())
                        .as("GET /integration-observability parameter %s",
                                parameter)
                        .isFalse();
            }
            for (String responseCode : java.util.List.of(
                    "200", "400", "403", "503")) {
                assertThat(observability.path("responses").has(responseCode))
                        .as("GET /integration-observability must document %s",
                                responseCode)
                        .isTrue();
            }
            assertThat(observability.path("responses").path("200")
                    .path("content").path("application/json")
                    .path("schema").path("$ref").asText())
                    .endsWith("/IntegrationObservabilityResponse");
        }

        @Test
        @DisplayName("Sync Run item receipt contract exposes bounded read semantics")
        void syncRunItemReceiptContractIsComplete() throws Exception {
            JsonNode receipt = findPath(
                    loadSpec().path("paths"),
                    "/rag/document-sync-runs/{runId}/items").path("get");

            assertThat(receipt.isMissingNode()).isFalse();
            assertParameter(receipt, "collectionKey", true, 1, 128);
            assertParameter(receipt, "sourceNamespace", false, 0, 128);
            assertParameter(receipt, "cursor", false, 0, 1024);
            assertThat(findParameter(receipt, "status").isMissingNode()).isFalse();
            JsonNode limit = findParameter(receipt, "limit");
            assertThat(limit.isMissingNode()).isFalse();
            assertThat(limit.path("required").asBoolean()).isFalse();
            assertThat(limit.path("schema").path("minimum").asInt()).isEqualTo(1);
            assertThat(limit.path("schema").path("maximum").asInt()).isEqualTo(200);
            for (String responseCode : java.util.List.of(
                    "200", "400", "401", "403", "404", "503")) {
                assertThat(receipt.path("responses").has(responseCode))
                        .as("GET /document-sync-runs/{runId}/items must document %s",
                                responseCode)
                        .isTrue();
            }
        }

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

    private JsonNode loadSpec() throws Exception {
        MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode findPath(JsonNode paths, String suffix) {
        for (String path : iterable(paths.fieldNames())) {
            if (path.endsWith(suffix)) {
                return paths.path(path);
            }
        }
        return paths.path("__missing__");
    }

    private JsonNode findParameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (name.equals(parameter.path("name").asText())) {
                return parameter;
            }
        }
        return operation.path("__missing_parameter__");
    }

    private void assertParameter(
            JsonNode operation,
            String name,
            boolean required,
            int minLength,
            int maxLength) {
        JsonNode parameter = findParameter(operation, name);
        assertThat(parameter.isMissingNode())
                .as("Parameter '%s' must be documented", name)
                .isFalse();
        assertThat(parameter.path("required").asBoolean())
                .as("Parameter '%s' required flag", name)
                .isEqualTo(required);
        JsonNode schema = parameter.path("schema");
        assertThat(schema.path("minLength").asInt())
                .as("Parameter '%s' minLength", name)
                .isEqualTo(minLength);
        assertThat(schema.path("maxLength").asInt())
                .as("Parameter '%s' maxLength", name)
                .isEqualTo(maxLength);
    }

    /**
     * The consumer direction of the same contract: every route the WebUI asks
     * for has to be a route this application actually serves.
     *
     * <p>Batch 932. Everything else in this class asks whether the server keeps
     * its own promises — that a documented schema exists, that a required path is
     * present. Those checks pass just as happily when a whole page calls
     * endpoints that were never mounted, because the spec is generated from the
     * server and says nothing about who is calling it. So the A/B test page
     * could ask for {@code /api/v1/rag/experiments} for its whole life while the
     * controller sat at {@code /api/v1/rag/ab} — ten calls, every one a 404,
     * and not one assertion anywhere noticed.
     *
     * <p>The route table is read from the running spec rather than parsed out of
     * the Java sources on purpose. A regex over annotations produces a table that
     * looks authoritative and is quietly wrong in whatever shapes it does not
     * cover — the survey that found this missed fourteen good routes before
     * anyone looked — and a table that is wrong in the direction of "missing"
     * produces false alarms, which is how a gate like this dies.
     *
     * <p>It lives as a nested class rather than a second top-level test so it
     * joins the same cached Spring context: the property block and the 33
     * {@code @MockBean} declarations below are what Spring keys the context
     * cache on, and redeclaring them in a second class would buy a second
     * context boot for no gain.
     *
     * <p><b>Known limit, registered here rather than left for someone to
     * rediscover:</b> the scan reads {@code apiClient.<verb>('…')}. A call made
     * as {@code apiClient.request({ url: '…' })} would not be seen, and a module
     * that moved wholesale to that shape would drop out of the count without
     * turning anything red on its own — only the floor below would notice, and
     * only if enough other call sites remained. {@code request} appears nowhere
     * in {@code src/api} today, which is why it is not handled speculatively.
     */
    @Nested
    @DisplayName("WebUI Route Contract")
    class WebUiRouteContract {

        /** apiClient's baseURL, read from source so the test cannot drift from it. */
        private static final java.util.regex.Pattern BASE_URL_PATTERN =
                java.util.regex.Pattern.compile(
                        "const\\s+BASE_URL\\s*=\\s*['\"]([^'\"]+)['\"]");

        /**
         * A call site: {@code apiClient.<verb><Type>('/path')}. The generic
         * parameter is matched reluctantly so a nested type argument
         * ({@code get<Array<Foo>>}) does not terminate the match at the first
         * {@code >}.
         *
         * <p>All three string styles have to be listed, and the single-quoted
         * one is the one the api modules actually use. The first version of this
         * pattern offered only double quotes and backticks — the same mistake as
         * the missing {@code %} in {@code check-hardcoded-copy} two batches ago,
         * and it failed the same way: silently. It found 47 of the 105 call sites
         * and reported a clean sweep over the half it could see. The floor
         * assertion below is what turned that into a red test instead of a green
         * one, which is the whole reason it is here.
         */
        private static final java.util.regex.Pattern CALL_SITE =
                java.util.regex.Pattern.compile(
                        "apiClient\\.(get|post|put|delete|patch)(?:<.*?>)?\\s*\\(\\s*"
                                + "(\"[^\"]*\"|'[^']*'|`[^`]*`)");

        /** A path variable, on either side: {@code ${id}} from TS, {@code {id}} from the spec. */
        private static final java.util.regex.Pattern PATH_VARIABLE =
                java.util.regex.Pattern.compile("\\$\\{[^}]*\\}|\\{[^}]*\\}");

        /**
         * A floor on how many call sites the scan must find. Without it, a
         * scanner that silently stopped matching anything would report "every
         * route the WebUI asks for exists" over an empty set, and a survey that
         * finds zero has to be checked against a known positive before it is
         * believed — here, that check is built into the test.
         */
        private static final int MIN_CALL_SITES = 90;

        /**
         * Batch 933: the floor for the {@code fetch} scan, which is a different
         * and much smaller set.
         *
         * <p>Three call sites name a server route without going through
         * {@code apiClient} — the SSE chat stream, the multipart upload, and the
         * client-error report — and the first version of this test saw none of
         * them, because it only looked inside {@code src/api}. The census behind
         * this number is in {@link #scan_coversTheApiModules()}: this frontend
         * names routes in exactly two ways, and a third would have to be added
         * there too.
         */
        private static final int MIN_FETCH_SITES = 3;

        /**
         * A {@code fetch(} that names a route, in either of the shapes this
         * codebase uses.
         *
         * <p>Batch 933 added the {@code BASE_URL +} alternative, and it is not
         * an accommodation — it is the shape all three call sites moved to once
         * the base path stopped being spelled out four times. A scanner that only
         * accepted the full literal would have found **zero** fetch call sites
         * after that change and reported a clean sweep, which is the same silent
         * blindness as the missing quote style in the first version of this
         * pattern. A rule has to match the shape the code is actually written in,
         * not the one it was written in when the rule was made.
         *
         * <p>The negative lookbehind is load-bearing rather than defensive:
         * {@code void refetch()} appears throughout this codebase, and a pattern
         * that matched any {@code fetch(} would report every one of them as a
         * route call. A rule that cries wolf on the codebase's most common word
         * is a rule that gets switched off.
         */
        private static final java.util.regex.Pattern FETCH_SITE =
                java.util.regex.Pattern.compile(
                        "(?<![A-Za-z0-9_$])fetch\\s*\\(\\s*(?:BASE_URL\\s*\\+\\s*)?"
                                + "([\"'][^\"']*[\"'])");

        private record CallSite(String file, int line, String verb, String path) {
            @Override
            public String toString() {
                return verb + " " + path + "  (" + file + ":" + line + ")";
            }
        }

        @Test
        @DisplayName("Every route the WebUI calls is a route this application serves")
        void everyWebUiRoute_isServed() throws Exception {
            Path apiDir = locateWebUiApiDirectory();
            String baseUrl = readBaseUrl(apiDir);
            List<CallSite> calls = new ArrayList<>(scanCallSites(apiDir, baseUrl));
            calls.addAll(scanFetchCallSites(apiDir, baseUrl));

            assertThat(calls)
                    .as("the scan found suspiciously few call sites — a scanner that "
                            + "matches nothing would pass this test vacuously")
                    .hasSizeGreaterThanOrEqualTo(MIN_CALL_SITES + MIN_FETCH_SITES);

            Set<String> served = servedRoutes();

            List<String> unrouted = calls.stream()
                    .filter(call -> !served.contains(call.verb() + " " + shape(call.path())))
                    .map(CallSite::toString)
                    .toList();

            assertThat(unrouted)
                    .as("the WebUI calls these routes, but no handler serves them. "
                            + "%d of %d call sites matched. Mount path: %s",
                            calls.size() - unrouted.size(), calls.size(), baseUrl)
                    .isEmpty();
        }

        @Test
        @DisplayName("The fetch scan reaches the call sites that bypass the api client")
        void scan_coversFetchCallSites() throws Exception {
            Path apiDir = locateWebUiApiDirectory();
            List<CallSite> fetches = scanFetchCallSites(apiDir, readBaseUrl(apiDir));

            // Batch 933. These three are the routes this frontend names without
            // `apiClient`, and the first version of this contract did not see a
            // single one of them — it only looked inside `src/api`, so a whole
            // way of naming a server route was outside the check. A rule that
            // covers most of the ways is not the same rule.
            assertThat(fetches)
                    .as("the fetch scan must reach the call sites outside src/api")
                    .hasSizeGreaterThanOrEqualTo(MIN_FETCH_SITES);

            assertThat(fetches)
                    .as("all three fetch call sites are POSTs; if that changes, this "
                            + "assertion is what should notice before the route check "
                            + "silently compares the wrong verb")
                    .allMatch(call -> "POST".equals(call.verb()));

            assertThat(fetches)
                    .as("the lookbehind must keep `refetch()` out — it is the most "
                            + "common word next to `fetch` in this codebase, and "
                            + "matching it would report ordinary code as a route")
                    .noneMatch(call -> call.path().contains("refetch"));

            assertThat(fetches.stream().map(CallSite::file).distinct())
                    .as("the three sites live in three different files, so a scan that "
                            + "reached only one of them would still pass a count check")
                    .containsExactlyInAnyOrder(
                            "components/ErrorBoundary/ErrorBoundary.tsx",
                            "hooks/useFileUpload.ts",
                            "hooks/useSSE.ts");
        }

        @Test
        @DisplayName("The scan is pointed at the real API modules, not an empty directory")
        void scan_coversTheApiModules() throws Exception {
            Path apiDir = locateWebUiApiDirectory();
            List<CallSite> calls = scanCallSites(apiDir, readBaseUrl(apiDir));

            // Known positives, by module. If the scanner's shape ever stops
            // matching how the api modules are actually written, this fails with
            // a name instead of the suite going quietly hollow.
            assertThat(calls.stream().map(CallSite::file).distinct())
                    .as("the scan must reach the single-quoted and backtick call styles "
                            + "across every api module, not just some of them")
                    .contains("documents.ts", "collections.ts", "chat.ts", "files.ts",
                            "abtest.ts", "alerts.ts", "apikeys.ts");

            // One of each call style, so a regression in either regex is named.
            assertThat(calls)
                    .as("backtick call sites (template literals) must be found")
                    .anyMatch(call -> call.path().contains("{"));
            assertThat(calls)
                    .as("single-quoted call sites must be found")
                    .anyMatch(call -> call.path().endsWith("/health"));
        }

        // ==================== support ====================

        /**
         * Locates {@code spring-ai-rag-webui/src/api} by walking up from the
         * working directory, so it does not depend on Maven's module layout.
         * Fails loudly rather than returning null: a test that quietly scanned
         * nothing is the exact failure mode this class exists to prevent.
         */
        private Path locateWebUiApiDirectory() {
            Path dir = Path.of("").toAbsolutePath();
            while (dir != null) {
                Path candidate = dir.resolve("spring-ai-rag-webui/src/api");
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
                dir = dir.getParent();
            }
            throw new IllegalStateException(
                    "could not find spring-ai-rag-webui/src/api above "
                            + Path.of("").toAbsolutePath()
                            + " — the route contract test cannot verify anything without it");
        }

        private String readBaseUrl(Path apiDir) throws IOException {
            String client = Files.readString(apiDir.resolve("client.ts"));
            java.util.regex.Matcher matcher =
                    BASE_URL_PATTERN.matcher(client);
            assertThat(matcher.find())
                    .as("client.ts must declare BASE_URL; the scan composes paths from it "
                            + "rather than hardcoding a prefix that could drift")
                    .isTrue();
            return matcher.group(1);
        }

        private List<CallSite> scanCallSites(Path apiDir, String baseUrl) throws IOException {
            List<CallSite> calls = new ArrayList<>();
            try (Stream<Path> files = Files.list(apiDir)) {
                for (Path file : files
                        .filter(p -> p.getFileName().toString().endsWith(".ts"))
                        .filter(p -> !p.getFileName().toString().endsWith(".test.ts"))
                        .filter(p -> !p.getFileName().toString().endsWith(".d.ts"))
                        .sorted()
                        .toList()) {
                    String source = Files.readString(file);
                    java.util.regex.Matcher matcher = CALL_SITE.matcher(source);
                    while (matcher.find()) {
                        String literal = matcher.group(2);
                        String path = literal.startsWith("`")
                                ? literal.substring(1, literal.length() - 1)
                                : literal.substring(1, literal.length() - 1);
                        // Query strings are not part of a route: the spec keys on
                        // the path alone. Strip before comparing, or every call
                        // carrying params would look unrouted.
                        int query = path.indexOf('?');
                        if (query >= 0) {
                            path = path.substring(0, query);
                        }
                        if (!path.startsWith("/")) {
                            continue;
                        }
                        int line = (int) source.substring(0, matcher.start()).lines().count();
                        calls.add(new CallSite(
                                file.getFileName().toString(),
                                line,
                                matcher.group(1).toUpperCase(java.util.Locale.ROOT),
                                baseUrl + path));
                    }
                }
            }
            return calls;
        }

        /**
         * Every route named by a {@code fetch(} call, wherever it lives.
         *
         * <p>Batch 933. {@link #scanCallSites} only walks {@code src/api}, which
         * is where the {@code apiClient} calls are — so the three call sites that
         * reach the server some other way were outside the route contract
         * entirely. The error-boundary one is the one that matters most: its
         * {@code fetch} sits inside a {@code catch} that swallows everything on
         * purpose, so a route that stopped existing there would not fail a test,
         * it would quietly stop reporting.
         *
         * <p>The path is accepted either already carrying the base or as a suffix
         * next to it, because that is the form {@code check-single-api-base}
         * pushes call sites into. A literal base spelled out again would also be
         * accepted here — and reported by the other gate, which is the division
         * of labour: this one asks whether the route is served, that one asks
         * whether the base is stated once.
         */
        private List<CallSite> scanFetchCallSites(Path apiDir, String baseUrl) throws IOException {
            Path srcDir = apiDir.getParent();
            List<CallSite> calls = new ArrayList<>();
            try (Stream<Path> files = Files.walk(srcDir)) {
                for (Path file : files
                        .filter(p -> p.getFileName().toString().endsWith(".ts")
                                || p.getFileName().toString().endsWith(".tsx"))
                        .filter(p -> !p.getFileName().toString().endsWith(".test.ts"))
                        .filter(p -> !p.getFileName().toString().endsWith(".test.tsx"))
                        .filter(p -> !p.getFileName().toString().endsWith(".spec.ts"))
                        .filter(p -> !p.getFileName().toString().endsWith(".spec.tsx"))
                        .sorted()
                        .toList()) {
                    String source = Files.readString(file);
                    java.util.regex.Matcher matcher = FETCH_SITE.matcher(source);
                    while (matcher.find()) {
                        String literal = matcher.group(1);
                        String path = literal.substring(1, literal.length() - 1);
                        if (!path.startsWith("/")) {
                            continue;
                        }
                        String method = methodOf(source, matcher.end());
                        int line = (int) source.substring(0, matcher.start()).lines().count();
                        calls.add(new CallSite(
                                srcDir.relativize(file).toString().replace('\\', '/'),
                                line,
                                method,
                                path.startsWith(baseUrl) ? path : baseUrl + path));
                    }
                }
            }
            return calls;
        }

        /**
         * Reads {@code method: 'POST'} from the options object that follows the
         * URL. Defaults to {@code GET}, which is what {@code fetch} does when the
         * caller says nothing — so an unreadable call is compared as the request
         * it would actually make.
         */
        private static String methodOf(String source, int from) {
            int close = source.indexOf(')', from);
            if (close < 0) {
                return "GET";
            }
            java.util.regex.Matcher method = java.util.regex.Pattern
                    .compile("method\\s*:\\s*[\"']([A-Za-z]+)[\"']")
                    .matcher(source.substring(from, close));
            return method.find() ? method.group(1).toUpperCase(java.util.Locale.ROOT) : "GET";
        }

        /**
         * Every {@code VERB /path/pattern} this application serves, with both
         * sides reduced to the same shape so that {@code /a/${id}/b} and
         * {@code /a/{id}/b} compare equal.
         */
        private Set<String> servedRoutes() throws Exception {
            MvcResult result = mockMvc.perform(get(OPENAPI_SPEC_PATH))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode paths = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("paths");

            Set<String> served = new HashSet<>();
            Iterator<String> pathIt = paths.fieldNames();
            while (pathIt.hasNext()) {
                String path = pathIt.next();
                JsonNode item = paths.path(path);
                Iterator<String> verbIt = item.fieldNames();
                while (verbIt.hasNext()) {
                    String field = verbIt.next();
                    if (HTTP_METHODS.contains(field)) {
                        served.add(field.toUpperCase(java.util.Locale.ROOT) + " " + shape(path));
                    }
                }
            }
            return served;
        }

        private static final java.util.Set<String> HTTP_METHODS = java.util.Set.of(
                "get", "post", "put", "delete", "patch", "head", "options");

        /** Both sides to one comparable form: path variables become {@code *}. */
        private static String shape(String path) {
            String shaped = PATH_VARIABLE.matcher(path).replaceAll("*");
            // A trailing slash is not a different route.
            return shaped.endsWith("/") && shaped.length() > 1
                    ? shaped.substring(0, shaped.length() - 1)
                    : shaped;
        }
    }
}
