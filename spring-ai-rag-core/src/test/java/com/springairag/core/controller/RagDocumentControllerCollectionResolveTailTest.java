package com.springairag.core.controller;

import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagDocumentController 集合解析长尾（Batch 564，JaCoCo 驱动）：
 * resolveWritableCollectionId 的 collectionKey 解析路径（unrestricted
 * 调用方经 resolver.resolveActiveIds）、resolveOptionalCollectionId
 * 非空委托。
 */
class RagDocumentControllerCollectionResolveTailTest {

    private CollectionIdentityResolver identityResolver;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        identityResolver = mock(CollectionIdentityResolver.class);
        controller = new RagDocumentController(
                mock(RagDocumentRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                mock(DocumentEmbedService.class),
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                mock(EmbeddingProfileProvider.class),
                identityResolver,
                null);
    }

    private Long invokeResolve(Long collectionId, String collectionKey)
            throws Exception {
        Method method = RagDocumentController.class.getDeclaredMethod(
                "resolveWritableCollectionId",
                Long.class, String.class,
                com.springairag.core.security.ApiAccessPolicy.class);
        method.setAccessible(true);
        return (Long) method.invoke(controller, collectionId, collectionKey,
                null);
    }

    @Test
    void resolveWritableViaCollectionKeyUsesIdentityResolver()
            throws Exception {
        when(identityResolver.resolveActiveIds(
                isNull(), eq(java.util.List.of("kb"))))
                .thenReturn(java.util.List.of(5L));

        Long resolved = invokeResolve(null, "kb");

        assertEquals(5L, resolved);
    }

    @Test
    void resolveWritableWithCollectionIdAndKeyValidatesPair()
            throws Exception {
        when(identityResolver.resolveActiveIds(
                isNull(), eq(java.util.List.of("kb"))))
                .thenReturn(java.util.List.of(5L));

        Long resolved = invokeResolve(5L, "kb");

        assertEquals(5L, resolved);
    }

    @Test
    void resolveOptionalDelegatesWhenKeyPresent() throws Exception {
        when(identityResolver.resolveActiveIds(
                isNull(), eq(java.util.List.of("kb"))))
                .thenReturn(java.util.List.of(5L));

        Method method = RagDocumentController.class.getDeclaredMethod(
                "resolveOptionalCollectionId",
                Long.class, String.class,
                com.springairag.core.security.ApiAccessPolicy.class);
        method.setAccessible(true);

        Long resolved = (Long) method.invoke(controller, null, "kb", null);

        assertEquals(5L, resolved);
    }
}
