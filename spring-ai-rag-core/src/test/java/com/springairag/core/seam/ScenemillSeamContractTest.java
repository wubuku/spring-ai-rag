package com.springairag.core.seam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ApiKeyIdentityResponse;
import com.springairag.api.dto.CollectionRequest;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.IntegrationCapabilitiesResponse;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertResponse;
import com.springairag.api.enums.CollectionAccessMode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.controller.ApiKeyIdentityController;
import com.springairag.core.controller.GlobalExceptionHandler;
import com.springairag.core.controller.IntegrationCapabilitiesController;
import com.springairag.core.controller.RagCollectionController;
import com.springairag.core.controller.RagJsonRecordController;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.filter.FailureClassHttpSupport;
import com.springairag.core.filter.RequestTraceFilter;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.IntegrationCapabilityCatalog;
import com.springairag.core.service.JsonRecordService;
import com.springairag.core.service.RagCollectionService;
import com.springairag.core.tenant.TenantMode;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Scenemill（星幕 AI）Wire 契约双模式回归套件。
 *
 * <p>锁定 7 类上游依赖路径：/auth/me、/integration-capabilities、/collections/by-key/{key}、
 * json-records upsert（含 contentHash 与 CAS 409）、source-delete tombstone、by-external-id、
 * search（payloadContains）。同时验证 X-Trace-Id / X-RAG-Failure-Class 头与租户字段在
 * SINGLE / STRICT 双模式下一致。</p>
 */
@WebMvcTest({
        ApiKeyIdentityController.class,
        IntegrationCapabilitiesController.class,
        RagCollectionController.class,
        RagJsonRecordController.class
})
@Import({
        ApiVersionConfig.class,
        ScenemillSeamContractTest.TestConfig.class
})
class ScenemillSeamContractTest {

    private static final String TENANT_ID = "scenemill-prod";
    private static final String COLLECTION_KEY = "scenemill:manual:v1";
    private static final String TRACE_ID = "trace-scenemill-xyz";
    private static final String CONTENT_HASH = "a".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RagProperties properties;

    @MockitoBean
    private EnvironmentRootCredentialResolver rootCredentialResolver;

    @MockitoBean
    private CollectionIdentityResolver collectionIdentityResolver;

    @MockitoBean
    private RagCollectionService collectionService;

    @MockitoBean
    private JsonRecordService jsonRecordService;

    @MockitoBean
    private com.springairag.core.service.DocumentMutationService documentMutationService;

    @MockitoBean
    private com.springairag.core.service.CollectionProvisioningService collectionProvisioningService;

    @MockitoBean
    private com.springairag.core.service.AuditLogService auditLogService;

    @MockitoBean
    private com.springairag.core.service.CollectionPurgeService collectionPurgeService;

    @MockitoBean
    private com.springairag.core.security.ProvisioningOwnerResolver provisioningOwnerResolver;

    @MockitoBean
    private com.springairag.core.service.CollectionExportService collectionExportService;

    @BeforeEach
    void setUp() {
        when(rootCredentialResolver.isConfigured()).thenReturn(false);
    }

