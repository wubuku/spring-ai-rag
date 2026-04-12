package com.springairag.core.service;

import com.springairag.core.entity.FsFile;
import com.springairag.core.repository.FsFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown → HTML Renderer for file system previews.
 *
 * <p>Converts Markdown content to HTML and rewrites image paths so they are
 * served through the file download endpoint.
 *
 * <p>Image rewriting: converts relative paths like {@code images/0.png}
 * to {@code /api/v1/rag/files/images/0.png?path=<virtualDir>/images/0.png}.
 */
@Service
@ConditionalOnBean(FsFileRepository.class)
public class MarkdownRendererService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownRendererService.class);

    // Matches Markdown image syntax: ![alt](path)
    private static final Pattern MD_IMAGE = Pattern.compile(
            "!\\[([^\\]]*)\\]\\(([^)]+)\\)",
            Pattern.MULTILINE
    );

    private final PdfImportService pdfImportService;

    public MarkdownRendererService(PdfImportService pdfImportService) {
        this.pdfImportService = pdfImportService;
    }

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

        // Escape HTML special characters in the Markdown source first
        String escaped = escapeHtml(markdownContent);

        // Convert Markdown to HTML (basic conversion; no full CommonMark parser needed)
        String html = markdownToHtml(escaped);

        // Rewrite image paths to point to the file download endpoint
        html = rewriteImagePaths(html, virtualDir);

        return html;
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
                : new String(markdownFile.getContentBin(), java.nio.charset.StandardCharsets.UTF_8);
        return renderToHtml(content, markdownFile.getPath());
    }

    // ==================== Internal ====================

    /**
     * Rewrite Markdown image paths to the file API endpoint.
     *
     * <p>Before: `![alt](image.png)` or `![alt](./subdir/image.png)`
     * After:  `![alt](/api/v1/rag/files/raw?path=virtualDir/image.png)`
     *
     * <p>Absolute URLs (starting with http:// or https://) are left unchanged.
     */
    String rewriteImagePaths(String html, String virtualDir) {
        Matcher m = MD_IMAGE.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String alt = m.group(1);
            String src = m.group(2);

            // Skip absolute URLs
            if (src.startsWith("http://") || src.startsWith("https://")) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }

            // Normalize: remove leading "./" if present
            String normalized = src.startsWith("./") ? src.substring(2) : src;

            // Compute the full virtual path of the image
            String fullVirtualPath;
            if (virtualDir.isEmpty()) {
                fullVirtualPath = normalized;
            } else {
                fullVirtualPath = virtualDir + normalized;
            }

            // URL-encode the path for use as a query parameter value
            String encodedPath = java.net.URLEncoder.encode(fullVirtualPath, java.nio.charset.StandardCharsets.UTF_8);

            String rewritten = String.format(
                    "![%s](/api/v1/rag/files/raw?path=%s)",
                    alt.isEmpty() ? "image" : alt,
                    encodedPath
            );
            m.appendReplacement(sb, Matcher.quoteReplacement(rewritten));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Basic Markdown → HTML conversion.
     *
     * <p>Covers: headings, bold, italic, code blocks, inline code, links, line breaks.
     * A full CommonMark parser is not needed for this use case.
     */
    String markdownToHtml(String text) {
        String html = text;

        // Code blocks (must be before inline code)
        html = html.replaceAll("(?s)```([\\w]*)\\n(.*?)```", "<pre><code class=\"language-$1\">$2</code></pre>");

        // Inline code
        html = html.replaceAll("`([^`]+)`", "<code>$1</code>");

        // Headings (h1–h3)
        html = html.replaceAll("(?m)^### (.+)$", "<h3>$1</h3>");
        html = html.replaceAll("(?m)^## (.+)$", "<h2>$1</h2>");
        html = html.replaceAll("(?m)^# (.+)$", "<h1>$1</h1>");

        // Bold and italic
        html = html.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "<strong><em>$1</em></strong>");
        html = html.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        html = html.replaceAll("\\*(.+?)\\*", "<em>$1</em>");
        html = html.replaceAll("__(.+?)__", "<strong>$1</strong>");
        html = html.replaceAll("_(.+?)_", "<em>$1</em>");

        // Blockquotes
        html = html.replaceAll("(?m)^&gt; (.+)$", "<blockquote>$1</blockquote>");

        // Horizontal rules
        html = html.replaceAll("(?m)^---$", "<hr/>");
        html = html.replaceAll("(?m)^\\*\\*\\*$", "<hr/>");

        // Unordered lists (simple single-level)
        html = html.replaceAll("(?m)^[\\*\\-] (.+)$", "<li>$1</li>");
        // Wrap consecutive <li> in <ul>
        html = html.replaceAll("(<li>.*?</li>\\n?)+", "<ul>$0</ul>");

        // Ordered lists
        html = html.replaceAll("(?m)^\\d+\\. (.+)$", "<li>$1</li>");

        // Links: [text](url) → <a href="url">text</a>
        html = html.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");

        // Line breaks (double newline → paragraph, single newline → <br/>)
        html = html.replaceAll("\n\n+", "</p>\n<p>");
        html = html.replaceAll("(?<!</p>|<br/>)\n(?!<)", "<br/>\n");

        // Wrap in paragraph tags
        if (!html.startsWith("<")) {
            html = "<p>" + html + "</p>";
        }

        return html;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replaceAll("&", "&amp;")
                .replaceAll("<", "&lt;")
                .replaceAll(">", "&gt;")
                .replaceAll("\"", "&quot;");
    }
}
