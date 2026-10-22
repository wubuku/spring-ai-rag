package com.springairag.core.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import com.springairag.core.controller.GlobalExceptionHandler;
import com.springairag.core.controller.LongRunningOperationController;
import com.springairag.core.controller.RagCollectionController;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.security.ProvisioningOwnerResolver;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.CollectionProvisioningService;
import com.springairag.core.service.CollectionPurgeAuthorization;
import com.springairag.core.service.CollectionPurgeOperationWorker;
import com.springairag.core.service.CollectionPurgeService;
import com.springairag.core.service.DocumentMutationService;
import com.springairag.core.service.LongRunningOperationService;
import com.springairag.core.service.RagCollectionService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.ACCEPTED;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.GONE;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** Async purge external HTTP lifecycle against an isolated PostgreSQL database. */
@EnabledIfSystemProperty(named = "collection-purge-http.it.enabled", matches = "true")
@SpringBootTest(
        classes = CollectionPurgeHttpPostgresIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("postgresql")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.ai.openai.chat.enabled=false",
        "spring.ai.openai.embedding.enabled=false",
        "rag.collection-purge.enabled=true",
        "rag.collection-purge.allow-auth-disabled=false",
        "rag.long-running-operations.poll-interval-ms=3600000"
})
class CollectionPurgeHttpPostgresIntegrationTest {

