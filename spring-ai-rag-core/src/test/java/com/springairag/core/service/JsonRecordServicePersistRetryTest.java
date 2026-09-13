package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertResponse;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.exception.StructuredRecordConflictException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService persist 重试循环（Batch 388）：可重试并发冲
 * 突恢复、重试耗尽抛 StructuredRecordConflictException、非可重
 * 试异常快速失败。
 */
class JsonRecordServicePersistRetryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver resolver;
    private DocumentVersionService versionService;
    private PlatformTransactionManager transactionManager;
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        resolver = mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        transactionManager = mock(PlatformTransactionManager.class);

        var embeddingProfileProvider = mock(EmbeddingProfileProvider.class);
        lenient().when(embeddingProfileProvider.getActiveProfile())
                .thenReturn(new com.springairag.core.config.EmbeddingProfile(
                        9L, "profile", "test", "model", "v1",
                        1024, "COSINE", "NONE", true));
        service = new JsonRecordService(
                documentRepository,
                versionService,
                mock(DocumentEmbedService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                embeddingProfileProvider,
                resolver,
                new RagProperties(),
                MAPPER,
                mock(JdbcTemplate.class),
                transactionManager);
        stubCommon();
    }

    private void stubCommon() {
        lenient().when(resolver.beginActiveWrite(7L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(7L, 0L));
        lenient().when(versionService.forceRecordVersion(
                any(com.springairag.core.entity.RagDocument.class),
                anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(1);
                    return version;
                });
        lenient().when(documentRepository.saveAndFlush(
                any(com.springairag.core.entity.RagDocument.class)))
                .thenAnswer(invocation -> {
                    com.springairag.core.entity.RagDocument value =
                            invocation.getArgument(0);
                    value.setId(41L);
                    return value;
                });
    }

    private JsonRecordUpsertRequest validRequest() {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(7L);
        request.setExternalId("rec-1");
        request.setTitle("Record");
        request.setRetrievalText("text");
        try {
            request.setJsonbPayload(MAPPER.readTree("{\"k\":1}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return request;
    }

    @Test
    void upsertRetriesDataIntegrityViolationThenSucceeds() {
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any()))
                .thenThrow(new DataIntegrityViolationException("seq race"))
                .thenReturn(status);
        when(documentRepository
                .findByCollectionIdAndDocumentTypeAndExternalId(
                        eq(7L), eq(com.springairag.core.entity.RagDocument.JSON_RECORD),
                        eq("rec-1")))
                .thenReturn(Optional.empty());

        JsonRecordUpsertResponse response = service.upsert(validRequest());

        assertEquals("CREATED", response.action());
        verify(documentRepository, times(1)).saveAndFlush(any(
                com.springairag.core.entity.RagDocument.class));
    }

    @Test
    void upsertThrowsStructuredRecordConflictAfterExhaustingRetries() {
        when(transactionManager.getTransaction(any()))
                .thenThrow(new ConcurrencyFailureException("serialized"));

        StructuredRecordConflictException error = assertThrows(
                StructuredRecordConflictException.class,
                () -> service.upsert(validRequest()));
        assertEquals(
                "Concurrent structured-record write did not converge after "
                        + "3 attempts",
                error.getMessage());
        verify(transactionManager, times(3)).getTransaction(any());
    }

    @Test
    void upsertFailsFastOnNonRetryableFailure() {
        when(transactionManager.getTransaction(any()))
                .thenThrow(new IllegalStateException("unrelated"));

        assertThrows(IllegalStateException.class,
                () -> service.upsert(validRequest()));
        verify(transactionManager, times(1)).getTransaction(any());
    }
}
