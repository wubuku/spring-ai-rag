package com.springairag.core.service;

import com.springairag.core.config.RagPdfProperties;
import com.springairag.core.entity.FsFile;
import com.springairag.core.entity.FsImportBatch;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.repository.FsImportBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PDF 导入的文件管理读取面：原始文件名归一化（路径剥离/控制字符/
 * 后缀校验/长度上限）、按导入 ID 批量查询、虚拟路径子项列举（根与
 * 深层路径、legacy 前导空白兼容）、临时文件资源加载。
 */
class PdfImportFileQueryTest {

    private FsFileRepository fsFileRepository;
    private FsImportBatchRepository fsImportBatchRepository;
    private PdfImportService service;

    @BeforeEach
    void setUp() {
        fsFileRepository = mock(FsFileRepository.class);
        fsImportBatchRepository = mock(FsImportBatchRepository.class);
        service = new PdfImportService(
                fsFileRepository,
                fsImportBatchRepository,
                new RagPdfProperties(),
                List.of());
    }

    private FsFile file(String path) {
        FsFile value = new FsFile();
        value.setPath(path);
        value.setContentBin("content".getBytes(StandardCharsets.UTF_8));
        return value;
    }

    private MockMultipartFile upload(String filename) {
        return new MockMultipartFile(
                "file", filename, "application/pdf", new byte[] {1});
    }

    @Test
    void normalizeStripsClientPathsAndKeepsBasename() {
        assertEquals("paper.pdf", PdfImportService.normalizeOriginalFilename(
                upload("C:\\Users\\upload\\paper.pdf")));
        assertEquals("paper.pdf", PdfImportService.normalizeOriginalFilename(
                upload("/tmp/uploads/paper.pdf")));
        assertEquals("paper.pdf", PdfImportService.normalizeOriginalFilename(
                upload("  paper.pdf  ")));
    }

    @Test
    void normalizeRejectsBlankControlCharsWrongSuffixAndOversize() {
        assertThrows(IllegalArgumentException.class,
                () -> PdfImportService.normalizeOriginalFilename(null));
        MockMultipartFile empty = new MockMultipartFile(
                "file", "a.pdf", "application/pdf", new byte[0]);
        assertThrows(IllegalArgumentException.class,
                () -> PdfImportService.normalizeOriginalFilename(empty));
        assertThrows(IllegalArgumentException.class,
                () -> PdfImportService.normalizeOriginalFilename(upload("bad\nname.pdf")));
        assertThrows(IllegalArgumentException.class,
                () -> PdfImportService.normalizeOriginalFilename(upload("notes.txt")));
        assertThrows(IllegalArgumentException.class,
                () -> PdfImportService.normalizeOriginalFilename(upload("p".repeat(510) + ".pdf")));
    }

    @Test
    void importBatchLookupBySingleIdAndByCollection() {
        UUID id = UUID.randomUUID();
        FsImportBatch batch = mock(FsImportBatch.class);
        when(batch.getImportId()).thenReturn(id);
        when(fsImportBatchRepository.findById(id)).thenReturn(Optional.of(batch));
        when(fsImportBatchRepository.findAllByImportIdIn(anyCollection()))
                .thenReturn(List.of(batch));

        assertEquals(batch, service.getImportBatch(id).orElseThrow());
        assertEquals(Map.of(id, batch),
                service.getImportBatches(List.of(id)));
        assertEquals(Map.of(), service.getImportBatches(List.of()));
        assertEquals(Map.of(), service.getImportBatches(null));
    }

    @Test
    void listChildrenReturnsAllFilesForRootPath() {
        when(fsFileRepository.findAll())
                .thenReturn(List.of(file("uuid-1/default.md")));

        assertEquals(1, service.listChildren("/").size());
        assertEquals(1, service.listChildren(null).size());
        assertEquals(1, service.listChildren("  ").size());
    }

    @Test
    void listChildrenFiltersToDirectChildrenOnly() {
        FsFile entry = file("uuid-1/default.md");
        FsFile nested = file("uuid-1/sub/deep.md");
        FsFile legacySibling = file(" uuid-1/legacy.md");
        when(fsFileRepository.findByPathStartingWithOrderByPathAsc("uuid-1"))
                .thenReturn(List.of(entry, nested, legacySibling));

        List<FsFile> children = service.listChildren("uuid-1/");

        assertEquals(2, children.size());
        assertTrue(children.contains(entry));
        assertTrue(children.contains(legacySibling));
    }

    @Test
    void loadFileAsResourceExposesContentViaTempFile() throws Exception {
        FsFile stored = file("uuid-1/original.pdf");
        when(fsFileRepository.findById("uuid-1/original.pdf"))
                .thenReturn(Optional.of(stored));

        Optional<Resource> resource = service.loadFileAsResource("uuid-1/original.pdf");

        assertTrue(resource.isPresent());
        assertTrue(resource.get().contentLength() > 0);
        assertTrue(resource.get().getFilename().endsWith("original.pdf"));
    }

    @Test
    void loadFileAsResourceReturnsEmptyForUnknownPath() {
        when(fsFileRepository.findById(any())).thenReturn(Optional.empty());

        assertTrue(service.loadFileAsResource("missing/file.md").isEmpty());
    }
}
