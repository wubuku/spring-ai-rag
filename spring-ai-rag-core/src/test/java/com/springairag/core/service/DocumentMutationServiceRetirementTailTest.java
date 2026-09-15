package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.DocumentDeduplicationScope;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DocumentMutationService 长尾（Batch 399）：requireAddressNotRetired
 * 对 ExternalAddressRetirementService 的委托与放行、tombstoneExternal
 * 编排中的退役地址守卫、findDuplicate 的 null 作用域默认与受限密钥
 * 过滤、executeExternalInTransaction 对 ConcurrencyFailureException
 * 的可重试分支。
 */
class DocumentMutationServiceRetirementTailTest {

    private static final long COLLECTION_ID = 5L;

    private RagDocumentRepository documentRepository;
    private PlatformTransactionManager transactionManager;
    private DocumentVersionService versionService;
    private ExternalAddressRetirementService retirementService;
    private JdbcTemplate jdbcTemplate;
    private DocumentMutationService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        versionService = mock(DocumentVersionService.class);
        retirementService = mock(ExternalAddressRetirementService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        stubSourceSequenceAllocation();
        service = newService();
    }

    /** 命名空间条件 DML 返回自增后的序列（生产由 RETURNING 提供）。 */
    private void stubSourceSequenceAllocation() {
        lenient().when(jdbcTemplate.queryForObject(
                anyString(),
                org.mockito.ArgumentMatchers.eq(Long.class),
                any(), any())).thenReturn(1L);
    }

