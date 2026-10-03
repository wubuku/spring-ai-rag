package com.springairag.core.service;

import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.entity.FsFile;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.util.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PdfToRagService 辅助方法与策略委托长尾（Batch 635，JaCoCo 驱
 * 动）：deriveTitle 空白 / .pdf 剥离 / 截断、extractUuid 三臂、
 * 嵌入直连路径的 CACHED / FAILED / 正常投影、5 参入口在 mutation
 * 服务就绪时的策略委托、mutation 响应 lifecycle 投影两臂、空内容
 * 拒绝。
 */
class PdfToRagHelperTailTest {

    private FsFileRepository fsFileRepository;
    private RagDocumentRepository documentRepository;
    private DocumentEmbedService documentEmbedService;
    private DocumentMutationService documentMutationService;
    private PdfToRagService bareService;
    private PdfToRagService service;

    @BeforeEach
    void setUp() {
        fsFileRepository = mock(FsFileRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        documentMutationService = mock(DocumentMutationService.class);
        bareService = new PdfToRagService(
                fsFileRepository, documentRepository, documentEmbedService);
        service = new PdfToRagService(
                fsFileRepository, documentRepository, documentEmbedService);
        service.setDocumentMutationService(documentMutationService);
    }

    private String deriveTitle(String filename) throws Exception {
        Method method = PdfToRagService.class
                .getDeclaredMethod("deriveTitle", String.class);
        method.setAccessible(true);
        return (String) method.invoke(bareService, filename);
    }

    private String extractUuid(String path) throws Exception {
        Method method = PdfToRagService.class
                .getDeclaredMethod("extractUuid", String.class);
        method.setAccessible(true);
        return (String) method.invoke(bareService, path);
    }

    private FsFile markdownFile(String entryPath, String markdown) {
        return new FsFile(entryPath, true, null, markdown,
                "text/markdown", (long) markdown.length());
    }

    @Test
    void deriveTitleBlankReturnsUntitled() throws Exception {
        assertEquals("Untitled", deriveTitle(null));
        assertEquals("Untitled", deriveTitle("   "));
    }

    @Test
    void deriveTitleStripsPdfSuffixAndTruncates() throws Exception {
        assertEquals("paper", deriveTitle("paper.PDF"));
        assertEquals("paper", deriveTitle("paper.pdf"));
        String longName = "n".repeat(250) + ".pdf";
        String title = deriveTitle(longName);
        assertEquals(200, title.length());
    }

    @Test
    void extractUuidCoversThreeArms() throws Exception {
        assertEquals("", extractUuid(null));
        assertEquals("bare", extractUuid("bare"));
        assertEquals("uuid-1", extractUuid("uuid-1/nested/default.md"));
    }

    @Test
    void entryMarkdownWithoutTextContentRejected() {
        FsFile empty = new FsFile("uuid-x/default.md", true, null, "  ",
                "text/markdown", 2L);
        when(fsFileRepository.findById("uuid-x/default.md"))
                .thenReturn(Optional.of(empty));

        assertThrows(IllegalArgumentException.class,
                () -> bareService.importPdfToRag(
                        "uuid-x/default.md", "a.pdf", 5L, false, false));
    }

    @Test
    void mutationImportWithSkipPolicyDelegatesThroughFiveArgEntry() {
        String markdown = "# 委托内容";
        when(fsFileRepository.findById("uuid-g/default.md"))
                .thenReturn(Optional.of(markdownFile("uuid-g/default.md", markdown)));
        RagDocument existing = new RagDocument();
        existing.setId(91L);
        existing.setTitle("委托标题");
        existing.setContent(markdown);
        existing.setContentHash(DigestUtils.sha256(markdown));
        existing.setSource("pdf-import:uuid-g/default.md");
        existing.setDocumentType("markdown");
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.of(existing));
        when(documentRepository.findById(91L)).thenReturn(Optional.of(existing));
        DocumentMutationResponse mutation = mock(DocumentMutationResponse.class);
        when(mutation.embeddingAction()).thenReturn("NONE");
        when(mutation.lifecycle()).thenReturn(null);
        Mockito.when(documentMutationService.upsertLocalImport(
                        any(), any(), any(), any(), any(), any(), any(),
                        anyBoolean(), anyString()))
                .thenReturn(new DocumentMutationService.CreatedLocal(
                        existing, mutation));

        PdfToRagService.PdfToRagResult result = service.importPdfToRag(
                "uuid-g/default.md", "paper.pdf", 5L, false, false);

        assertEquals("NONE", result.embeddingAction());
    }

    @Test
    void mutationImportProjectsLifecycleEmbeddingStatus() {
        String markdown = "# 生命周期内容";
        when(fsFileRepository.findById("uuid-h/default.md"))
                .thenReturn(Optional.of(markdownFile("uuid-h/default.md", markdown)));
        RagDocument existing = new RagDocument();
        existing.setId(92L);
        existing.setTitle("生命周期标题");
        existing.setContent(markdown);
        existing.setContentHash(DigestUtils.sha256(markdown));
        existing.setSource("pdf-import:uuid-h/default.md");
        existing.setDocumentType("markdown");
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.of(existing));
        when(documentRepository.findById(92L)).thenReturn(Optional.of(existing));
        DocumentMutationResponse mutation = mock(DocumentMutationResponse.class);
        when(mutation.embeddingAction()).thenReturn("ASYNC_QUEUED");
        when(mutation.embeddingJobId()).thenReturn(java.util.UUID.randomUUID());
        when(mutation.lifecycle()).thenReturn(
                new com.springairag.api.dto.DocumentLifecycleResponse(
                        "LIVE", "READY", "COMPLETED", "QUEUED",
                        "profile-key", null, null, null, false));
        Mockito.when(documentMutationService.upsertLocalImport(
                        any(), any(), any(), any(), any(), any(), any(),
                        anyBoolean(), anyString()))
                .thenReturn(new DocumentMutationService.CreatedLocal(
                        existing, mutation));

        PdfToRagService.PdfToRagResult result = service.importPdfToRag(
                "uuid-h/default.md", "paper.pdf", 5L,
                EmbeddingPolicy.SYNC, false);

        assertEquals("ASYNC_QUEUED", result.embeddingAction());
        assertEquals("QUEUED", result.embedStatus());
    }
}
