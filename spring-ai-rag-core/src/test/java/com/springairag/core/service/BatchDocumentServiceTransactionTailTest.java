package com.springairag.core.service;

import com.springairag.api.dto.DocumentRequest;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BatchDocumentService 集合归属解析长尾（Batch 667 建，Batch 832 收口）：
 * per-doc collectionId 优先于批次级 collectionId。
 *
 * <p>原职责里的"ASYNC 策略在事务模板内执行遗留创建链并提交事务"是
 * {@code DocumentMutationService} 缺席时才会走的那条分支——它自带一个
 * {@code TransactionTemplate}。该分支在运行的应用里不可达，已随 Batch 832
 * 连同 {@code transactionTemplate} 字段与那个 4 参构造器一起删除，
 * 对应用例也一并删掉。
 */
class BatchDocumentServiceTransactionTailTest {

    private RagDocumentRepository documentRepository;
    private RagEmbeddingRepository embeddingRepository;
    private EmbeddingDispatchService dispatchService;
    private DocumentMutationService mutationService;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        mutationService = mock(DocumentMutationService.class);
        lenient().when(dispatchService.enqueueInCurrentTransaction(
                        any(), anyBoolean(), anyBoolean(), anyString()))
                .thenAnswer(invocation -> new com.springairag.core.embeddingjob
                        .EmbeddingDispatchService.Result(
                        com.springairag.api.enums.EmbeddingAction.ASYNC_QUEUED,
                        "QUEUED", "bge-m3", java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(), null));
    }

    private DocumentRequest request(Long collectionId) {
        DocumentRequest req = new DocumentRequest();
        req.setTitle("文档");
        req.setContent("正文内容");
        req.setSource("batch");
        req.setDocumentType("text");
        req.setCollectionId(collectionId);
        return req;
    }


    private BatchDocumentService service(boolean async) {
        var service = new BatchDocumentService(
                documentRepository,
                embeddingRepository,
                mutationService);
        if (async) {
            service.setDispatchService(dispatchService);
        }
        return service;
    }

    @Test
    void perDocCollectionIdTakesPrecedenceOverBatchLevel() {
        BatchDocumentMutationFixture.stubCreates(mutationService);

        service(false).batchCreateDocuments(
                List.of(request(7L)), false, 99L, false);

        // 归属解析发生在把请求交给协作者之前：collectionId 是 createLocal 的
        // 独立参数（不在 DocumentRequest 里），断言它比断言落库结果更贴近生产契约。
        verify(mutationService).createLocal(
                any(), eq(7L), any(), anyBoolean(), anyString(), any(),
                any(), any(), any());
    }
}
