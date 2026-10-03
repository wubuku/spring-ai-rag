package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.FsFile;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PdfToRagService 迁移服务分派长尾（Batch 728 建，Batch 830 收口）：
 * triggerEmbedding 与 importPdfToRag 在注入 DocumentMutationService 时
 * 经 upsertLocalImport 变更通道。
 *
 * <p>原职责里的"无变更服务时回落旧版嵌入链并透传结果"是内联落库分支，
 * 运行中的应用里不可达，已随 Batch 830 删除，对应用例也一并删掉。
 */
class PdfToRagServiceMutationDispatchTailTest {

    private static final String UUID_DIR = "mut-uuid";
    private static final String ENTRY = UUID_DIR + "/default.md";

    private FsFileRepository fsFileRepository;
    private RagDocumentRepository documentRepository;
    private EmbeddingDispatchService dispatchService;
    private DocumentEmbedService embedService;

    @BeforeEach
    void setUp() {
        fsFileRepository = mock(FsFileRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        embedService = mock(DocumentEmbedService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void stubEntryMarkdown(String markdown) {
        FsFile fsFile = new FsFile(
                ENTRY, true, null, markdown, "text/markdown", 64L);
        when(fsFileRepository.findById(ENTRY))
                .thenReturn(Optional.of(fsFile));
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.empty());
        when(documentRepository.save(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    doc.setId(66L);
                    return doc;
                });
    }

    private EmbeddingDispatchService.Result queued() {
        return new EmbeddingDispatchService.Result(
                EmbeddingAction.ASYNC_QUEUED, "QUEUED", "bge-m3",
                UUID.randomUUID(), UUID.randomUUID(), null);
    }

    @Test
    void triggerEmbeddingWithMutationServiceUsesMutationChannel() {
        stubEntryMarkdown("# Mut Doc\n\nBody.");
        DocumentMutationService mutation = mock(DocumentMutationService.class);
        DocumentMutationService.CreatedLocal created =
                new DocumentMutationService.CreatedLocal(
                        savedDoc(66L),
                        new DocumentMutationResponse(
                                66L, "CREATED", 1L, 1, true, false, false,
                                "ASYNC_QUEUED", null, null, null));
        when(mutation.upsertLocalImport(
                any(), any(DocumentRequest.class), any(), any(),
                any(), any(), any(EmbeddingPolicy.class), anyBoolean(),
                anyString()))
                .thenReturn(created);
        PdfToRagService service = new PdfToRagService(
                fsFileRepository, documentRepository, embedService);
        service.setDocumentMutationService(mutation);
        service.setDispatchService(dispatchService);
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(),
                anyString()))
                .thenReturn(queued());

        var result = service.triggerEmbedding(
                UUID_DIR, null, EmbeddingPolicy.ASYNC, true);

        assertEquals(66L, result.documentId());
        assertEquals("ASYNC_QUEUED", result.embeddingAction());
    }

    private RagDocument savedDoc(long id) {
        RagDocument doc = new RagDocument();
        doc.setId(id);
        doc.setTitle("Mut Doc");
        return doc;
    }

}
