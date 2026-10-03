package com.springairag.core.service;

import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.DocumentDeduplicationScope;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.entity.FsFile;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.util.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.springairag.core.service.DocumentMutationService;

/**
 * PdfToRagService unit tests.
 */
@ExtendWith(MockitoExtension.class)
class PdfToRagServiceTest {

    @Mock
    private FsFileRepository fsFileRepository;

    @Mock
    private RagDocumentRepository documentRepository;

    @Mock
    private DocumentEmbedService documentEmbedService;

    @Mock
    private DocumentMutationService mutationService;

    private PdfToRagService service;

    @BeforeEach
    void setUp() {
        service = new PdfToRagService(fsFileRepository, documentRepository, documentEmbedService,
 mutationService);
        // Batch 830：legacy 内联落库分支已删除，DocumentMutationService 是必选协作者。

    }

    /** 协作者按请求建好文档并回报"新建"。 */
    private void stubMutationCreates(Long id) {
        when(mutationService.upsertLocalImport(
                any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenAnswer(inv -> PdfToRagMutationFixture.created(
                        inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), id));
    }

    /** 协作者按请求建好文档并回报"更新"（文档已存在，scope 未变）。 */
    private void stubMutationUpdates(Long id) {
        when(mutationService.upsertLocalImport(
                any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenAnswer(inv -> PdfToRagMutationFixture.updated(
                        inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), id));
    }

    /** 取出服务交给协作者的 DocumentRequest。 */
    private DocumentRequest capturedRequest() {
        ArgumentCaptor<DocumentRequest> captor =
                ArgumentCaptor.forClass(DocumentRequest.class);
        verify(mutationService).upsertLocalImport(
                any(), captor.capture(), any(), any(), any(), any(), any(), anyBoolean(), any());
        return captor.getValue();
    }

    // ==================== importPdfToRag tests ====================

    @Test
    void importPdfToRag_newDocument_createsRagDocument() {
        String entryPath = "uuid-123/default.md";
        String filename = "test-paper.pdf";
        String markdown = "# Test\n\nThis is the content.";
        Long collectionId = 5L;

        FsFile fsFile = new FsFile(entryPath, true, null, markdown, "text/markdown", 100L);
        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.empty());
        stubMutationCreates(42L);
        // embed=false
        PdfToRagService.PdfToRagResult result = service.importPdfToRag(
                entryPath, filename, collectionId, false, false);

        assertEquals(42L, result.documentId());
        assertEquals("test-paper", result.title());
        assertTrue(result.newlyCreated());
        assertNull(result.embedStatus());

        // 断言对象从"内联代码 save 出来的文档"换成"服务请求协作者写入的内容"——
        // 后者才是生产契约（Batch 830）。
        DocumentRequest request = capturedRequest();
        assertEquals("test-paper", request.getTitle());
        assertEquals(markdown, request.getContent());
        assertEquals("pdf-import:" + entryPath, request.getSource());
        assertEquals("markdown", request.getDocumentType());
        assertNotNull(request.getMetadata());
        assertEquals("pdf", request.getMetadata().get("importedFrom"));
        assertEquals("uuid-123", request.getMetadata().get("uuid"));
        // collectionId / originalFilename 是 upsertLocalImport 的独立参数，不在请求里。
        verify(mutationService).upsertLocalImport(
                isNull(), any(DocumentRequest.class), eq(collectionId),
                eq(filename), isNull(), isNull(), eq(EmbeddingPolicy.SKIP),
                eq(false), eq("PDF_TO_RAG"));
    }

    @Test
    void importPdfToRag_sameSource_returnsExistingDocument() {
        String entryPath = "uuid-456/default.md";
        String filename = "same-content.pdf";
        String markdown = "# Same Content";
        String contentHash = DigestUtils.sha256(markdown);

        FsFile fsFile = new FsFile(entryPath, true, null, markdown, "text/markdown", 50L);
        RagDocument existing = new RagDocument();
        existing.setId(99L);
        existing.setTitle("same-content");
        existing.setContent(markdown);
        existing.setContentHash(contentHash);
        existing.setSource("pdf-import:" + entryPath);
        existing.setDocumentType("markdown");
        existing.setOriginalFilename(filename);
        existing.setSize((long) markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        existing.setMetadata(Map.of(
                "importedFrom", "pdf",
                "fsFilesPath", entryPath,
                "uuid", "uuid-456"));

        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findFirstBySourceOrderByIdAsc(
                "pdf-import:" + entryPath))
                .thenReturn(Optional.of(existing));
        stubMutationUpdates(99L);

        PdfToRagService.PdfToRagResult result = service.importPdfToRag(
                entryPath, filename, null, false, false);

        assertEquals(99L, result.documentId());
        assertFalse(result.newlyCreated());
        // 已存在的文档要作为 existingDocumentId 交给协作者，而不是被服务就地改写。
        verify(mutationService).upsertLocalImport(
                eq(99L), any(DocumentRequest.class), isNull(), eq(filename),
                isNull(), isNull(), eq(EmbeddingPolicy.SKIP), eq(false),
                eq("PDF_TO_RAG"));
    }

    @Test
    void importPdfToRag_sameContentDifferentSource_createsDistinctDocument() {
        String entryPath = "uuid-new/default.md";
        String markdown = "# Same Content";
        FsFile fsFile = new FsFile(
                entryPath, true, null, markdown, "text/markdown", 50L);
        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findFirstBySourceOrderByIdAsc(
                "pdf-import:" + entryPath))
                .thenReturn(Optional.empty());
        stubMutationCreates(100L);

        PdfToRagService.PdfToRagResult result = service.importPdfToRag(
                entryPath, "copy.pdf", null, false, false);

        assertEquals(100L, result.documentId());
        assertTrue(result.newlyCreated());
        // 去重由 upsertLocalImport 负责（请求已标 NONE），服务不再自行按内容哈希查重。
        assertEquals(DocumentDeduplicationScope.NONE, capturedRequest().getDeduplicationScope());
    }