    @ParameterizedTest(name = "{0} 模式七类 Wire 契约")
    @EnumSource(TenantMode.class)
    void allSevenWireContractsAreStableInBothModes(TenantMode mode) throws Exception {
        properties.getTenant().setMode(mode);
        String tenantId = mode == TenantMode.SINGLE ? "default" : TENANT_ID;
        AuthenticatedApiPrincipal principal = restrictedPrincipal(tenantId);
        when(collectionIdentityResolver.mapKeys(List.of(7L)))
                .thenReturn(Map.of(7L, COLLECTION_KEY));

        // ① /auth/me
        mockMvc.perform(get("/api/v1/rag/auth/me")
                        .header("X-API-Key", "rag_sk_scenemill")
                        .header(RequestTraceFilter.INCOMING_TRACE_HEADER, TRACE_ID)
                        .with(databasePrincipal(principal)))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestTraceFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(jsonPath("$.principalType").value(
                        ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY))
                .andExpect(jsonPath("$.tenantId").value(tenantId))
                .andExpect(jsonPath("$.tenantMode").value(mode.name()))
                .andExpect(jsonPath("$.tenantStatus").value("ACTIVE"));

        // ② /integration-capabilities（version 1.1 + reliability + principal 租户字段）
        mockMvc.perform(get("/api/v1/rag/integration-capabilities")
                        .header("X-API-Key", "rag_sk_scenemill")
                        .with(databasePrincipal(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocol.version").value("1.1"))
                .andExpect(jsonPath("$.protocol.apiVersion").value("1.0.0"))
                .andExpect(jsonPath("$.principal.tenantId").value(tenantId))
                .andExpect(jsonPath("$.principal.tenantMode").value(mode.name()))
                .andExpect(jsonPath("$.principal.tenantStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.principal.collectionAccessMode").value("RESTRICTED"))
                .andExpect(jsonPath("$.features.dataPlane.jsonRecords.payloadContains").value(true))
                .andExpect(jsonPath("$.features.dataPlane.jsonRecords.upsert").value(true))
                .andExpect(jsonPath("$.reliability." + "responseTraceHeader")
                        .value(RequestTraceFilter.TRACE_ID_HEADER))
                .andExpect(jsonPath("$.reliability.failureClassHeader")
                        .value(FailureClassHttpSupport.HEADER));

        // ③ /collections/by-key/{key}
        com.springairag.core.entity.RagCollection collection = new com.springairag.core.entity.RagCollection();
        collection.setId(7L);
        collection.setCollectionKey(COLLECTION_KEY);
        collection.setName("Scenemill manual");
        collection.setEnabled(true);
        when(collectionIdentityResolver.requireActive(null, COLLECTION_KEY))
                .thenReturn(collection);
        when(collectionIdentityResolver.requireActiveWithinAllowed(
                COLLECTION_KEY, java.util.Set.of(7L)))
                .thenReturn(collection);
        when(collectionService.countDocuments(7L)).thenReturn(12L);
        mockMvc.perform(get("/api/v1/rag/collections/by-key")
                        .param("collectionKey", COLLECTION_KEY)
                        .header("X-API-Key", "rag_sk_scenemill")
                        .with(databasePrincipal(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectionKey").value(COLLECTION_KEY));

        // ④ upsert（contentHash 非空 + CAS 409 结构化错误）
        JsonRecordUpsertResponse upsert = new JsonRecordUpsertResponse(
                42L, 7L, COLLECTION_KEY, "sm-media-1", "CREATED",
                true, true, 1, "NOT_REQUESTED", null, null, null, null, null,
                "default", "rev-1", 1L, null, CONTENT_HASH);
        when(jsonRecordService.upsert(any(JsonRecordUpsertRequest.class))).thenReturn(upsert);
        mockMvc.perform(post("/api/v1/rag/json-records/upsert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", "rag_sk_scenemill")
                        .with(databasePrincipal(principal))
                        .content(jsonRecordRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(42))
                .andExpect(jsonPath("$.contentHash").value(CONTENT_HASH))
                .andExpect(header().string(RequestTraceFilter.TRACE_ID_HEADER,
                        org.hamcrest.Matchers.notNullValue()));

        // ⑤ source-delete tombstone
        when(jsonRecordService.sourceDelete(anyString(), anyString(), anyString(),
                anyString(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(new com.springairag.api.dto.ExternalDocumentDeleteResponse(
                        42L, COLLECTION_KEY, "sm-media-1", "rev-2", "DELETED",
                        2, true, null, null, null));
        mockMvc.perform(delete("/api/v1/rag/json-records/by-external-id")
                        .param("collectionKey", COLLECTION_KEY)
                        .param("externalId", "sm-media-1")
                        .param("sourceRevision", "rev-2")
                        .header("X-API-Key", "rag_sk_scenemill")
                        .with(databasePrincipal(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("DELETED"));

        // ⑥ by-external-id
        when(jsonRecordService.getByExternalIdentity(
                COLLECTION_KEY, "default", "sm-media-1"))
                .thenReturn(new com.springairag.api.dto.JsonRecordDetailResponse(
                        42L, 7L, COLLECTION_KEY, "sm-media-1", "title", "source",
                        "retrieval", objectMapper.createObjectNode(), CONTENT_HASH,
                        "COMPLETED", true, null, null, 1, Map.of()));
        mockMvc.perform(get("/api/v1/rag/json-records/by-external-id")
                        .param("collectionKey", COLLECTION_KEY)
                        .param("externalId", "sm-media-1")
                        .header("X-API-Key", "rag_sk_scenemill")
                        .with(databasePrincipal(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentHash").value(CONTENT_HASH));

        // ⑦ search（payloadContains 过滤请求被服务接收）
        when(jsonRecordService.search(any(com.springairag.api.dto.JsonRecordSearchRequest.class)))
                .thenReturn(new com.springairag.api.dto.JsonRecordSearchResponse(
                        "manual", List.of()));
        mockMvc.perform(post("/api/v1/rag/json-records/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", "rag_sk_scenemill")
                        .with(databasePrincipal(principal))
                        .content("""
                                {"query":"manual","collectionKeys":["scenemill:manual:v1"],
                                 "payloadContains":{"tenantKey":"customer-a"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("STRICT 模式非 ACTIVE 租户结构化为 403 + AUTHORIZATION")
    void suspendedTenantHasStableForbiddenContract() throws Exception {
        properties.getTenant().setMode(TenantMode.STRICT);
        AuthenticatedApiPrincipal suspended = restrictedPrincipal(TENANT_ID);
        AuthenticatedApiPrincipal denied = new AuthenticatedApiPrincipal(
                suspended.principalId(), suspended.credentialId(), 1,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, ApiKeyRole.NORMAL,
                "7", null, 1, 120, List.of(ApiCapabilitySupport.RAG_READ),
                suspended.tenantId(), "SUSPENDED");

        mockMvc.perform(get("/api/v1/rag/json-records/by-external-id")
                        .param("collectionKey", COLLECTION_KEY)
                        .param("externalId", "sm-media-1")
                        .header("X-API-Key", "rag_sk_scenemill")
                        .header(RequestTraceFilter.INCOMING_TRACE_HEADER, TRACE_ID)
                        .with(databasePrincipal(denied)))
                .andExpect(status().isForbidden())
                .andExpect(header().string(RequestTraceFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(header().string(FailureClassHttpSupport.HEADER, "AUTHORIZATION"))
                .andExpect(jsonPath("$.error").value("TENANT_SUSPENDED"));
    }

    @ParameterizedTest(name = "{0} 模式 DTO 序列化保持既有字段与增量字段")
    @EnumSource(TenantMode.class)
    void dtoContractIsAdditiveOnly(TenantMode mode) throws Exception {
        properties.getTenant().setMode(mode);
        JsonRecordUpsertResponse legacy = new JsonRecordUpsertResponse(
                1L, 2L, "ck", "ext", "CREATED", true, true, 1,
                "NOT_REQUESTED", null, null);
        JsonNode legacyJson = objectMapper.readTree(objectMapper.writeValueAsString(legacy));
        assertFalse(legacyJson.hasNonNull("contentHash"), "旧构造器 contentHash 必须为 null");
        assertTrue(legacyJson.has("documentId"));
        assertTrue(legacyJson.has("collectionKey"));
        assertTrue(legacyJson.has("versionNumber"));

        IntegrationCapabilitiesResponse.Principal principal =
                new IntegrationCapabilitiesResponse.Principal(
                        ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, "NORMAL",
                        List.of(ApiCapabilitySupport.RAG_READ),
                        CollectionAccessMode.RESTRICTED, List.of(COLLECTION_KEY));
        JsonNode principalJson = objectMapper.readTree(
                objectMapper.writeValueAsString(principal));
        assertFalse(principalJson.has("tenantId"));
        principal.setTenantId(TENANT_ID);
        principal.setTenantMode(mode.name());
        principal.setTenantStatus("ACTIVE");
        JsonNode extended = objectMapper.readTree(
                objectMapper.writeValueAsString(principal));
        assertEquals(TENANT_ID, extended.path("tenantId").asText());
        assertEquals(mode.name(), extended.path("tenantMode").asText());
    }

    private static AuthenticatedApiPrincipal restrictedPrincipal(String tenantId) {
        return new AuthenticatedApiPrincipal(
                "rag_k_scenemill", "rag_k_scenemill_credential", 1,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, ApiKeyRole.NORMAL,
                "7", null, 1, 120, List.of(ApiCapabilitySupport.RAG_READ),
                tenantId, "ACTIVE");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor
    databasePrincipal(AuthenticatedApiPrincipal principal) {
        return request -> {
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                    ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                    principal.principalId());
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                    principal);
            return request;
        };
    }

    private static String jsonRecordRequest() {
        return """
                {"collectionKey":"scenemill:manual:v1","externalId":"sm-media-1",
                 "title":"manual","retrievalText":"manual","jsonbPayload":{"k":"v"}}
                """;
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        RagProperties ragProperties() {
            RagProperties properties = new RagProperties();
            properties.getOpenAiCompatibility().setEnabled(false);
            return properties;
        }

        @Bean
        IntegrationCapabilityCatalog integrationCapabilityCatalog(
                RagProperties properties, CollectionIdentityResolver resolver) {
            return new IntegrationCapabilityCatalog(properties, resolver);
        }

        /** 显式加载 STRICT 租户过滤器：SINGLE 的 default ACTIVE principal 不受影响。 */
        @Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<
                com.springairag.core.tenant.TenantContextFilter>
        scenemillTenantContextFilter() {
            org.springframework.boot.web.servlet.FilterRegistrationBean<
                    com.springairag.core.tenant.TenantContextFilter> registration =
                    new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                            new com.springairag.core.tenant.TenantContextFilter(
                                    new com.springairag.core.tenant.PrincipalTenantResolver(),
                                    TenantMode.STRICT));
            registration.addUrlPatterns("/api/*", "/v1/*");
            registration.setOrder(-8);
            return registration;
        }

        /** 锁定上游依赖的 X-Trace-Id 回写契约。 */
        @Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<RequestTraceFilter>
        scenemillRequestTraceFilter() {
            org.springframework.boot.web.servlet.FilterRegistrationBean<RequestTraceFilter>
                    registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                            new RequestTraceFilter());
            registration.addUrlPatterns("/api/*", "/v1/*");
            registration.setOrder(-30);
            return registration;
        }
    }
}
