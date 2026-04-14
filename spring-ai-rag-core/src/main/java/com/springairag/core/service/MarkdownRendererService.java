package com.springairag.core.service;

import com.springairag.core.entity.FsFile;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Markdown → HTML Renderer for file system previews.
 *
 * <p>Uses the <a href="https://github.com/commonmark/commonmark-java">commonmark-java</a>
 * library for standards-compliant Markdown parsing and HTML rendering.
 *
 * <p><b>Image path handling:</b> Image links in Markdown are kept as relative paths.
 * The browser resolves them relative to the page URL, so no path rewriting is needed.
 * For example, if the preview page is at {@code /preview/{uuid}/default.html} and the
 * Markdown contains {@code ![img](image.png)}, the browser will request
 * {@code /preview/{uuid}/image.png} which maps to our preview endpoint.
 *
 * <p>Absolute HTTP(S) URLs are preserved as-is.
 */
@Service
public class MarkdownRendererService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownRendererService.class);

    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().build();

    /**
     * Render Markdown content to HTML.
     *
     * <p>Image paths are preserved as relative paths - no rewriting is done.
     * The browser will resolve them relative to the page URL.
     *
     * @param markdownContent the Markdown text content
     * @param virtualPath     the virtual path of the entry Markdown file (e.g., "{uuid}/default.md")
     *                        This is used to derive the base URL for relative image resolution.
     * @return rendered HTML string (HTML fragment, not a full page)
     */
    public String renderToHtml(String markdownContent, String virtualPath) {
        if (markdownContent == null || markdownContent.isBlank()) {
            return "<p><em>Empty content.</em></p>";
        }

        // Parse Markdown
        Node document = PARSER.parse(markdownContent);

        // Render to HTML - no image path rewriting needed
        // Relative image paths will be resolved by the browser
        return RENDERER.render(document);
    }

    /**
     * Render an FsFile's Markdown content to HTML.
     */
    public String renderToHtml(FsFile markdownFile) {
        if (markdownFile == null) {
            return "<p><em>File not found.</em></p>";
        }
        String content = markdownFile.getContentTxt() != null
                ? markdownFile.getContentTxt()
                : new String(markdownFile.getContentBin(), StandardCharsets.UTF_8);
        return renderToHtml(content, markdownFile.getPath());
    }
}