    private static final String ROOT_KEY = "purge-http-integration-root-key-2026-10";
    private String collectionKey;
    private String cancellationCollectionKey;
    private String capacityCollectionKey;

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(System.getProperty(
                    "testcontainers.pg.image",
                    System.getenv().getOrDefault(
                            "TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16")))
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("purge_http")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        String externalUrl = System.getProperty("collection-purge.it.jdbc-url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            if (!"YES".equals(System.getProperty(
                    "collection-purge.it.clean-confirm"))) {
                throw new IllegalStateException(
                        "Set collection-purge.it.clean-confirm=YES only for a disposable database");
            }
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username", () -> System.getProperty(
                    "collection-purge.it.username", "postgres"));
            registry.add("spring.datasource.password", () -> System.getProperty(
                    "collection-purge.it.password", "postgres"));
            return;
        }
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopOwnedDatabase() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RagCollectionRepository collections;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CollectionPurgeOperationWorker worker;

    @BeforeEach
    void createDisposableCollection() {
        String testRun = UUID.randomUUID().toString();
        collectionKey = "async-http-purge-lifecycle-" + testRun;
        cancellationCollectionKey = "async-http-purge-cancel-" + testRun;
        capacityCollectionKey = "async-http-purge-capacity-" + testRun;
        RagCollection collection = new RagCollection();
        collection.setCollectionKey(collectionKey);
        collection.setName("Async purge HTTP lifecycle");
        collection.setEnabled(true);
        collections.saveAndFlush(collection);
    }

    @Test
    void asyncPurgeHttpContractAndWorkerLifecycleAreDurable() throws Exception {
        String previewPath = "/api/v1/rag/collections/by-key/purge/async/preview"
                + "?collectionKey=" + collectionKey;
        ResponseEntity<String> anonymousPreview = request(
                POST, previewPath, null, null, null);
        assertEquals(UNAUTHORIZED, anonymousPreview.getStatusCode());

        ResponseEntity<String> previewResponse = request(
                POST, previewPath, ROOT_KEY, null, null);
        assertEquals(OK, previewResponse.getStatusCode());
        JsonNode preview = objectMapper.readTree(previewResponse.getBody());
        assertEquals("PREVIEWED", preview.path("status").asText());
        assertEquals(0, preview.path("documentCount").asInt());
        assertTrue(preview.path("confirmationToken").isTextual());
        assertTrue(preview.path("fingerprint").isTextual());

        var apply = objectMapper.createObjectNode()
                .put("collectionKey", collectionKey)
                .put("previewId", preview.path("previewId").asText())
                .put("confirmationToken", preview.path("confirmationToken").asText())
                .put("fingerprint", preview.path("fingerprint").asText())
                .put("expectedCollectionVersion", preview.path("collectionVersion").asLong())
                .put("expectedChatCommitFenceVersion",
                        preview.path("chatCommitFenceVersion").asLong());
        String applyJson = objectMapper.writeValueAsString(apply);

        ResponseEntity<String> syncApply = request(
                POST, "/api/v1/rag/collections/by-key/purge", ROOT_KEY, null, applyJson);
        assertEquals(CONFLICT, syncApply.getStatusCode());
        assertEquals("COLLECTION_PURGE_ASYNC_PREVIEW_REQUIRES_ASYNC_APPLY",
                objectMapper.readTree(syncApply.getBody()).path("error").asText());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_collection WHERE collection_key = ? AND purged_at IS NOT NULL",
                Integer.class, collectionKey));

        String asyncPath = "/api/v1/rag/collections/by-key/purge/async";
        ResponseEntity<String> missingIdempotencyKey = request(
                POST, asyncPath, ROOT_KEY, null, applyJson);
        assertEquals(BAD_REQUEST, missingIdempotencyKey.getStatusCode());
        assertEquals("MISSING_HEADER",
                objectMapper.readTree(missingIdempotencyKey.getBody()).path("error").asText());

        ResponseEntity<String> accepted = request(
                POST, asyncPath, ROOT_KEY, "purge-http-idempotency", applyJson);
        assertEquals(ACCEPTED, accepted.getStatusCode());
        assertNotNull(accepted.getHeaders().getLocation());
        assertTrue(Integer.parseInt(accepted.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)) > 0);
        JsonNode receipt = objectMapper.readTree(accepted.getBody());
        String operationId = receipt.path("operationId").asText();
        assertFalse(operationId.isBlank());

        ResponseEntity<String> replay = request(
                POST, asyncPath, ROOT_KEY, "purge-http-idempotency", applyJson);
        assertEquals(ACCEPTED, replay.getStatusCode());
        assertEquals(operationId,
                objectMapper.readTree(replay.getBody()).path("operationId").asText());

        apply.put("expectedCollectionVersion", preview.path("collectionVersion").asLong() + 1);
        ResponseEntity<String> keyConflict = request(
                POST, asyncPath, ROOT_KEY, "purge-http-idempotency",
                objectMapper.writeValueAsString(apply));
        assertEquals(CONFLICT, keyConflict.getStatusCode());
        assertEquals("IDEMPOTENCY_KEY_REUSED",
                objectMapper.readTree(keyConflict.getBody()).path("error").asText());

        String statusPath = receipt.path("statusUrl").asText();
        String resultPath = receipt.path("resultUrl").asText();
        ResponseEntity<String> unknownOperation = request(
                GET, "/api/v1/rag/operations/00000000-0000-0000-0000-000000000000",
                ROOT_KEY, null, null);
        assertEquals(NOT_FOUND, unknownOperation.getStatusCode());

        String status = "";
        for (int attempt = 0; attempt < 40; attempt++) {
            worker.processOneOperation();
            ResponseEntity<String> statusResponse = request(GET, statusPath, ROOT_KEY, null, null);
            assertEquals(OK, statusResponse.getStatusCode());
            status = objectMapper.readTree(statusResponse.getBody()).path("status").asText();
            if ("SUCCEEDED".equals(status)) {
                break;
            }
            Thread.sleep(25);
        }
        assertEquals("SUCCEEDED", status);
        ResponseEntity<String> result = request(GET, resultPath, ROOT_KEY, null, null);
        assertEquals(OK, result.getStatusCode());
        JsonNode resultJson = objectMapper.readTree(result.getBody());
        assertEquals("RETIRED", resultJson.path("status").asText());
        assertNotNull(resultJson.get("purgedAt"));
        jdbc.update("""
                UPDATE rag_long_running_operations
                SET result_expires_at = clock_timestamp() - INTERVAL '1 second'
                WHERE id = ?
                """, UUID.fromString(operationId));
        ResponseEntity<String> expiredResult = request(GET, resultPath, ROOT_KEY, null, null);
        assertEquals(GONE, expiredResult.getStatusCode());
        assertEquals("LONG_RUNNING_OPERATION_RESULT_EXPIRED",
                objectMapper.readTree(expiredResult.getBody()).path("error").asText());

        RagCollection cancellable = new RagCollection();
        cancellable.setCollectionKey(cancellationCollectionKey);
        cancellable.setName("Async purge HTTP cancellation");
        cancellable.setEnabled(true);
        collections.saveAndFlush(cancellable);
        ResponseEntity<String> cancellationPreview = request(
                POST, "/api/v1/rag/collections/by-key/purge/async/preview"
                        + "?collectionKey=" + cancellationCollectionKey,
                ROOT_KEY, null, null);
        JsonNode cancellationPlan = objectMapper.readTree(cancellationPreview.getBody());
        var cancellationApply = objectMapper.createObjectNode()
                .put("collectionKey", cancellationCollectionKey)
                .put("previewId", cancellationPlan.path("previewId").asText())
                .put("confirmationToken", cancellationPlan.path("confirmationToken").asText())
                .put("fingerprint", cancellationPlan.path("fingerprint").asText())
                .put("expectedCollectionVersion", cancellationPlan.path("collectionVersion").asLong())
                .put("expectedChatCommitFenceVersion",
                        cancellationPlan.path("chatCommitFenceVersion").asLong());
        ResponseEntity<String> cancellationAccepted = request(
                POST, asyncPath, ROOT_KEY, "purge-http-cancel-key",
                objectMapper.writeValueAsString(cancellationApply));
        String cancellationId = objectMapper.readTree(cancellationAccepted.getBody())
                .path("operationId").asText();
        ResponseEntity<String> cancellation = request(
                POST, "/api/v1/rag/operations/" + cancellationId + "/cancel",
                ROOT_KEY, null, null);
        assertEquals(ACCEPTED, cancellation.getStatusCode());
        assertEquals("CANCELLED",
                objectMapper.readTree(cancellation.getBody()).path("status").asText());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_collection WHERE collection_key = ? AND purged_at IS NOT NULL",
                Integer.class, cancellationCollectionKey));

        RagCollection capacityLimited = new RagCollection();
        capacityLimited.setCollectionKey(capacityCollectionKey);
        capacityLimited.setName("Async purge HTTP capacity");
        capacityLimited.setEnabled(true);
        collections.saveAndFlush(capacityLimited);
        ResponseEntity<String> capacityPreviewResponse = request(
                POST, "/api/v1/rag/collections/by-key/purge/async/preview"
                        + "?collectionKey=" + capacityCollectionKey,
                ROOT_KEY, null, null);
        JsonNode capacityPreview = objectMapper.readTree(capacityPreviewResponse.getBody());
        var capacityApply = objectMapper.createObjectNode()
                .put("collectionKey", capacityCollectionKey)
                .put("previewId", capacityPreview.path("previewId").asText())
                .put("confirmationToken", capacityPreview.path("confirmationToken").asText())
                .put("fingerprint", capacityPreview.path("fingerprint").asText())
                .put("expectedCollectionVersion", capacityPreview.path("collectionVersion").asLong())
                .put("expectedChatCommitFenceVersion",
                        capacityPreview.path("chatCommitFenceVersion").asLong());
        jdbc.update("""
                UPDATE rag_long_running_operation_global_capacity
                SET active_count = 500 WHERE singleton_id = 1
                """);
        try {
            ResponseEntity<String> capacityRejected = request(
                    POST, asyncPath, ROOT_KEY, "purge-http-capacity-key",
                    objectMapper.writeValueAsString(capacityApply));
            assertEquals(TOO_MANY_REQUESTS, capacityRejected.getStatusCode());
            assertTrue(Integer.parseInt(capacityRejected.getHeaders()
                    .getFirst(HttpHeaders.RETRY_AFTER)) > 0);
            assertEquals("LONG_RUNNING_OPERATION_CAPACITY_EXCEEDED",
                    objectMapper.readTree(capacityRejected.getBody()).path("error").asText());
            assertEquals(0, jdbc.queryForObject("""
                    SELECT active_count FROM rag_long_running_operation_owner_capacity
                    WHERE owner_principal_id = 'root:environment-root'
                    """, Integer.class));
            assertEquals(0, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM rag_collection_operation_slot
                    WHERE collection_id = ?
                    """, Integer.class, capacityLimited.getId()));
        } finally {
            jdbc.update("""
                    UPDATE rag_long_running_operation_global_capacity
                    SET active_count = 0 WHERE singleton_id = 1
                    """);
        }
    }

    private ResponseEntity<String> request(
            org.springframework.http.HttpMethod method,
            String path,
            String apiKey,
            String idempotencyKey,
            String body) {
        var headers = new HttpHeaders();
        if (apiKey != null) {
            headers.set("X-API-Key", apiKey);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return http.exchange(URI.create("http://127.0.0.1:" + port + path), method,
                new org.springframework.http.HttpEntity<>(body, headers), String.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = RagCollection.class)
    @EnableJpaRepositories(basePackageClasses = RagCollectionRepository.class)
    @Import({
            ApiVersionConfig.class,
            RagCollectionController.class,
            LongRunningOperationController.class,
            GlobalExceptionHandler.class,
            CollectionPurgeAuthorization.class,
            CollectionPurgeService.class,
            LongRunningOperationService.class,
            CollectionPurgeOperationWorker.class,
            CollectionIdentityResolver.class,
            ProvisioningOwnerResolver.class
    })
    @org.springframework.boot.context.properties.EnableConfigurationProperties(RagProperties.class)
    static class TestApplication {

        @Bean
        FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration() {
            var registration = new FilterRegistrationBean<>(new ApiKeyAuthFilter(
                    "", true, null, new EnvironmentRootCredentialResolver(ROOT_KEY)));
            registration.addUrlPatterns("/api/*", "/v1/*");
            registration.setOrder(-10);
            return registration;
        }

        @Bean
        @Primary
        RagCollectionService collectionService() {
            return mock(RagCollectionService.class);
        }

        @Bean
        @Primary
        DocumentMutationService documentMutationService() {
            return mock(DocumentMutationService.class);
        }

        @Bean
        @Primary
        CollectionProvisioningService collectionProvisioningService() {
            return mock(CollectionProvisioningService.class);
        }
    }
}
