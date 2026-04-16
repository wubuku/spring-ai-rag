package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FsFile entity.
 * Covers: both constructors, getters/setters, isText default value.
 */
class FsFileTest {

    @Test
    void defaultConstructor() {
        FsFile file = new FsFile();
        assertNull(file.getPath());
        // isText defaults to Boolean.FALSE (field initializer)
        assertEquals(Boolean.FALSE, file.getIsText());
        assertNull(file.getContentBin());
        assertNull(file.getContentTxt());
        assertNull(file.getMimeType());
        assertNull(file.getFileSize());
        assertNull(file.getCreatedAt());
        assertNull(file.getUpdatedAt());
    }

    @Test
    void fullConstructor() {
        byte[] bin = new byte[]{0x01, 0x02, 0x03};
        OffsetDateTime now = OffsetDateTime.now();

        FsFile file = new FsFile(
                "papers/doc.pdf",
                true,
                bin,
                "text content",
                "application/pdf",
                1024L
        );

        assertEquals("papers/doc.pdf", file.getPath());
        assertEquals(true, file.getIsText());
        assertArrayEquals(bin, file.getContentBin());
        assertEquals("text content", file.getContentTxt());
        assertEquals("application/pdf", file.getMimeType());
        assertEquals(1024L, file.getFileSize());
    }

    @Test
    void allGettersAndSetters() {
        FsFile file = new FsFile();
        byte[] bin = new byte[]{0x04, 0x05};
        OffsetDateTime now = OffsetDateTime.now();

        file.setPath("uploads/image.png");
        file.setIsText(false);
        file.setContentBin(bin);
        file.setContentTxt("markdown content here");
        file.setMimeType("image/png");
        file.setFileSize(2048L);
        file.setCreatedAt(now);
        file.setUpdatedAt(now);

        assertEquals("uploads/image.png", file.getPath());
        assertEquals(false, file.getIsText());
        assertArrayEquals(bin, file.getContentBin());
        assertEquals("markdown content here", file.getContentTxt());
        assertEquals("image/png", file.getMimeType());
        assertEquals(2048L, file.getFileSize());
        assertEquals(now, file.getCreatedAt());
        assertEquals(now, file.getUpdatedAt());
    }

    @Test
    void isText_defaultsToFalse() {
        FsFile file = new FsFile();
        // isText defaults to Boolean.FALSE in field declaration
        assertEquals(Boolean.FALSE, file.getIsText());
    }

    @Test
    void pathCanContainUnicode() {
        FsFile file = new FsFile();
        String unicodePath = "papers/烟酰胺文献/烟酰胺在化妆品中的应用_王洪滨.pdf";
        file.setPath(unicodePath);
        assertEquals(unicodePath, file.getPath());
    }

    @Test
    void contentBinCanBeNull() {
        FsFile file = new FsFile();
        file.setContentBin(null);
        assertNull(file.getContentBin());
    }

    @Test
    void contentTxtCanBeNull() {
        FsFile file = new FsFile();
        file.setContentTxt(null);
        assertNull(file.getContentTxt());
    }

    @Test
    void fileSizeCanBeNull() {
        FsFile file = new FsFile();
        // Null file size is allowed
        assertNull(file.getFileSize());
        file.setFileSize(null);
        assertNull(file.getFileSize());
    }

    @Test
    void mimeTypeCommonValues() {
        FsFile pdfFile = new FsFile();
        pdfFile.setMimeType("application/pdf");
        assertEquals("application/pdf", pdfFile.getMimeType());

        FsFile mdFile = new FsFile();
        mdFile.setMimeType("text/markdown");
        assertEquals("text/markdown", mdFile.getMimeType());

        FsFile pngFile = new FsFile();
        pngFile.setMimeType("image/png");
        assertEquals("image/png", pngFile.getMimeType());
    }

    @Test
    void timestampsCanBeSet() {
        FsFile file = new FsFile();
        OffsetDateTime created = OffsetDateTime.now().minusHours(1);
        OffsetDateTime updated = OffsetDateTime.now();

        file.setCreatedAt(created);
        file.setUpdatedAt(updated);

        assertEquals(created, file.getCreatedAt());
        assertEquals(updated, file.getUpdatedAt());
    }
}
