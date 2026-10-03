package com.springairag.core.service;

import com.springairag.api.dto.DocumentRequest;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BatchDocumentService 删除路径长尾（Batch 485 建，Batch 832 收口）：
 * 单文档删除级联与缺失拒绝，以及批量删除的外部管理文档保护。
 *
 * <p>本类原有 6 条用例覆盖的是"没有 {@code DocumentMutationService} 就自己
 * 按内容哈希查重、自己 save、自己驱动嵌入"那条 legacy 分支——它自带一个
 * {@code TransactionTemplate}。该分支在运行的应用里不可达，已随 Batch 832
 * 连同 {@code createSingleDocument}、{@code transactionTemplate} 与那个
 * 4 参构造器一起删除，对应用例也一并删掉。类名里的 "Legacy" 随之失效，
 * 故改名为 {@code BatchDocumentServiceDeleteTailTest}。
 */
class BatchDocumentServiceDeleteTailTest {

    private RagDocumentRepository documentRepository;
    private RagEmbeddingRepository embeddingRepository;
    private DocumentMutationService mutationService;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        mutationService = mock(DocumentMutationService.class);
    }

    private BatchDocumentService service() {
        BatchDocumentService service = new BatchDocumentService(
                documentRepository, embeddingRepository);
        service.setDocumentMutationService(mutationService);
        return service;
    }

    private DocumentRequest request(String title, String content) {
        DocumentRequest req = new DocumentRequest();
        req.setTitle(title);
        req.setContent(content);
        req.setSource("test");
        return req;
    }

    private void stubSaveAssignsId() {
        when(documentRepository.save(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    if (doc.getId() == null) {
                        doc.setId(41L);
                    }
                    return doc;
                });
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void deleteSingleDocumentCascadesAndRejectsMissing() {
        BatchDocumentService service = service();
        when(documentRepository.existsById(9L)).thenReturn(false);

        assertThrows(DocumentNotFoundException.class,
                () -> service.deleteDocument(9L));

        when(documentRepository.existsById(9L)).thenReturn(true);
        when(embeddingRepository.countByDocumentId(9L)).thenReturn(3L);

        var response = service.deleteDocument(9L);

        assertEquals(3L, response.embeddingsRemoved());
        verify(embeddingRepository).deleteByDocumentId(9L);
        verify(documentRepository).deleteById(9L);
    }

    @Test
    void batchDeleteRejectsExternalManagedDocuments() {
        BatchDocumentService service = service();
        RagDocument external = new RagDocument();
        external.setId(9L);
        external.setExternalId("ext-1");
        when(documentRepository.findAllById(List.of(9L)))
                .thenReturn(List.of(external));

        assertThrows(
                com.springairag.core.exception.DocumentRevisionConflictException.class,
                () -> service.batchDeleteDocuments(List.of(9L)));
    }
}