    @Test
    void importPdfToRag_existingSourceWithChangedContent_updatesDocument() {
        String entryPath = "uuid-updated/default.md";
        String markdown = "# Updated";
        FsFile fsFile = new FsFile(
                entryPath, true, null, markdown, "text/markdown", 50L);
        RagDocument existing = new RagDocument();
        existing.setId(101L);
        existing.setContent("# Old");
        existing.setContentHash(DigestUtils.sha256("# Old"));
        existing.setSource("pdf-import:" + entryPath);

        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findFirstBySourceOrderByIdAsc(
                "pdf-import:" + entryPath))
                .thenReturn(Optional.of(existing));
        stubMutationUpdates(101L);

        PdfToRagService.PdfToRagResult result = service.importPdfToRag(
                entryPath, "updated.pdf", null, false, false);

        assertFalse(result.newlyCreated());
        assertEquals(101L, result.documentId());
        // 内容变更不再由服务就地改写，而是整份请求交给协作者。
        verify(mutationService).upsertLocalImport(
                eq(101L), any(DocumentRequest.class), isNull(), eq("updated.pdf"),
                isNull(), isNull(), eq(EmbeddingPolicy.SKIP), eq(false),
                eq("PDF_TO_RAG"));
        DocumentRequest request = capturedRequest();
        assertEquals(markdown, request.getContent());
        assertEquals("updated", request.getTitle());
    }

    /**
     * Batch 830 取代 {@code importPdfToRag_withEmbedding_triggersEmbed}。
     *
     * <p>旧用例断言 {@code documentEmbedService.embedDocument} 被调用——那是
     * legacy 分支的行为。在生产里（协作者在场）{@code embed=true} 一直走的是
     * {@code upsertLocalImport(policy = SYNC)}，嵌入由协作者按策略执行，
     * 服务自己从不调 {@code embedDocument}。所以真正该钉的是
     * **SYNC 策略确实被交到了协作者手上**。
     */
    @Test
    void importPdfToRag_embedTrue_handsSyncPolicyToTheMutationService() {
        String entryPath = "embed-uuid/default.md";
        FsFile fsFile = new FsFile(
                entryPath, true, null, "Content here.", "text/markdown", 80L);
        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.empty());
        stubMutationCreates(7L);

        PdfToRagService.PdfToRagResult result = service.importPdfToRag(
                entryPath, "embed-me.pdf", null, true, false);

        assertEquals(7L, result.documentId());
        assertTrue(result.newlyCreated());
        verify(mutationService).upsertLocalImport(
                isNull(), any(DocumentRequest.class), isNull(), eq("embed-me.pdf"),
                isNull(), isNull(), eq(EmbeddingPolicy.SYNC), eq(false),
                eq("PDF_TO_RAG"));
        // 服务不再自己驱动嵌入：那是协作者按策略做的事。
        verify(documentEmbedService, never()).embedDocument(anyLong(), anyBoolean());
    }

    @Test
    void importPdfToRag_markdownNotFound_throwsException() {
        when(fsFileRepository.findById("nonexistent/default.md")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.importPdfToRag("nonexistent/default.md", "test.pdf", null, false, false));

        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void importPdfToRag_emptyContent_throwsException() {
        FsFile emptyFile = new FsFile("uuid/default.md", true, null, null, "text/markdown", 0L);
        when(fsFileRepository.findById("uuid/default.md")).thenReturn(Optional.of(emptyFile));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.importPdfToRag("uuid/default.md", "test.pdf", null, false, false));

        assertTrue(ex.getMessage().contains("no text content"));
    }

    @Test
    void importPdfToRag_titleDerivation_handlesVariousFilenames() {
        // Test: filename without .pdf
        FsFile fsFile = new FsFile("u/default.md", true, null, "x", "text/markdown", 1L);
        when(fsFileRepository.findById(anyString())).thenReturn(Optional.of(fsFile));
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.empty());
        stubMutationCreates(1L);

        PdfToRagService.PdfToRagResult r = service.importPdfToRag("u/default.md", "noextension", null, false, false);
        assertEquals("noextension", r.title());

        r = service.importPdfToRag("u/default.md", "very-long-name.pdf", null, false, false);
        assertEquals("very-long-name", r.title());
        assertTrue(r.title().length() <= 200);
    }

    @Test
    void importPdfToRagWithEmbedding_sseProgressForwardsEvents() {
        String entryPath = "sse-uuid/default.md";
        FsFile fsFile = new FsFile(entryPath, true, null, "Content here", "text/markdown", 50L);
        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.empty());
        stubMutationCreates(10L);

        @SuppressWarnings("unchecked")
        Consumer<com.springairag.api.dto.EmbedProgressEvent> cb = mock(Consumer.class);
        when(documentEmbedService.embedDocumentWithProgress(eq(10L), eq(false), any()))
                .thenAnswer(inv -> {
                    // Simulate SSE progress events
                    var callback = inv.getArgument(2, Consumer.class);
                    callback.accept(new com.springairag.api.dto.EmbedProgressEvent("PREPARING", 0, 0, "prep", 10L));
                    callback.accept(new com.springairag.api.dto.EmbedProgressEvent("COMPLETED", 5, 5, "done", 10L));
                    return Map.of("status", "COMPLETED", "chunksCreated", 5, "message", "Done");
                });

        PdfToRagService.PdfToRagResult result = service.importPdfToRagWithEmbedding(
                entryPath, "sse-test.pdf", null, false, cb);

        assertEquals(10L, result.documentId());
        assertEquals("COMPLETED", result.embedStatus());
        assertEquals(5, result.chunksCreated());

        verify(cb, times(2)).accept(any()); // PREPARING + COMPLETED
    }

    @Test
    void importPdfToRagWithEmbedding_doesNotWrapProviderCallInTransaction() throws Exception {
        assertNull(AnnotatedElementUtils.findMergedAnnotation(
                PdfToRagService.class, Transactional.class));
        assertNull(AnnotatedElementUtils.findMergedAnnotation(
                PdfToRagService.class.getMethod(
                        "importPdfToRagWithEmbedding",
                        String.class,
                        String.class,
                        Long.class,
                        boolean.class,
                        Consumer.class),
                Transactional.class));
    }

    // ==================== triggerEmbedding tests (already-imported PDF) ====================

    @Test
    void triggerEmbedding_newDocument_createsAndEmbeds() {
        String uuid = "new-trigger-uuid";
        String entryPath = uuid + "/default.md";
        String markdown = "# New Doc\n\nContent to embed.";

        FsFile fsFile = new FsFile(entryPath, true, null, markdown, "text/markdown", 100L);
        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.empty());
        stubMutationCreates(55L);
        when(documentEmbedService.embedDocument(eq(55L), eq(false)))
                .thenReturn(Map.of("status", "COMPLETED", "chunksCreated", 4, "message", "OK"));

        PdfToRagService.PdfToRagResult result = service.triggerEmbedding(uuid, null, false);

        assertEquals(55L, result.documentId());
        assertTrue(result.newlyCreated());
        assertEquals("COMPLETED", result.embedStatus());
        assertEquals(4, result.chunksCreated());

        DocumentRequest request = capturedRequest();
        assertEquals("pdf-import:" + entryPath, request.getSource());
        assertEquals("markdown", request.getDocumentType());
    }

    @Test
    void triggerEmbedding_existingDocument_reusesAndEmbeds() {
        String uuid = "existing-uuid";
        String entryPath = uuid + "/default.md";
        String markdown = "# Existing Content";

        FsFile fsFile = new FsFile(entryPath, true, null, markdown, "text/markdown", 80L);
        RagDocument existing = new RagDocument();
        existing.setId(88L);
        existing.setTitle("PDF Import " + uuid);
        existing.setContent(markdown);
        existing.setContentHash(DigestUtils.sha256(markdown));
        existing.setSource("pdf-import:" + entryPath);
        existing.setDocumentType("markdown");
        existing.setOriginalFilename(entryPath);
        existing.setSize((long) markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        existing.setMetadata(Map.of(
                "importedFrom", "pdf",
                "fsFilesPath", entryPath,
                "uuid", uuid));

        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findFirstBySourceOrderByIdAsc(
                "pdf-import:" + entryPath))
                .thenReturn(Optional.of(existing));
        when(documentEmbedService.embedDocument(eq(88L), eq(false)))
                .thenReturn(Map.of("status", "CACHED", "chunksCreated", 3, "message", "already done"));
        stubMutationUpdates(88L);

        PdfToRagService.PdfToRagResult result = service.triggerEmbedding(uuid, null, false);

        assertEquals(88L, result.documentId());
        assertFalse(result.newlyCreated());
        assertEquals("CACHED", result.embedStatus());
        // 已存在的文档交给协作者复用，而不是服务自己 save 一份。
        verify(mutationService).upsertLocalImport(
                eq(88L), any(DocumentRequest.class), isNull(), eq(entryPath),
                isNull(), isNull(), eq(EmbeddingPolicy.SKIP), eq(false),
                eq("PDF_TO_RAG"));
    }

    @Test
    void triggerEmbedding_notFound_throwsException() {
        when(fsFileRepository.findById("nonexistent/default.md")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.triggerEmbedding("nonexistent", null, false));

        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void triggerEmbedding_cachedDocument_skipsEmbedding() {
        // newlyCreated=false, forceReembed=false, embeddedContentHash matches contentHash
        // → should return CACHED without calling embedDocument
        String uuid = "cached-uuid";
        String entryPath = uuid + "/default.md";
        String markdown = "# Cached Content";

        FsFile fsFile = new FsFile(entryPath, true, null, markdown, "text/markdown", 50L);
        RagDocument existing = new RagDocument();
        existing.setId(5L);
        existing.setContent(markdown);
        existing.setContentHash(DigestUtils.sha256(markdown));
        existing.setEmbeddedContentHash(DigestUtils.sha256(markdown));
        existing.setProcessingStatus("COMPLETED");
        existing.setDocumentType("markdown");
        existing.setSize((long) markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        existing.setMetadata(Map.of(
                "importedFrom", "pdf",
                "fsFilesPath", entryPath,
                "uuid", uuid));

        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.of(existing));
        // 协作者回报"已存在且内容哈希与已嵌入哈希一致"，doEmbed 据此判 CACHED。
        when(mutationService.upsertLocalImport(
                any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    DocumentRequest request = inv.getArgument(1);
                    RagDocument doc = new RagDocument();
                    doc.setId(5L);
                    doc.setContent(request.getContent());
                    doc.setContentHash(DigestUtils.sha256(markdown));
                    doc.setEmbeddedContentHash(DigestUtils.sha256(markdown));
                    doc.setProcessingStatus("COMPLETED");
                    doc.setTitle(request.getTitle());
                    return new DocumentMutationService.CreatedLocal(doc,
                            PdfToRagMutationFixture.updated(
                                    request, inv.getArgument(2), inv.getArgument(3), 5L)
                                    .mutation());
                });

        PdfToRagService.PdfToRagResult result = service.triggerEmbedding(uuid, null, false);

        assertEquals(5L, result.documentId());
        assertFalse(result.newlyCreated());
        assertEquals("CACHED", result.embedStatus());
        assertTrue(result.embedMessage().contains("skipping"));
        verify(documentEmbedService, never()).embedDocument(anyLong(), anyBoolean());
    }

    @Test
    void triggerEmbedding_withCollectionId_updatesCollectionId() {
        String uuid = "col-uuid";
        String entryPath = uuid + "/default.md";

        FsFile fsFile = new FsFile(entryPath, true, null, "content", "text/markdown", 50L);
        RagDocument existing = new RagDocument();
        existing.setId(1L);
        existing.setContent("content");
        existing.setCollectionId(null);

        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.of(existing));
        stubMutationUpdates(1L);
        when(documentEmbedService.embedDocument(eq(1L), anyBoolean()))
                .thenReturn(Map.of("status", "COMPLETED", "chunksCreated", 1));

        PdfToRagService.PdfToRagResult result = service.triggerEmbedding(uuid, 99L, false);

        assertEquals(1L, result.documentId());
        assertFalse(result.newlyCreated());
        // 改集合归属同样是交给协作者：collectionId 是 upsertLocalImport 的独立参数。
        verify(mutationService).upsertLocalImport(
                eq(1L), any(DocumentRequest.class), eq(99L), anyString(),
                isNull(), isNull(), eq(EmbeddingPolicy.SKIP), eq(false),
                eq("PDF_TO_RAG"));
    }

    @Test
    void triggerEmbeddingWithProgress_sseCallbackForwarded() {
        String uuid = "sse-trigger";
        String entryPath = uuid + "/default.md";
        FsFile fsFile = new FsFile(entryPath, true, null, "Content", "text/markdown", 50L);

        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(Optional.empty());
        stubMutationCreates(20L);

        @SuppressWarnings("unchecked")
        Consumer<com.springairag.api.dto.EmbedProgressEvent> cb = mock(Consumer.class);
        when(documentEmbedService.embedDocumentWithProgress(eq(20L), eq(true), any()))
                .thenAnswer(inv -> {
                    var callback = inv.getArgument(2, Consumer.class);
                    callback.accept(new com.springairag.api.dto.EmbedProgressEvent("PREPARING", 0, 0, "prep", 20L));
                    callback.accept(new com.springairag.api.dto.EmbedProgressEvent("COMPLETED", 2, 2, "done", 20L));
                    return Map.of("status", "COMPLETED", "chunksCreated", 2, "message", "Done");
                });

        PdfToRagService.PdfToRagResult result = service.triggerEmbeddingWithProgress(
                uuid, null, true, cb);

        assertEquals(20L, result.documentId());
        assertEquals("COMPLETED", result.embedStatus());
        verify(cb, times(2)).accept(any());
    }

    // ==================== null-safety tests ====================

    @Test
    void importPdfToRag_nullEntryMarkdownPath_throws() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> service.importPdfToRag(null, "file.pdf", 1L, false, false));
        assertEquals("entryMarkdownPath must not be null", ex.getMessage());
    }

    @Test
    void importPdfToRagWithEmbedding_nullEntryMarkdownPath_throws() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> service.importPdfToRagWithEmbedding(null, "file.pdf", 1L, false, null));
        assertEquals("entryMarkdownPath must not be null", ex.getMessage());
    }

    @Test
    void triggerEmbedding_nullUuid_throws() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> service.triggerEmbedding(null, 1L, false));
        assertEquals("uuid must not be null", ex.getMessage());
    }

    @Test
    void triggerEmbeddingWithProgress_nullUuid_throws() {
        @SuppressWarnings("unchecked")
        Consumer<com.springairag.api.dto.EmbedProgressEvent> cb = mock(Consumer.class);
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> service.triggerEmbeddingWithProgress(null, 1L, false, cb));
        assertEquals("uuid must not be null", ex.getMessage());
    }
}
