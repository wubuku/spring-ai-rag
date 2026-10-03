package com.springairag.core.service;

import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
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

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 显式触发嵌入的策略分派（4 参 triggerEmbedding）：SKIP 拒绝、
 * 缺失持久化任务通道 fail-closed、ASYNC 经分发通道排队并透传
 * job/batch 标识、默认策略回落到既有同步触发路径。
 */
@ExtendWith(MockitoExtension.class)
class PdfToRagEmbedPolicyTest {

    @Mock FsFileRepository fsFileRepository;
    @Mock RagDocumentRepository documentRepository;
    @Mock com.springairag.core.service.DocumentEmbedService documentEmbedService;
    @Mock EmbeddingDispatchService dispatchService;
    @Mock DocumentMutationService mutationService;

    private PdfToRagService service;
    private String markdown;

    @BeforeEach
    void setUp() {
        service = new PdfToRagService(
                fsFileRepository, documentRepository, documentEmbedService);
        // Batch 830：DocumentMutationService 是必选协作者，不再有内联落库回落。
        service.setDocumentMutationService(mutationService);
        String uuid = "policy-uuid";
        markdown = "# Policy Doc\n\nContent.";
        FsFile fsFile = new FsFile(
                uuid + "/default.md", true, null, markdown, "text/markdown", 64L);
        lenient().when(fsFileRepository.findById(uuid + "/default.md"))
                .thenReturn(Optional.of(fsFile));
        lenient().when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.empty());
    }

    @Test
    void skipPolicyIsRejectedOnExplicitTrigger() {
        RagException error = assertThrows(RagException.class,
                () -> service.triggerEmbedding(
                        "policy-uuid", null, EmbeddingPolicy.SKIP, false));
        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCodeEnum());
    }

    @Test
    void asyncPolicyFailsClosedWithoutDispatchChannel() {
        // 未注入 dispatchService：持久化任务通道缺失即拒绝。
        RagException error = assertThrows(RagException.class,
                () -> service.triggerEmbedding(
                        "policy-uuid", null, EmbeddingPolicy.ASYNC, false));
        assertEquals(ErrorCode.EMBEDDING_JOBS_DISABLED,
                error.getErrorCodeEnum());
    }

    /**
     * Batch 830 取代 {@code syncPolicyFallsBackToTheLegacySyncTrigger}。
     *
     * <p>ASYNC 策略现在交给 {@code DocumentMutationService} 执行，任务标识
     * 从**协作者的响应**里回传——不再由本服务自己去 {@code enqueueInCurrentTransaction}
     * 并映射 dispatch 结果。{@code dispatchService} 仍被用于
     * {@code requireJobsEnabled} 那一道 fail-closed 检查。
     */
    @Test
    void asyncPolicyIsHandedToTheMutationServiceAndItsIdentifiersComeBack() {
        service.setDispatchService(dispatchService);
        UUID jobId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        when(mutationService.upsertLocalImport(
                any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    DocumentRequest request = inv.getArgument(1);
                    RagDocument doc = new RagDocument();
                    doc.setId(66L);
                    doc.setTitle(request.getTitle());
                    return new DocumentMutationService.CreatedLocal(doc,
                            new DocumentMutationResponse(
                                    66L, "CREATED", 1L, 1, true, true, true,
                                    "ASYNC_QUEUED", jobId, batchId, null));
                });

        PdfToRagService.PdfToRagResult result = service.triggerEmbedding(
                "policy-uuid", null, EmbeddingPolicy.ASYNC, false);

        assertEquals(66L, result.documentId());
        assertTrue(result.newlyCreated());
        assertEquals("ASYNC_QUEUED", result.embeddingAction());
        assertEquals(jobId, result.embeddingJobId());
        assertEquals(batchId, result.embeddingBatchId());
        verify(mutationService).upsertLocalImport(
                any(), any(), any(), any(), any(), any(),
                eq(EmbeddingPolicy.ASYNC), eq(false), eq("PDF_TO_RAG"));
        // 服务不再自己入队。
        verify(dispatchService, never()).enqueueInCurrentTransaction(
                any(), anyBoolean(), anyBoolean(), anyString());
    }

}
