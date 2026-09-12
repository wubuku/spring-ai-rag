package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.enums.DocumentDeduplicationScope;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.security.ApiAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 命名空间守卫与去重作用域过滤（Batch 330）：normalizeNamespace
 * 的长度/可见 ASCII/开关守卫；findDuplicate 的 COLLECTION 作用域
 * 闭合与受限密钥不可见文档过滤。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentMutationNamespaceDedupGuardsTest {

    @Mock RagDocumentRepository documentRepository;
    @Mock RagEmbeddingRepository embeddingRepository;
    @Mock CollectionIdentityResolver collectionIdentityResolver;
    @Mock DocumentVersionService versionService;
    @Mock EmbeddingDispatchService dispatchService;
    @Mock DocumentEmbedService documentEmbedService;
    @Mock DocumentLifecycleService lifecycleService;
    @Mock JdbcTemplate jdbcTemplate;

    private RagProperties properties;

    @BeforeEach
    void setUp() {
        installCaller(null);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        properties = new RagProperties();
        // 公共桩：创建/去重链路通用。
        when(collectionIdentityResolver.beginActiveWrites(any()))
                .thenReturn(List.of());
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
        when(versionService.getLatestVersion(anyLong()))
                .thenReturn(java.util.Optional.of(version(7)));
        when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> version(7));
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument document = invocation.getArgument(0);
                    document.setId(66L);
                    document.setDocumentRevision(1L);
                    return document;
                });
        when(documentRepository.findById(anyLong()))
                .thenAnswer(invocation ->
                        java.util.Optional.of(
                                savedDocument(invocation.getArgument(0))));
        when(dispatchService.markNotRequestedInCurrentTransaction(
                any(RagDocument.class)))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.SKIPPED, "NOT_REQUESTED", "bge-m3",
                        null, null, null));
    }

    @AfterEach
    void resetContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private DocumentMutationService service() {
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        return new DocumentMutationService(
                documentRepository,
                embeddingRepository,
                collectionIdentityResolver,
                versionService,
                dispatchService,
                documentEmbedService,
                lifecycleService,
                jdbcTemplate,
                new ObjectMapper(),
                properties,
                transactionManager);
    }

    // ── normalizeNamespace 守卫 ─────────────────────────────────────

    @Test
    void blankNamespaceFallsBackToDefaultWithoutError() {
        // 命名空间归一后继续执行到 externalId 校验才失败。
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service().upsertJsonRecord(
                        jsonRequest("  "), 10L, "kb", null, null));
        assertTrue(error.getMessage().contains("externalId"));
    }

    @Test
    void overLongNamespaceRejected() {
        JsonRecordUpsertRequest request = jsonRequest("n".repeat(129));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service().upsertJsonRecord(
                        request, 10L, "kb", null, null));
        assertTrue(error.getMessage().contains("must not exceed 128"));
    }

    @Test
    void nonVisibleAsciiNamespaceRejected() {
        JsonRecordUpsertRequest request = jsonRequest("ns\u0001x");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service().upsertJsonRecord(
                        request, 10L, "kb", null, null));
        assertTrue(error.getMessage().contains("visible ASCII only"));
    }

    @Test
    void nonDefaultNamespaceHonorsConfigurationSwitch() {
        // 默认允许非默认命名空间。
        assertThrows(IllegalArgumentException.class,
                () -> service().upsertJsonRecord(
                        jsonRequest("cms"), 10L, "kb", null, null),
                "externalId 缺失应在命名空间检查之后");

        // 关闭开关后非默认命名空间被拒绝。
        properties.getDocumentLifecycle().setAllowNonDefaultNamespace(false);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service().upsertJsonRecord(
                        jsonRequest("cms"), 10L, "kb", null, null));
        assertTrue(error.getMessage().contains("Non-default"));
    }

    // ── findDuplicate 作用域与可见性过滤 ────────────────────────────

    private DocumentRequest request(DocumentDeduplicationScope scope) {
        DocumentRequest request = new DocumentRequest();
        request.setTitle("New Document");
        request.setContent("Fresh searchable body");
        request.setDeduplicationScope(scope);
        return request;
    }

    private RagDocument duplicate(long id, long collectionId) {
        RagDocument document = savedDocument(id);
        document.setCollectionId(collectionId);
        return document;
    }

    @Test
    void collectionScopeDedupMatchesOnlySameCollection() {
        when(documentRepository.findByContentHash(anyString()))
                .thenReturn(List.of(
                        duplicate(99L, 99L),
                        duplicate(10L, 10L)));

        var created = service().createLocal(
                request(DocumentDeduplicationScope.COLLECTION),
                10L, EmbeddingPolicy.SKIP, false, "LOCAL_CREATE",
                null, null, null, null);

        // 不同集合的重复被过滤，命中同集合文档。
        assertEquals("DUPLICATE", created.mutation().action());
        assertEquals(10L, created.document().getId());
    }

    @Test
    void collectionScopeDedupIgnoresOtherCollections() {
        when(documentRepository.findByContentHash(anyString()))
                .thenReturn(List.of(duplicate(99L, 99L)));

        var created = service().createLocal(
                request(DocumentDeduplicationScope.COLLECTION),
                10L, EmbeddingPolicy.SKIP, false, "LOCAL_CREATE",
                null, null, null, null);

        assertEquals("CREATED", created.mutation().action());
    }

    @Test
    void restrictedCallerCannotSeeInaccessibleDuplicates() {
        installCaller("10");
        when(documentRepository.findByContentHash(anyString()))
                .thenReturn(List.of(duplicate(99L, 99L)));

        // 受限密钥仅可见集合 10：集合 99 的重复被安全过滤 → 创建。
        var created = service().createLocal(
                request(DocumentDeduplicationScope.LEGACY_GLOBAL),
                10L, EmbeddingPolicy.SKIP, false, "LOCAL_CREATE",
                null, null, null, null);

        assertEquals("CREATED", created.mutation().action());
    }

    @Test
    void restrictedCallerStillSeesAccessibleDuplicates() {
        installCaller("10");
        when(documentRepository.findByContentHash(anyString()))
                .thenReturn(List.of(duplicate(10L, 10L)));

        var created = service().createLocal(
                request(DocumentDeduplicationScope.LEGACY_GLOBAL),
                10L, EmbeddingPolicy.SKIP, false, "LOCAL_CREATE",
                null, null, null, null);

        assertEquals("DUPLICATE", created.mutation().action());
    }

    // ── fixture ─────────────────────────────────────────────────────

    /** 安装指定可见集合的数据库密钥调用方；null 表示不受限。 */
    private void installCaller(String allowedCollectionIds) {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                new ApiAccessPolicy() {
                    @Override public String getPrincipalId() {
                        return "db:test";
                    }

                    @Override public String getCredentialId() {
                        return "rag_k_test";
                    }

                    @Override public ApiKeyRole getRole() {
                        return allowedCollectionIds == null
                                ? ApiKeyRole.ADMIN : ApiKeyRole.NORMAL;
                    }

                    @Override public String getAllowedCollectionIds() {
                        return allowedCollectionIds;
                    }

                    @Override public java.time.LocalDateTime getExpiresAt() {
                        return null;
                    }
                });
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(httpRequest));
    }

    private JsonRecordUpsertRequest jsonRequest(String namespace) {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setSourceNamespace(namespace);
        return request;
    }

    private RagDocument savedDocument(long id) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setCollectionId(10L);
        document.setTitle("Existing");
        document.setContent("Fresh searchable body");
        document.setContentHash(
                com.springairag.core.util.DigestUtils.sha256(
                        "Fresh searchable body"));
        document.setEnabled(true);
        document.setDocumentRevision(3L);
        document.setVersion(2L);
        document.setNextHistoryVersion(3);
        document.setProcessingStatus("COMPLETED");
        return document;
    }

    private RagDocumentVersion version(int number) {
        RagDocumentVersion value = new RagDocumentVersion();
        value.setVersionNumber(number);
        return value;
    }
}
