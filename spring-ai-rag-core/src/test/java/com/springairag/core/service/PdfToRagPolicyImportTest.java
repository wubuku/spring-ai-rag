package com.springairag.core.service;

import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.service.PdfToRagService.PdfToRagResult;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.FsFile;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PDF 导入的策略变体（importPdfToRag 5 参重载）：ASYNC 无作业服
 * 务 fail-closed、ASYNC 经 dispatch 排队并映射作业标识、SKIP 委派
 * mutation 管道（upsertLocalImport）并映射变更响应。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PdfToRagPolicyImportTest {

    private static final String ENTRY_PATH = "uuid-123/default.md";

    @Mock FsFileRepository fsFileRepository;
    @Mock RagDocumentRepository documentRepository;
    @Mock DocumentEmbedService documentEmbedService;
    @Mock EmbeddingDispatchService dispatchService;
    @Mock DocumentMutationService mutationService;

    private PdfToRagService service;

    @BeforeEach
    void setUp() {
        service = new PdfToRagService(
                fsFileRepository, documentRepository, documentEmbedService);
        service.setDispatchService(dispatchService);
        service.setDocumentMutationService(mutationService);
    }

    private void stubMarkdown(String entryPath, String content) {
        FsFile fsFile = new FsFile(
                entryPath, true, null, content, "text/markdown", 100L);
        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
    }

    private RagDocument document(long id, String title) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setTitle(title);
        document.setEnabled(true);
        return document;
    }

    private DocumentMutationResponse mutation(String action) {
        DocumentLifecycleResponse lifecycle = new DocumentLifecycleResponse(
                "LIVE", "SEARCHABLE", "COMPLETED", "SKIPPED", "profile-key",
                null, null, null, false);
        return new DocumentMutationResponse(
                42L, action, 2L, 5, false, false, false,
                "SKIPPED", null, null, lifecycle);
    }

    @Test
    void asyncWithoutJobsFailsClosed() {
        // dispatchService 不可用：先撤下再调用 ASYNC 入口。
        service.setDispatchService(null);
        stubMarkdown(ENTRY_PATH, "# Test\n\nContent.");

        RagException error = assertThrows(RagException.class,
                () -> service.importPdfToRag(
                        ENTRY_PATH, "test-paper.pdf", 5L,
                        EmbeddingPolicy.ASYNC, false));

        assertEquals(ErrorCode.EMBEDDING_JOBS_DISABLED, error.getErrorCodeEnum());
    }

    @Test
    void asyncQueuesJobAndMapsDispatchIdentifiers() {
        // legacy 路径（无 mutation service）：ASYNC 经 dispatch 排队。
        PdfToRagService legacyService = new PdfToRagService(
                fsFileRepository, documentRepository, documentEmbedService);
        legacyService.setDispatchService(dispatchService);
        stubMarkdown(ENTRY_PATH, "# Test\n\nContent.");
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.empty());
        when(documentRepository.save(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    doc.setId(42L);
                    return doc;
                });
        UUID jobId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), eq(true), eq(false), eq("PDF_TO_RAG")))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.ASYNC_QUEUED, "QUEUED", "profile-key",
                        jobId, batchId, null));

        PdfToRagService.PdfToRagResult result = legacyService.importPdfToRag(
                        ENTRY_PATH, "test-paper.pdf", 5L,
                        EmbeddingPolicy.ASYNC, false);

        assertEquals(42L, result.documentId());
        assertEquals("QUEUED", result.embedStatus());
        // 生产行为：embedMessage 槽位承载 action 名称。
        assertEquals("ASYNC_QUEUED", result.embedMessage());
        assertNull(result.chunksCreated());
        assertEquals("ASYNC_QUEUED", result.embeddingAction());
        assertEquals(jobId, result.embeddingJobId());
        assertEquals(batchId, result.embeddingBatchId());
        verify(dispatchService).enqueueInCurrentTransaction(
                any(RagDocument.class), eq(true), eq(false), eq("PDF_TO_RAG"));
        verify(documentEmbedService, never()).embedDocument(any(), anyBoolean());
    }

    @Test
    void skipPolicyDelegatesToMutationService() {
        // mutation 管道路径：documentMutationService 存在时全部策略
        // （含 ASYNC）都经 upsertLocalImport 委派。
        service.setDocumentMutationService(mutationService);
        stubMarkdown(ENTRY_PATH, "# Test\n\nContent.");
        RagDocument document = document(42L, "test-paper");
        DocumentMutationResponse mutation = mutation("UNCHANGED");
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.empty());
        when(mutationService.upsertLocalImport(
                isNull(), any(), eq(5L), eq("test-paper.pdf"),
                isNull(), isNull(), eq(EmbeddingPolicy.SKIP), eq(false),
                eq("PDF_TO_RAG")))
                .thenReturn(new DocumentMutationService.CreatedLocal(
                        document, mutation));

        PdfToRagService.PdfToRagResult result = service.importPdfToRag(
                ENTRY_PATH, "test-paper.pdf", 5L,
                EmbeddingPolicy.SKIP, false);

        assertEquals(42L, result.documentId());
        // PdfToRagResult 无 action 组件：mutation 的动作经嵌入字段体现。
        assertEquals("SKIPPED", result.embedStatus());
        assertEquals("SKIPPED", result.embeddingAction());
        assertNull(result.embeddingJobId());
        // mutation 管道接管：不再走 legacy 保存与同步嵌入。
        verify(documentRepository, never()).save(any(RagDocument.class));
        verify(documentEmbedService, never()).embedDocument(any(), anyBoolean());
    }
}
