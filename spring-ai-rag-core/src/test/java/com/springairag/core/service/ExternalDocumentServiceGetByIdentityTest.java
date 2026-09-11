package com.springairag.core.service;

import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import org.mockito.Mockito;
import static org.mockito.Mockito.when;

/**
 * getByExternalIdentity 的守卫与分支：文档缺失 → NOT_FOUND、
 * JSON record 冲突、双参入口默认 namespace。
 */
class ExternalDocumentServiceGetByIdentityTest {

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver collectionIdentityResolver;
    private EmbeddingProfileProvider profileProvider;
    private ExternalDocumentService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        var profile = new EmbeddingProfile(
                9L, "test-profile", "test", "test", "v1",
                1024, "COSINE", "NONE", true);
        when(profileProvider.getActiveProfile()).thenReturn(profile);
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
    }

    private RagCollection collection(long id, String key) {
        RagCollection value = new RagCollection();
        value.setId(id);
        value.setCollectionKey(key);
        return value;
    }

    private RagDocument textDocument(long id) {
        RagDocument doc = new RagDocument();
        doc.setId(id);
        doc.setCollectionId(10L);
        doc.setSourceNamespace("default");
        doc.setExternalId("ext-1");
        doc.setSourceRevision("rev-1");
        doc.setDocumentType("text");
        doc.setTitle("Test Doc");
        doc.setEnabled(true);
        doc.setProcessingStatus("COMPLETED");
        return doc;
    }

    private void stubCollection() {
        when(collectionIdentityResolver.requireActive(null, "kb"))
                .thenReturn(collection(10L, "kb"));
    }

    @Test
    void getByExternalIdentityNotFoundThrowsNotFound() {
        stubCollection();
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        10L, "default", "ext-1"))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.getByExternalIdentity("kb", "ext-1"));

        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void getByExternalIdentityRejectsJsonRecord() {
        stubCollection();
        RagDocument jsonDoc = textDocument(5L);
        jsonDoc.setDocumentType("json-record");
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        10L, "default", "ext-1"))
                .thenReturn(Optional.of(jsonDoc));

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service.getByExternalIdentity("kb", "ext-1"));

        assertTrue(error.getMessage().contains("use the JSON record API"));
    }

}
