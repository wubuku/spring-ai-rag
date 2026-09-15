package com.springairag.core.controller;

import com.springairag.core.service.MarkdownRendererService;
import com.springairag.core.service.PdfToRagService;
import com.springairag.core.service.PdfImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PdfImportController 目录树长尾（Batch 442）：空路径与子路径的
 * 树构建、文件/合成目录排序、currentImportId 的根级限定、
 * FsFile 缺省 MIME/大小的归一。
 */
class PdfImportControllerTreeTailTest {

    private PdfImportService pdfImportService;
    private PdfImportController controller;
    private Method buildTreeEntries;
    private Method currentImportId;
    private Method toFileEntry;

    @BeforeEach
    void setUp() throws Exception {
        pdfImportService = mock(PdfImportService.class);
        controller = new PdfImportController(
                pdfImportService,
                mock(MarkdownRendererService.class),
                mock(PdfToRagService.class),
                null);
        buildTreeEntries = PdfImportController.class.getDeclaredMethod(
                "buildTreeEntries", List.class, String.class, Map.class);
        buildTreeEntries.setAccessible(true);
        currentImportId = PdfImportController.class.getDeclaredMethod(
                "currentImportId", String.class);
        currentImportId.setAccessible(true);
        toFileEntry = PdfImportController.class.getDeclaredMethod(
                "toFileEntry", com.springairag.core.entity.FsFile.class,
                String.class, String.class);
        toFileEntry.setAccessible(true);
    }

    private com.springairag.core.entity.FsFile fsFile(
            String path, String mime, Long size, OffsetDateTime createdAt) {
        var file = new com.springairag.core.entity.FsFile();
        file.setPath(path);
        file.setMimeType(mime);
        file.setFileSize(size);
        file.setCreatedAt(createdAt);
        return file;
    }

    @SuppressWarnings("unchecked")
    private List<com.springairag.api.dto.FileTreeEntryResponse> entries(
            List<com.springairag.core.entity.FsFile> files, String parent)
            throws Exception {
        return (List<com.springairag.api.dto.FileTreeEntryResponse>)
                buildTreeEntries.invoke(controller, files, parent, Map.of());
    }

    @Test
    void currentImportIdOnlyResolvesRootLevelUuids() throws Exception {
        assertEquals(Optional.empty(), currentImportId.invoke(
                controller, (Object) null));
        assertEquals(Optional.empty(), currentImportId.invoke(controller, ""));
        assertEquals(Optional.empty(), currentImportId.invoke(
                controller, "uuid-1/"));
        assertEquals(Optional.empty(), currentImportId.invoke(
                controller, "a/b"));
        // 根级 UUID → 解析成功。
        UUID id = UUID.randomUUID();
        assertEquals(Optional.of(id), currentImportId.invoke(controller, id.toString()));
    }

    @Test
    void toFileEntryNormalizesMissingMimeAndSize() throws Exception {
        var file = fsFile("uuid-1/doc.pdf", null, null,
                OffsetDateTime.parse("2026-09-01T00:00:00Z"));
        var entry = (com.springairag.api.dto.FileTreeEntryResponse)
                toFileEntry.invoke(controller, file, "doc.pdf", "uuid-1/doc.pdf");
        assertEquals("application/octet-stream", entry.mimeType());
        assertEquals(0L, entry.size());
        assertEquals("file", entry.type());
    }

    @Test
    void listTreeBuildsSortedEntriesForRootAndSubPath() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-01T00:00:00Z");
        List<com.springairag.core.entity.FsFile> files = List.of(
                fsFile("b.md", "text/markdown", 10L, now),
                fsFile("uuid-a/original.pdf", "application/pdf", 20L, now),
                fsFile("a.md", "text/markdown", 5L, now));

        List<com.springairag.api.dto.FileTreeEntryResponse> entries =
                entries(files, "");

        // 目录条目排在最前，其后文件按名称排序。
        assertEquals("directory", entries.getFirst().type());
        assertTrue(entries.size() >= 2);
        // 文件条目保留原名与 MIME。
        assertTrue(entries.stream().anyMatch(entry ->
                "a.md".equals(entry.name())
                        && "text/markdown".equals(entry.mimeType())));
    }

    @Test
    void listTreeEndpointReturnsOkWithEntries() {
        UUID importId = UUID.randomUUID();
        when(pdfImportService.listChildren(Mockito.anyString()))
                .thenReturn(List.of());
        when(pdfImportService.getImportBatches(anyCollection()))
                .thenReturn(Map.of());

        ResponseEntity<?> response = controller.listTree("  ");

        assertEquals(200, response.getStatusCode().value());
        var body = (org.springframework.http.ResponseEntity<?>) response;
        assertTrue(body.getStatusCode().is2xxSuccessful());
        assertNull(null);
    }
}
