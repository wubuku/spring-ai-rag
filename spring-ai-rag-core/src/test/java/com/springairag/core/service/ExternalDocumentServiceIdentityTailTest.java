package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExternalDocumentService 身份查询与退役地址接线长尾（Batch
 * 432）：retirement service 存在时的委托与拦截、双参重载默认命
 * 名空间、来源详情映射。
 */
class ExternalDocumentServiceIdentityTailTest {

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver collectionIdentityResolver;
    private ExternalAddressRetirementService retirementService;
    private ExternalDocumentService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        retirementService = mock(ExternalAddressRetirementService.class);
        var profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                9L, "test-profile", "test", "test", "v1",
                1024, "COSINE", "NONE", true));
        service = new ExternalDocumentService(
                documentRepository,
                mock(RagCollectionRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                profileProvider,
                collectionIdentityResolver,
                mock(JdbcTemplate.class),
                mock(PlatformTransactionManager.class));
        service.setAddressRetirementService(retirementService);
        var collection = new RagCollection();
        collection.setId(10L);
        collection.setCollectionKey("kb");
        when(collectionIdentityResolver.requireActive(null, "kb"))
                .thenReturn(collection);
    }

    private void stubDocument() {
        RagDocument doc = new RagDocument();
        doc.setId(41L);
        doc.setCollectionId(10L);
        doc.setSourceNamespace("default");
        doc.setExternalId("ext-1");
        doc.setSourceRevision("rev-1");
        doc.setDocumentType("text");
        doc.setTitle("Test Doc");
        doc.setEnabled(true);
        doc.setProcessingStatus("COMPLETED");
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        10L, "default", "ext-1"))
                .thenReturn(Optional.of(doc));
    }

    @Test
    void identityLookupDelegatesToRetirementService() {
        stubDocument();

        service.getByExternalIdentity("kb", "ext-1");

        verify(retirementService).requireNotRetired(10L, "default", "ext-1");
    }

    @Test
    void identityLookupSurfacesRetirementRejection() {
        doThrow(new IllegalArgumentException("address retired"))
                .when(retirementService)
                .requireNotRetired(10L, "default", "ext-1");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.getByExternalIdentity("kb", "ext-1"));
        assertEquals("address retired", error.getMessage());
    }

    @Test
    void twoArgOverloadDefaultsNamespace() {
        stubDocument();

        var detail = service.getByExternalIdentity("kb", "ext-1");

        verify(retirementService).requireNotRetired(10L, "default", "ext-1");
        org.junit.jupiter.api.Assertions.assertNotNull(detail.title());
    }
}
