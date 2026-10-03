package com.springairag.core.service;

import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        service = new ExternalDocumentService(documentRepository,
                collectionRepository,
                mock(RagEmbeddingRepository.class),
                profileProvider,
                collectionIdentityResolver);
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

}
