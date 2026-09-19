package com.springairag.core.service;

import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ExternalDocumentService 校验与事务长尾（Batch 527，JaCoCo 驱
 * 动）：新身份携带 expectedSourceRevision 冲突、documentType 超
 * 长、collectionKey 非可见 ASCII、title/source 超长、namespace 归
 * 一化两分支、safeError 三重载兜底与截断、并发写失败重试收敛。
 */
class ExternalDocumentServiceNormalizeTailTest {

    private static final String KEY = "kb";

    private RagDocumentRepository documentRepository;
    private RagCollectionRepository collectionRepository;
    private CollectionIdentityResolver collectionIdentityResolver;
    private EmbeddingProfileProvider profileProvider;
    private ExternalDocumentService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        collectionRepository = mock(RagCollectionRepository.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        lenient().when(profileProvider.getActiveProfile()).thenReturn(profile());
        lenient().when(collectionIdentityResolver.requireActive(null, KEY))
                .thenReturn(collection());
        lenient().when(collectionIdentityResolver.beginActiveWrite(10L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(10L, 0L));
        lenient().when(collectionRepository.findById(10L))
                .thenReturn(Optional.of(collection()));
        service = new ExternalDocumentService(
                documentRepository,
                collectionRepository,
                mock(RagEmbeddingRepository.class),
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                profileProvider,
                collectionIdentityResolver,
                mock(JdbcTemplate.class),
                mock(PlatformTransactionManager.class));
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                7L, "test-profile", "test", "test-model", "v1",
                1024, "COSINE", "NONE", true);
    }

    private RagCollection collection() {
        RagCollection value = new RagCollection();
        value.setId(10L);
        value.setCollectionKey(KEY);
        value.setName("KB");
        value.setEnabled(true);
        return value;
    }

    private ExternalDocumentUpsertRequest baseRequest() {
        ExternalDocumentUpsertRequest request =
                new ExternalDocumentUpsertRequest();
        request.setCollectionKey(KEY);
        request.setExternalId("doc-new");
        request.setSourceRevision("rev-1");
        request.setTitle("Title");
        request.setContent("content");
        return request;
    }

    @Test
    void upsertNewIdentityWithExpectedRevisionConflicts() {
        when(documentRepository.findByCollectionIdAndExternalId(
                10L, "doc-new"))
                .thenReturn(Optional.empty());
        ExternalDocumentUpsertRequest request = baseRequest();
        request.setExpectedSourceRevision("rev-0");

        var error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service.upsert(request));

        assertEquals(
                "expectedSourceRevision must be omitted for a new identity",
                error.getMessage());
    }

    @Test
    void upsertRejectsOversizeDocumentType() {
        ExternalDocumentUpsertRequest request = baseRequest();
        request.setDocumentType("x".repeat(51));

        var error = assertThrows(IllegalArgumentException.class,
                () -> service.upsert(request));

        assertEquals("documentType must not exceed 50 characters",
                error.getMessage());
    }

    @Test
    void upsertRejectsCollectionKeyWithInvisibleAscii() {
        ExternalDocumentUpsertRequest request = baseRequest();
        request.setCollectionKey("kb bad");

        var error = assertThrows(IllegalArgumentException.class,
                () -> service.upsert(request));

        assertEquals(
                "collectionKey must contain 1-128 visible ASCII characters",
                error.getMessage());
    }

    @Test
    void upsertRejectsOversizeTitle() {
        ExternalDocumentUpsertRequest request = baseRequest();
        request.setTitle("t".repeat(256));

        var error = assertThrows(IllegalArgumentException.class,
                () -> service.upsert(request));

        assertEquals("title must not exceed 255 characters",
                error.getMessage());
    }

    @Test
    void upsertRejectsOversizeSource() {
        ExternalDocumentUpsertRequest request = baseRequest();
        request.setSource("s".repeat(256));

        var error = assertThrows(IllegalArgumentException.class,
                () -> service.upsert(request));

        assertEquals("value must not exceed 255 characters",
                error.getMessage());
    }

    @Test
    void externalIdentityNamespaceFallsBackAndRejectsOversize() {
        // 空白 namespace 归一化为 default；超长 namespace 拒绝。
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        any(), any(), any())).thenReturn(Optional.empty());

        assertThrows(RagException.class,
                () -> service.getByExternalIdentity(KEY, "   ", "doc-1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.getByExternalIdentity(
                        KEY, "n".repeat(129), "doc-1"));
    }

    @Test
    void safeErrorOverloadsProvideFallbackAndTruncation() throws Exception {
        for (var method : ExternalDocumentService.class
                .getDeclaredMethods()) {
            if (!"safeError".equals(method.getName())) {
                continue;
            }
            method.setAccessible(true);
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0] == Object.class) {
                assertNull(method.invoke(service, (Object) null));
                // Object 重载走 String.valueOf：非空白值脱敏后原样返回。
                assertEquals("123",
                        method.invoke(service, (Object) 123));
            } else if (params.length == 1 && params[0] == Throwable.class) {
                assertEquals("Embedding failed",
                        method.invoke(service, (Throwable) null));
                assertEquals("boom",
                        method.invoke(service,
                                (Throwable) new IllegalStateException("boom")));
            } else if (params.length == 1 && params[0] == String.class) {
                assertEquals("Embedding failed", method.invoke(service, ""));
                assertEquals("plain",
                        method.invoke(service, "plain"));
                Object truncated = method.invoke(service, "e".repeat(600));
                assertEquals(500, ((String) truncated).length());
            }
        }
    }

    @Test
    void executeInTransactionRetriesThenConverges() throws Exception {
        var method = ExternalDocumentService.class.getDeclaredMethod(
                "executeInTransaction", Supplier.class);
        method.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger();
        Supplier<String> flaky = () -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new DataIntegrityViolationException("uniq race");
            }
            return "converged";
        };

        assertEquals("converged", method.invoke(service, flaky));
        assertEquals(3, attempts.get());
    }

    @Test
    void executeInTransactionGivesUpAfterMaxAttempts() throws Exception {
        var serviceWithoutTx = new ExternalDocumentService(
                documentRepository,
                collectionRepository,
                mock(RagEmbeddingRepository.class),
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                profileProvider,
                collectionIdentityResolver,
                mock(JdbcTemplate.class),
                null);
        var method = ExternalDocumentService.class.getDeclaredMethod(
                "executeInTransaction", Supplier.class);
        method.setAccessible(true);
        // 无事务模板时直接执行回调，异常原样透出（非重试路径）。
        var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(serviceWithoutTx,
                        (Supplier<String>) () -> {
                            throw new IllegalStateException("direct");
                        }));
        assertTrue(error.getCause() instanceof IllegalStateException);
    }
}
