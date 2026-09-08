package com.springairag.core.service;

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

import java.util.Map;
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

    private PdfToRagService service;
    private String markdown;

    @BeforeEach
    void setUp() {
        service = new PdfToRagService(
                fsFileRepository, documentRepository, documentEmbedService);
        String uuid = "policy-uuid";
        markdown = "# Policy Doc\n\nContent.";
        FsFile fsFile = new FsFile(
                uuid + "/default.md", true, null, markdown, "text/markdown", 64L);
        lenient().when(fsFileRepository.findById(uuid + "/default.md"))
                .thenReturn(Optional.of(fsFile));
        lenient().when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.empty());
        lenient().when(documentRepository.save(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    doc.setId(66L);
                    return doc;
                });
    }

    private EmbeddingDispatchService.Result queuedResult() {
        return new EmbeddingDispatchService.Result(
                com.springairag.api.enums.EmbeddingAction.ASYNC_QUEUED,
                "QUEUED", "profile-key",
                UUID.randomUUID(), UUID.randomUUID(), null);
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

    @Test
    void asyncPolicyEnqueuesThroughDispatchChannel() {
        service.setDispatchService(dispatchService);
        EmbeddingDispatchService.Result queued = queuedResult();
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(), anyString()))
                .thenReturn(queued);

        PdfToRagService.PdfToRagResult result = service.triggerEmbedding(
                "policy-uuid", null, EmbeddingPolicy.ASYNC, false);

        assertEquals(66L, result.documentId());
        assertTrue(result.newlyCreated());
        assertEquals("QUEUED", result.embedStatus());
        assertEquals("ASYNC_QUEUED", result.embeddingAction());
        assertEquals(queued.embeddingJobId(), result.embeddingJobId());
        assertEquals(queued.embeddingBatchId(), result.embeddingBatchId());
    }

    @Test
    void syncPolicyFallsBackToTheLegacySyncTrigger() {
        when(documentEmbedService.embedDocument(eq(66L), eq(false)))
                .thenReturn(Map.of("status", "COMPLETED",
                        "chunksCreated", 3, "message", "OK"));

        // SYNC 未被显式分支处理，回落到 3 参同步触发路径。
        PdfToRagService.PdfToRagResult result = service.triggerEmbedding(
                "policy-uuid", null, EmbeddingPolicy.SYNC, false);

        assertEquals(66L, result.documentId());
        assertEquals("COMPLETED", result.embedStatus());
        assertEquals(3, result.chunksCreated());
    }
}
