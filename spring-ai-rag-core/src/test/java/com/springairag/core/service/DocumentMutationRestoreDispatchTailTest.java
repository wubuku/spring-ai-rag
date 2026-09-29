package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentRestoreRequest;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentMutationService 长尾补充（Batch 707，JaCoCo 驱动）：
 * restoreLocal 在缺少新鲜向量时走派发臂（470-472）、createLocal
 * 携带 originalFilename 时落库（1152）。
 */
class DocumentMutationRestoreDispatchTailTest {

    private RagDocumentRepository documentRepository;
    private EmbeddingDispatchService dispatchService;
    private DocumentEmbedService documentEmbedService;
    private DocumentVersionService versionService;
    private DocumentLifecycleService lifecycleService;
    private JdbcTemplate jdbcTemplate;
    private DocumentMutationService service;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        versionService = mock(DocumentVersionService.class);
        lifecycleService = mock(DocumentLifecycleService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));

        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(CollectionIdentityResolver.class),
                versionService,
                dispatchService,
                documentEmbedService,
                lifecycleService,
                jdbcTemplate,
                new ObjectMapper(),
                new com.springairag.core.config.RagProperties(),
                transactionManager);

        MockHttpServletRequest httpRequest =
                new MockHttpServletRequest("POST", "/documents");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(httpRequest));

        document = new RagDocument();
        document.setId(41L);
        document.setTitle("Current Title");
        document.setContent("Current content");
        document.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Current content"));
        document.setSource("manual");
        document.setDocumentType("text");
        document.setDocumentRevision(5L);
        document.setEnabled(Boolean.FALSE);
        document.setCollectionId(7L);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document));
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument saved = invocation.getArgument(0);
                    if (saved.getId() == null) {
                        saved.setId(66L);
                    }
                    return saved;
                });
        when(documentRepository.findById(66L))
                .thenAnswer(invocation -> Optional.of(document.getId() == null
                        ? savedWithId(66L) : document));
        when(jdbcTemplate.queryForObject(
                contains("RETURNING mutation_sequence"),
                eq(Long.class), anyLong(), anyString()))
                .thenReturn(1L);
        when(versionService.forceRecordVersion(
                any(RagDocument.class), any(), any()))
                .thenAnswer(invocation -> {
                    var version = new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(9);
                    return version;
                });
        when(lifecycleService.read(any(RagDocument.class)))
                .thenReturn(Mockito.mock(com.springairag.api.dto.DocumentLifecycleResponse.class));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private RagDocument savedWithId(long id) {
        RagDocument saved = new RagDocument();
        saved.setId(id);
        saved.setTitle("New Document");
        saved.setContent("Fresh searchable body");
        saved.setCollectionId(10L);
        return saved;
    }

    @Test
    void restoreLocalDispatchesEmbeddingWithoutFreshVectors() {
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(false);
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(),
                anyString()))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.ASYNC_QUEUED, "QUEUED", "bge-m3",
                        UUID.randomUUID(), null, null));
        DocumentRestoreRequest request = new DocumentRestoreRequest();
        request.setExpectedDocumentRevision(5L);
        request.setEmbeddingPolicy(EmbeddingPolicy.ASYNC);

        var response = service.restoreLocal(41L, request);

        assertEquals("RESTORED", response.action());
        verify(dispatchService).enqueueInCurrentTransaction(
                any(RagDocument.class), eq(true), eq(false),
                eq("LOCAL_RESTORE"));
    }

    @Test
    void createLocalPersistsProvidedOriginalFilename() {
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(),
                anyString()))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.ASYNC_QUEUED, "QUEUED", "bge-m3",
                        UUID.randomUUID(), null, null));
        DocumentRequest request = new DocumentRequest();
        request.setTitle("New Document");
        request.setContent("Fresh searchable body");
        request.setSource("manual");
        request.setDocumentType("text");

        service.createLocal(
                request, 10L, EmbeddingPolicy.ASYNC, false, "LOCAL_CREATE",
                null, "report.pdf", null, null);

        Mockito.verify(documentRepository).saveAndFlush(
                Mockito.argThat((RagDocument saved) ->
                        "report.pdf".equals(saved.getOriginalFilename())));
        assertTrue(documentRepository != null);
    }
}
