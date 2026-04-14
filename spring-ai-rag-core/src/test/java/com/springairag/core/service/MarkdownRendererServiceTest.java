package com.springairag.core.service;

import com.springairag.core.entity.FsFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MarkdownRendererService.
 * Tests Markdown-to-HTML rendering with relative image paths preserved.
 */
@DisplayName("MarkdownRendererService Tests")
class MarkdownRendererServiceTest {

    private final MarkdownRendererService service = new MarkdownRendererService();

    // ==================== renderToHtml(String, String) Tests ====================

    @Nested
    @DisplayName("renderToHtml(String, String)")
    class RenderToHtmlString {

        @Test
        @DisplayName("returns empty paragraph for null content")
        void nullContent_returnsEmptyParagraph() {
            String result = service.renderToHtml(null, "test.md");
            assertEquals("<p><em>Empty content.</em></p>", result);
        }

        @Test
        @DisplayName("returns empty paragraph for blank content")
        void blankContent_returnsEmptyParagraph() {
            String result = service.renderToHtml("   ", "test.md");
            assertEquals("<p><em>Empty content.</em></p>", result);
        }

        @Test
        @DisplayName("returns empty paragraph for empty content")
        void emptyContent_returnsEmptyParagraph() {
            String result = service.renderToHtml("", "test.md");
            assertEquals("<p><em>Empty content.</em></p>", result);
        }

        @Test
        @DisplayName("renders simple markdown heading")
        void simpleHeading_rendersCorrectly() {
            String result = service.renderToHtml("# Hello World", "test.md");
            assertTrue(result.contains("<h1>"));
            assertTrue(result.contains("Hello World"));
        }

        @Test
        @DisplayName("renders paragraph with content")
        void paragraph_rendersCorrectly() {
            String result = service.renderToHtml("This is a test.", "test.md");
            assertTrue(result.contains("This is a test"));
        }

        @Test
        @DisplayName("preserves relative image paths")
        void relativeImagePath_preserved() {
            String markdown = "![alt text](image.png)";
            String result = service.renderToHtml(markdown, "docs/readme.md");

            // Image src should remain as relative path (not rewritten)
            assertTrue(result.contains("<img"));
            assertTrue(result.contains("src=\"image.png\""));
        }

        @Test
        @DisplayName("preserves relative image paths with ./ prefix")
        void relativeImageWithDotSlash_preserved() {
            String markdown = "![alt](./photo.jpg)";
            String result = service.renderToHtml(markdown, "notes.md");

            assertTrue(result.contains("<img"));
            assertTrue(result.contains("src=\"./photo.jpg\""));
        }

        @Test
        @DisplayName("preserves absolute HTTP image URLs")
        void absoluteHttpUrl_preserved() {
            String markdown = "![img](http://example.com/pic.png)";
            String result = service.renderToHtml(markdown, "test.md");

            assertTrue(result.contains("http://example.com/pic.png"));
        }

        @Test
        @DisplayName("preserves absolute HTTPS image URLs")
        void absoluteHttpsUrl_preserved() {
            String markdown = "![img](https://cdn.example.com/logo.png)";
            String result = service.renderToHtml(markdown, "test.md");

            assertTrue(result.contains("https://cdn.example.com/logo.png"));
        }

        @Test
        @DisplayName("handles markdown without images")
        void noImages_rendersNormally() {
            String markdown = "**Bold** and *italic* text.";
            String result = service.renderToHtml(markdown, "test.md");

            assertTrue(result.contains("<strong>Bold</strong>") || result.contains("Bold"));
        }

        @Test
        @DisplayName("handles code blocks")
        void codeBlock_rendersCorrectly() {
            String markdown = "```java\nSystem.out.println(\"hi\");\n```";
            String result = service.renderToHtml(markdown, "test.md");

            assertTrue(result.contains("System.out.println"));
        }
    }

    // ==================== renderToHtml(FsFile) Tests ====================

    @Nested
    @DisplayName("renderToHtml(FsFile)")
    class RenderToHtmlFsFile {

        @Test
        @DisplayName("returns file-not-found message for null file")
        void nullFile_returnsNotFound() {
            String result = service.renderToHtml((FsFile) null);
            assertEquals("<p><em>File not found.</em></p>", result);
        }

        @Test
        @DisplayName("renders file content from text field")
        void textContent_rendered() {
            FsFile file = new FsFile();
            file.setPath("readme.md");
            file.setContentTxt("# Project Readme\n\nWelcome.");

            String result = service.renderToHtml(file);

            assertTrue(result.contains("<h1>"));
            assertTrue(result.contains("Project Readme"));
        }

        @Test
        @DisplayName("renders file content from binary field when text is null")
        void binaryContent_rendered() {
            FsFile file = new FsFile();
            file.setPath("readme.md");
            file.setContentTxt(null);
            file.setContentBin("# From Binary\n\nContent.".getBytes(StandardCharsets.UTF_8));

            String result = service.renderToHtml(file);

            assertTrue(result.contains("From Binary"));
        }
    }
}