package com.springairag.core.service;

import com.springairag.core.entity.FsFile;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Markdown → HTML Renderer for file system previews.
 *
 * <p>Uses the <a href="https://github.com/commonmark/commonmark-java">commonmark-java</a>
 * library for standards-compliant Markdown parsing and HTML rendering.
 * Image links are rewritten to point to the {@code /files/raw} API endpoint
 * so they are served through the application.
 *
 * <p>Example image path rewriting:
 * {@code ![alt](image.png)} → {@code <img src="/api/v1/rag/files/raw?path=virtualDir/image.png" alt="alt"/>}
 */
@Service
public class MarkdownRendererService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownRendererService.class);

    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .attributeProviderFactory(ctx -> new RewriteImagePathAttributeProvider())
            .build();

    /**
     * Render Markdown content to HTML, rewriting image links to point to the file API.
     *
     * @param markdownContent the Markdown text content of the entry file
     * @param virtualPath     the virtual path of the entry Markdown file (e.g., "papers/论文.md")
     * @return rendered HTML string (HTML fragment, not a full page)
     */
    public String renderToHtml(String markdownContent, String virtualPath) {
        if (markdownContent == null || markdownContent.isBlank()) {
            return "<p><em>Empty content.</em></p>";
        }

        // Determine the virtual directory for rewriting relative image paths
        String virtualDir = virtualPath.contains("/")
                ? virtualPath.substring(0, virtualPath.lastIndexOf('/') + 1)
                : "";

        // Parse Markdown
        Node document = PARSER.parse(markdownContent);

        // Inject virtualDir into the parsing context via a custom attribute
        RewriteImagePathAttributeProvider.setVirtualDir(virtualDir);

        try {
            return RENDERER.render(document);
        } finally {
            // Clear after rendering to avoid leaking state between requests
            RewriteImagePathAttributeProvider.clearVirtualDir();
        }
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

    // ==================== Image Path Rewriting ====================

    /**
     * Custom {@link AttributeProvider} that rewrites image {@code src} attributes.
     *
     * <p>Relative paths (not starting with {@code http://} or {@code https://}) are
     * prefixed with the virtual directory and rewritten to the {@code /files/raw} API endpoint.
     *
     * <p>This approach is thread-safe because the virtual directory is stored in a
     * {@code ThreadLocal} during the rendering of a single document.
     */
    static class RewriteImagePathAttributeProvider implements AttributeProvider {

        private static final ThreadLocal<String> VIRTUAL_DIR = new ThreadLocal<>();

        static void setVirtualDir(String dir) {
            VIRTUAL_DIR.set(dir);
        }

        static void clearVirtualDir() {
            VIRTUAL_DIR.remove();
        }

        private static String virtualDir() {
            return VIRTUAL_DIR.get() != null ? VIRTUAL_DIR.get() : "";
        }

        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attrs) {
            if (node instanceof Image && "img".equals(tagName)) {
                String src = attrs.get("src");
                if (src != null && !src.startsWith("http://") && !src.startsWith("https://")) {
                    // Normalize: remove leading "./"
                    String normalized = src.startsWith("./") ? src.substring(2) : src;

                    // Compute full virtual path
                    String virtualDir = virtualDir();
                    String fullVirtualPath = virtualDir.isEmpty() ? normalized : virtualDir + normalized;

                    // Rewrite to the raw file API endpoint
                    String encodedPath = URLEncoder.encode(fullVirtualPath, StandardCharsets.UTF_8);
                    attrs.put("src", "/api/v1/rag/files/raw?path=" + encodedPath);
                }
            }
        }
    }
}
