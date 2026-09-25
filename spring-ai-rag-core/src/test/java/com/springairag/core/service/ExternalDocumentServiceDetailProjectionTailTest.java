package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ExternalDocumentService 详情投影长尾（Batch 645，JaCoCo 驱
 * 动）：toDetail 对缺集合归属文档的空名称/键映射、集合存在时的
 * 名称与键投影、collectionKeyFor 对 null 集合返回 null。
 */
class ExternalDocumentServiceDetailProjectionTailTest {

    private RagDocumentRepository documentRepository;
    private RagCollectionRepository collectionRepository;
    private EmbeddingProfileProvider profileProvider;
    private ExternalDocumentService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        collectionRepository = mock(RagCollectionRepository.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        var profile = new EmbeddingProfile(
                9L, "test-profile", "test", "test", "v1",
                1024, "COSINE", "NONE", true);
        lenient().when(profileProvider.getActiveProfile()).thenReturn(profile);
        service = new ExternalDocumentService(
                documentRepository,
                collectionRepository,
                mock(RagEmbeddingRepository.class),
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                profileProvider,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                mock(PlatformTransactionManager.class));
    }

    private RagDocument document(Long collectionId) {
        RagDocument doc = new RagDocument();
        doc.setId(41L);
        doc.setCollectionId(collectionId);
        doc.setSourceNamespace("default");
        doc.setExternalId("ext-1");
        doc.setTitle("外部文档");
        doc.setContent("正文内容");
        doc.setDocumentType("text");
        doc.setEnabled(Boolean.TRUE);
        return doc;
    }

    private com.springairag.api.dto.DocumentDetailResponse detailOf(
            RagDocument doc) {
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(doc));
        // 通过反射驱动私有 toDetail 投影。
        try {
            java.lang.reflect.Method method = ExternalDocumentService.class
                    .getDeclaredMethod("toDetail", RagDocument.class);
            method.setAccessible(true);
            return (com.springairag.api.dto.DocumentDetailResponse)
                    method.invoke(service, doc);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void detailWithoutCollectionProjectsEmptyNameAndKeyMaps() {
        var detail = detailOf(document(null));

        assertEquals(null, detail.collectionId());
        org.junit.jupiter.api.Assertions.assertTrue(
                detail.collectionName() == null
                        || detail.collectionName().isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(
                detail.collectionKey() == null
                        || detail.collectionKey().isEmpty());
    }

    @Test
    void detailWithCollectionProjectsNameAndKey() {
        RagCollection collection = new RagCollection();
        collection.setId(10L);
        collection.setCollectionKey("kb");
        collection.setName("知识库");
        when(collectionRepository.findById(10L))
                .thenReturn(Optional.of(collection));

        var detail = detailOf(document(10L));

        assertEquals("知识库", detail.collectionName());
        assertEquals("kb", detail.collectionKey());
    }

    @Test
    void detailWithMissingCollectionRowProjectsEmptyMaps() {
        when(collectionRepository.findById(99L))
                .thenReturn(Optional.empty());

        var detail = detailOf(document(99L));

        org.junit.jupiter.api.Assertions.assertTrue(
                detail.collectionName() == null
                        || detail.collectionName().isEmpty());
    }
}
