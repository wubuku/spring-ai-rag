package com.springairag.core.controller;

import com.springairag.core.service.MarkdownRendererService;
import com.springairag.core.service.PdfImportService;
import com.springairag.core.service.PdfToRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * PdfImportController 路径辅助长尾（Batch 437）：deriveMarkdownPath
 * 的三种路径形态、extractUuid 的分段与斜杠清理、replaceLast 的
 * 末位替换与 null/零限直通。
 */
class PdfImportControllerPathTailTest {

    private PdfImportController controller;
    private Method deriveMarkdownPath;
    private Method extractUuid;
    private Method replaceLast;

    @BeforeEach
    void setUp() throws Exception {
        controller = new PdfImportController(
                mock(PdfImportService.class),
                mock(MarkdownRendererService.class),
                mock(PdfToRagService.class),
                null);
        deriveMarkdownPath = PdfImportController.class.getDeclaredMethod(
                "deriveMarkdownPath", String.class);
        deriveMarkdownPath.setAccessible(true);
        extractUuid = PdfImportController.class.getDeclaredMethod(
                "extractUuid", String.class);
        extractUuid.setAccessible(true);
        replaceLast = PdfImportController.class.getDeclaredMethod(
                "replaceLast", String.class, String.class, String.class, int.class);
        replaceLast.setAccessible(true);
    }

    private String derive(String path) throws Exception {
        return (String) deriveMarkdownPath.invoke(controller, path);
    }

    private String uuid(String path) throws Exception {
        return (String) extractUuid.invoke(controller, path);
    }

    private String replaceLast(String s, String target, String replacement,
                               int limit) throws Exception {
        return (String) replaceLast.invoke(null, s, target, replacement, limit);
    }

    @Test
    void deriveMarkdownPathHandlesAllThreeShapes() throws Exception {
        // 1. 纯 UUID 虚拟目录 → UUID/default.md。
        assertEquals("uuid-1/default.md", derive("uuid-1"));
        // 2. UUID/original.pdf → UUID/default.md。
        assertEquals("uuid-1/default.md", derive("uuid-1/original.pdf"));
        // 既有怪癖：无扩展名的 "/original" 不命中特例分支，
        // 走通用规则追加 .md。
        assertEquals("uuid-1/original.md", derive("uuid-1/original"));
        // 3. 传统路径保留目录并替换扩展名。
        assertEquals("papers/paper.md", derive("papers/paper.pdf"));
        // 无扩展名的带斜杠路径 → 追加 .md。
        assertEquals("papers/notes.md", derive("papers/notes"));
    }

    @Test
    void extractUuidHandlesTrailingSlashAndFirstSegment() throws Exception {
        assertEquals("", uuid(""));
        assertEquals("uuid-1", uuid("uuid-1"));
        assertEquals("uuid-1", uuid("uuid-1/"));
        assertEquals("uuid-1", uuid("uuid-1/original.pdf"));
        // 以斜杠开头（slashIdx == 0）→ 整段返回。
        assertEquals("/rooted", uuid("/rooted"));
    }

    @Test
    void replaceLastOnlyReplacesTheLastOccurrence() throws Exception {
        assertEquals("a/b.md", replaceLast("a/b.pdf", ".pdf", ".md", 1));
        assertEquals("a.pdf.b.md",
                replaceLast("a.pdf.b.pdf", ".pdf", ".md", 1));
        // target 不存在 → 原样返回。
        assertEquals("plain.txt", replaceLast("plain.txt", ".pdf", ".md", 1));
        // null 输入与零限直通。
        assertEquals(null, replaceLast(null, ".pdf", ".md", 1));
        assertEquals("s", replaceLast("s", ".pdf", ".md", 0));
    }
}
