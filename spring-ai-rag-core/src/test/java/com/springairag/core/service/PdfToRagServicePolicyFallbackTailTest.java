package com.springairag.core.service;

import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.springairag.core.service.DocumentMutationService;

/**
 * PdfToRagService 变更结果映射长尾（Batch 735 建，Batch 830 收口）：注入变更
 * 服务时 importPdfToRag 的结果携带 mutation 映射。
 *
 * <p>原类名与职责里的"策略回落 / 无变更服务时 SYNC 回落旧版嵌入链"是
 * {@code DocumentMutationService} 缺席时才会走的那条内联落库分支。它在运行
 * 的应用里不可达，已随 Batch 830 删除，对应用例也一并删掉。
 */
class PdfToRagServicePolicyFallbackTailTest {

    private static final String ENTRY = "fb-uuid/default.md";

    private FsFileRepository fsFileRepository;
    private RagDocumentRepository documentRepository;
    private DocumentEmbedService embedService;

    @BeforeEach
    void setUp() {
        fsFileRepository = mock(FsFileRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        embedService = mock(DocumentEmbedService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
        stubEntryMarkdown("# Fallback Doc\n\nBody.");
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
                    doc.setId(77L);
                    return doc;
                });
    }

    @Test
    void importPdfToRagWithMutationServiceMapsMutationResponse() {
        DocumentMutationService mutation = mock(DocumentMutationService.class);
        RagDocument saved = new RagDocument();
        saved.setId(88L);
        saved.setTitle("Fallback Doc");
        DocumentMutationResponse response = Mockito.mock(
                DocumentMutationResponse.class);
        Mockito.when(response.documentId()).thenReturn(88L);
        Mockito.when(response.lifecycle()).thenReturn(
                Mockito.mock(com.springairag.api.dto.DocumentLifecycleResponse.class));
        Mockito.when(response.lifecycle().embeddingStatus())
                .thenReturn("COMPLETED");
        Mockito.when(response.embeddingAction()).thenReturn("NONE");
        Mockito.when(mutation.upsertLocalImport(
                        isNull(), any(DocumentRequest.class), isNull(),
                        anyString(), isNull(), isNull(),
                        eq(EmbeddingPolicy.SYNC), eq(false), eq("PDF_TO_RAG")))
                .thenReturn(new DocumentMutationService.CreatedLocal(
                        saved, response));
        PdfToRagService service = new PdfToRagService(
                fsFileRepository, documentRepository, embedService,
                mutation);;


        var result = service.importPdfToRag(
                ENTRY, "mut.pdf", null, EmbeddingPolicy.SYNC, false);

        assertEquals(88L, result.documentId());
        assertEquals("COMPLETED", result.embedStatus());
        assertEquals("NONE", result.embeddingAction());
    }
}
