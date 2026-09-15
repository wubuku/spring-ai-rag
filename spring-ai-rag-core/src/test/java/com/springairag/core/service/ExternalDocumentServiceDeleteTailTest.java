package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ExternalDocumentService sourceDelete 传统分支长尾（Batch 422，
 * mutationService 为 null 的直连路径）：目标文档缺失、JSON 记录
 * 身份拒绝、expectedSourceRevision 不匹配。
 */
class ExternalDocumentServiceDeleteTailTest {

    private static final long COLLECTION_ID = 10L;

    private RagDocumentRepository documentRepository;
    private ExternalDocumentService service;
    private RagCollection collection;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        var collectionRepository = mock(RagCollectionRepository.class);
        var embeddingRepository = mock(RagEmbeddingRepository.class);
        var documentVersionService = mock(DocumentVersionService.class);
        var documentEmbedService = mock(DocumentEmbedService.class);
        var embeddingProfileProvider = mock(EmbeddingProfileProvider.class);
        var collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        var jdbcTemplate = mock(JdbcTemplate.class);
        var transactionManager = mock(PlatformTransactionManager.class);

        collection = new RagCollection();
        collection.setId(COLLECTION_ID);
        collection.setCollectionKey("customer-42:manual:v1");
        collection.setEnabled(true);

        lenient().when(collectionIdentityResolver.requireActive(
                null, collection.getCollectionKey())).thenReturn(collection);
        lenient().when(collectionIdentityResolver.beginActiveWrite(COLLECTION_ID))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(
                        COLLECTION_ID, 0L));
        lenient().when(embeddingProfileProvider.getActiveProfile())
                .thenReturn(new EmbeddingProfile(
                        1L, "profile-key", "provider", "model", "rev", 1024,
                        "COSINE", "PROVIDER_DEFAULT", true));
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        lenient().when(documentVersionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(3);
                    return version;
                });
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service = new ExternalDocumentService(
                documentRepository,
                collectionRepository,
                embeddingRepository,
                documentVersionService,
                documentEmbedService,
                embeddingProfileProvider,
                collectionIdentityResolver,
                jdbcTemplate,
                transactionManager);
    }

    private RagDocument document(String documentType, String sourceRevision,
                                 boolean enabled) {
        RagDocument document = new RagDocument();
        document.setId(41L);
        document.setCollectionId(COLLECTION_ID);
        document.setExternalId("cms-1");
        document.setDocumentType(documentType);
        document.setSourceRevision(sourceRevision);
        document.setEnabled(enabled);
        return document;
    }

    @Test
    void deleteMissingDocumentReportsNotFound() {
        when(documentRepository.findByCollectionIdAndExternalId(
                eq(COLLECTION_ID), eq("cms-1"))).thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.sourceDelete(
                        collection.getCollectionKey(), "cms-1",
                        "rev-2", null));
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void deleteJsonRecordIdentityIsRejected() {
        when(documentRepository.findByCollectionIdAndExternalId(
                eq(COLLECTION_ID), eq("cms-1")))
                .thenReturn(Optional.of(document(
                        RagDocument.JSON_RECORD, "rev-1", true)));

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service.sourceDelete(
                        collection.getCollectionKey(), "cms-1", "rev-2", null));
        assertEquals(true, error.getMessage().contains("JSON record"));
    }

    @Test
    void deleteWithMismatchedExpectedRevisionIsRejected() {
        when(documentRepository.findByCollectionIdAndExternalId(
                eq(COLLECTION_ID), eq("cms-1")))
                .thenReturn(Optional.of(document("text", "rev-1", true)));

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service.sourceDelete(
                        collection.getCollectionKey(), "cms-1",
                        "rev-2", "stale-expected"));
        assertEquals(true, error.getMessage()
                .contains("expectedSourceRevision does not match"));
    }
}