    private DocumentMutationService newService() {
        return new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(CollectionIdentityResolver.class),
                versionService,
                mock(EmbeddingDispatchService.class),
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private Object invoke(String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = DocumentMutationService.class
                .getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(service, args);
    }

    private Throwable invokeFailing(String name, Class<?>[] types,
                                    Object... args) {
        try {
            invoke(name, types, args);
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }
    }

    private void authenticateWithAllowedCollections(String allowedIds) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/documents");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                new AuthenticatedApiPrincipal(
                        "db:1", "kid", 1,
                        ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                        ApiKeyRole.NORMAL, allowedIds, null, 1L, null));
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    // ── requireAddressNotRetired 委托 ──────────────────────────────

    @Test
    void requireAddressNotRetiredIsNoOpWithoutRetirementService() {
        // 默认构造（未注入退役服务）→ 直接放行，不抛异常。
        assertDoesNotThrow(() -> invoke("requireAddressNotRetired",
                new Class<?>[]{long.class, String.class, String.class},
                COLLECTION_ID, "crm", "ext-1"));
    }

    @Test
    void requireAddressNotRetiredDelegatesWithExactAddress() throws Exception {
        service.setAddressRetirementService(retirementService);

        Object unused = invoke("requireAddressNotRetired",
                new Class<?>[]{long.class, String.class, String.class},
                COLLECTION_ID, "crm", "ext-1");

        verify(retirementService).requireNotRetired(
                COLLECTION_ID, "crm", "ext-1");
    }

    @Test
    void requireAddressNotRetiredSurfacesRetirementFailure() {
        service.setAddressRetirementService(retirementService);
        org.mockito.Mockito.doThrow(new IllegalStateException("retired address"))
                .when(retirementService)
                .requireNotRetired(anyLong(), anyString(), anyString());

        Throwable cause = invokeFailing("requireAddressNotRetired",
                new Class<?>[]{long.class, String.class, String.class},
                COLLECTION_ID, "crm", "ext-1");

        assertEquals(IllegalStateException.class, cause.getClass());
        assertEquals("retired address", cause.getMessage());
    }

    // ── tombstoneExternal 编排中的退役地址守卫 ─────────────────────

    @Test
    void tombstoneExternalAbortsWhenAddressWasRelocated() {
        service.setAddressRetirementService(retirementService);
        CollectionIdentityResolver resolver =
                mock(CollectionIdentityResolver.class);
        RagCollection collection = new RagCollection();
        collection.setId(COLLECTION_ID);
        collection.setCollectionKey("kb");
        when(resolver.requireActive(null, "kb")).thenReturn(collection);
        org.mockito.Mockito.doThrow(new IllegalStateException("retired address"))
                .when(retirementService)
                .requireNotRetired(COLLECTION_ID, "crm", "ext-1");

        // 重建 service，让 tombstoneExternal 内部使用同一 resolver。
        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                resolver,
                versionService,
                mock(EmbeddingDispatchService.class),
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);
        service.setAddressRetirementService(retirementService);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.tombstoneExternal(
                        "kb", "crm", "ext-1", "rev-2", null, false));
        assertEquals("retired address", error.getMessage());
        // 守卫先于落库：文档实体不得被改写或保存。
        verify(documentRepository, never()).saveAndFlush(any(RagDocument.class));
        verifyNoInteractions(versionService);
    }

    // ── findDuplicate：null 作用域默认与受限密钥过滤 ───────────────

    @Test
    void findDuplicateTreatsNullScopeAsLegacyGlobal() throws Exception {
        RagDocument candidate = new RagDocument();
        candidate.setId(41L);
        candidate.setCollectionId(7L);
        when(documentRepository.findByContentHash("hash"))
                .thenReturn(List.of(candidate));

        Object found = invoke("findDuplicate",
                new Class<?>[]{DocumentDeduplicationScope.class,
                        String.class, Long.class},
                null, "hash", 7L);

        // null 作用域回退 LEGACY_GLOBAL：命中跨集合哈希。
        assertEquals(41L, ((RagDocument) found).getId());
    }

    @Test
    void findDuplicateFiltersDocumentsOutsideRestrictedKeyScope()
            throws Exception {
        authenticateWithAllowedCollections("9");
        RagDocument outsideScope = new RagDocument();
        outsideScope.setId(41L);
        outsideScope.setCollectionId(7L);
        when(documentRepository.findByContentHash("hash"))
                .thenReturn(List.of(outsideScope));

        // 受限密钥（仅允许集合 9）不可见集合 7 的重复文档 → 过滤为 null。
        Object found = invoke("findDuplicate",
                new Class<?>[]{DocumentDeduplicationScope.class,
                        String.class, Long.class},
                DocumentDeduplicationScope.LEGACY_GLOBAL, "hash", 7L);
        assertNull(found);
    }

    @Test
    void findDuplicateKeepsDocumentsInsideRestrictedKeyScope() throws Exception {
        authenticateWithAllowedCollections("7,9");
        RagDocument inScope = new RagDocument();
        inScope.setId(42L);
        inScope.setCollectionId(7L);
        when(documentRepository.findByContentHash("hash"))
                .thenReturn(List.of(inScope));

        Object found = invoke("findDuplicate",
                new Class<?>[]{DocumentDeduplicationScope.class,
                        String.class, Long.class},
                DocumentDeduplicationScope.LEGACY_GLOBAL, "hash", 7L);

        assertEquals(42L, ((RagDocument) found).getId());
    }

    @Test
    void findDuplicateReturnsNullWithoutHashMatch() throws Exception {
        when(documentRepository.findByContentHash("hash"))
                .thenReturn(List.of());

        Object found = invoke("findDuplicate",
                new Class<?>[]{DocumentDeduplicationScope.class,
                        String.class, Long.class},
                DocumentDeduplicationScope.LEGACY_GLOBAL, "hash", 7L);

        assertNull(found);
    }

    // ── executeExternalInTransaction：ConcurrencyFailure 可重试 ────

    @Test
    void externalTxRetriesConcurrencyFailureOnFirstAttempt()
            throws Exception {
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any()))
                .thenThrow(new org.springframework.dao.ConcurrencyFailureException(
                        "serialization race"))
                .thenReturn(status);

        Method method = DocumentMutationService.class.getDeclaredMethod(
                "executeExternalInTransaction", boolean.class,
                java.util.function.Supplier.class);
        method.setAccessible(true);

        int[] attempts = {0};
        Object result = method.invoke(service, false,
                (java.util.function.Supplier<String>) () -> {
                    attempts[0]++;
                    return "ok-" + attempts[0];
                });

        assertEquals("ok-1", result);
        assertEquals(1, attempts[0]);
        // 事务恰好开启两次：第一次序列化冲突，第二次成功。
        verify(transactionManager, times(2)).getTransaction(any());
    }
}
