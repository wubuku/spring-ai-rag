package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BatchDocumentService 删除与错误脱敏长尾（Batch 658，JaCoCo 驱
 * 动）：硬删除对缺失 revision 回退 1L、safeError 的 null 兜底与
 * 500 字符截断、FsImportBatch 实体全字段存取。
 */
class BatchDocumentServiceDeleteErrorTailTest {

    @Test
    void hardDeleteFallsBackToRevisionOneWhenMissing() {
        var documentRepository = mock(com.springairag.core.repository.RagDocumentRepository.class);
        var mutationService = mock(DocumentMutationService.class);
        RagDocument document = new RagDocument();
        document.setId(9L);
        document.setDocumentRevision(null);
        when(documentRepository.findById(9L)).thenReturn(Optional.of(document));
        when(documentRepository.findAllById(any()))
                .thenReturn(List.of(document));
        var service = new BatchDocumentService(
                documentRepository,
                mock(com.springairag.core.repository.RagEmbeddingRepository.class),
                mock(DocumentEmbedService.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        service.setDocumentMutationService(mutationService);

        var response = service.batchDeleteDocuments(List.of(9L));

        assertTrue(response.results().stream()
                .allMatch(item -> "DELETED".equals(item.status())));
        verify(mutationService).hardDeleteLocal(eq(9L), eq(1L));
    }

    @Test
    void safeErrorMasksAndTruncates() throws Exception {
        var method = BatchDocumentService.class
                .getDeclaredMethod("safeError", String.class);
        method.setAccessible(true);
        var service = new BatchDocumentService(
                mock(com.springairag.core.repository.RagDocumentRepository.class),
                mock(com.springairag.core.repository.RagEmbeddingRepository.class),
                mock(DocumentEmbedService.class));
        service.setDocumentMutationService(null);

        assertEquals("Document creation failed",
                method.invoke(service, (Object) null));
        assertEquals("Document creation failed",
                method.invoke(service, "  "));

        String withSecret = "token=rag_sk_abcdefghijklmnop failed";
        String masked = (String) method.invoke(service, withSecret);
        assertTrue(!masked.contains("rag_sk_abcdefghijklmnop"));

        String oversized = "e".repeat(800);
        assertEquals(500, ((String) method.invoke(service, oversized)).length());
    }

    @Test
    void fsImportBatchEntityRoundTripsAllFields() {
        var batch = new com.springairag.core.entity.FsImportBatch();
        batch.setImportId(java.util.UUID.randomUUID());
        batch.setSourceType("PDF_IMPORT");
        batch.setOriginalFilename("paper.pdf");
        batch.setDisplayName("Paper");
        batch.setEntryPath("uuid-1/default.md");
        batch.setOriginalPath("uuid-1/original.pdf");
        batch.setFileCount(3);
        var now = java.time.OffsetDateTime.now();
        batch.setUpdatedAt(now);

        assertEquals("PDF_IMPORT", batch.getSourceType());
        assertEquals("paper.pdf", batch.getOriginalFilename());
        assertEquals("Paper", batch.getDisplayName());
        assertEquals("uuid-1/default.md", batch.getEntryPath());
        assertEquals("uuid-1/original.pdf", batch.getOriginalPath());
        assertEquals(3, batch.getFileCount());
        assertEquals(now, batch.getUpdatedAt());
    }
}
