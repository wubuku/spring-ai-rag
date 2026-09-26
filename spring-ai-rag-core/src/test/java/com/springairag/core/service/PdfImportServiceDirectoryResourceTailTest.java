package com.springairag.core.service;

import com.springairag.core.config.RagPdfProperties;
import com.springairag.core.entity.FsFile;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.repository.FsImportBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PdfImportService 目录列举与资源加载长尾（Batch 657，JaCoCo 驱
 * 动）：前导斜杠路径的查询前缀剥离、直接子文件（无斜杠余量）与
 * 嵌套孙文件（非斜杠余量）的过滤臂、尾随斜杠路径的 file 回退名。
 */
class PdfImportServiceDirectoryResourceTailTest {

    private FsFileRepository fsFileRepository;
    private PdfImportService service;

    @BeforeEach
    void setUp() {
        fsFileRepository = mock(FsFileRepository.class);
        service = new PdfImportService(
                fsFileRepository,
                mock(FsImportBatchRepository.class),
                new RagPdfProperties(),
                List.of());
    }

    private FsFile file(String path) {
        FsFile value = new FsFile();
        value.setPath(path);
        value.setContentBin("content".getBytes(StandardCharsets.UTF_8));
        return value;
    }

    @Test
    void listChildrenStripsLeadingSlashFromQueryPrefix() {
        FsFile entry = file("uuid-1/default.md");
        when(fsFileRepository.findByPathStartingWithOrderByPathAsc("uuid-1"))
                .thenReturn(List.of(entry));

        List<FsFile> children = service.listChildren("/uuid-1/");

        assertEquals(1, children.size());
        assertEquals("uuid-1/default.md", children.getFirst().getPath());
    }

    @Test
    void listChildrenDistinguishesDirectFileFromNestedGrandchild() {
        FsFile direct = file("uuid-1/default.md");
        FsFile grandchild = file("uuid-1/sub/deep.md");
        when(fsFileRepository.findByPathStartingWithOrderByPathAsc("uuid-1"))
                .thenReturn(List.of(direct, grandchild));

        List<FsFile> children = service.listChildren("uuid-1");

        assertEquals(1, children.size());
        assertEquals(direct, children.getFirst());
    }

    @Test
    void loadFileAsResourceFallsBackToFileNamForTrailingSlashPath()
            throws Exception {
        FsFile stored = file("uuid-1/");
        when(fsFileRepository.findById("uuid-1/"))
                .thenReturn(java.util.Optional.of(stored));

        java.util.Optional<Resource> resource =
                service.loadFileAsResource("uuid-1/");

        assertTrue(resource.isPresent());
        assertTrue(resource.get().getFilename() != null
                && resource.get().getFilename().startsWith("fsfile-"));
    }
}
