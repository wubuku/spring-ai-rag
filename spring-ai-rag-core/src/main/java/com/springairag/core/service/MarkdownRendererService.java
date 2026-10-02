package com.springairag.core.service;

import com.springairag.core.entity.FsFile;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
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
 *
 * <p><b>Sanitisation:</b> commonmark-java passes raw HTML found in the Markdown
 * source straight through — {@code <script>}, {@code onerror=} and
 * {@code javascript:} URLs all reach the output verbatim. Every consumer of this
 * service puts the result on a page served from this application's own origin
 * ({@code FilePreview} injects it with {@code dangerouslySetInnerHTML}), so an
 * unsanitised render is a stored XSS: anyone who can write into the file store
 * can run script in every reader's session. The output is therefore cleaned
 * against {@link #PREVIEW_SAFELIST} before it is returned.
 */
@Service
public class MarkdownRendererService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownRendererService.class);

    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().build();

    /**
     * What a preview is allowed to contain.
     *
     * <p>{@code relaxed()} plus the block elements Markdown actually produces.
     * Deliberately absent: {@code script}, {@code style}, {@code iframe},
     * {@code object}, {@code embed}, {@code form} and every {@code on*}
     * attribute — the last because {@code Safelist} is an allow-list, so an
     * event handler is not something that has to be enumerated to be excluded.
     *
     * <p>Relative {@code img src} survives on purpose: the preview page resolves
     * images through a {@code <base>} tag, and jsoup keeps relative URLs when
     * {@code preserveRelativeLinks} is on. That flag alone is not enough —
     * jsoup 1.19.1 also drops a relative URL when the base URI is empty, and
     * rewrites it to an absolute one when the base is set and the flag is off.
     * Absolute base + {@code preserveRelativeLinks} is the combination that
     * leaves the path exactly as the Markdown wrote it.
     *
     * <p>The protocol lists are explicit so {@code javascript:} and
     * {@code data:} are dropped from {@code href} and {@code src} alike.
     */
    private static final Safelist PREVIEW_SAFELIST = Safelist.relaxed()
            .preserveRelativeLinks(true)
            .addTags("img", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "del", "s",
                    "table", "thead", "tbody", "tfoot", "tr", "th", "td", "span", "div")
            .addAttributes("img", "src", "alt", "title", "width", "height")
            .addAttributes("td", "colspan", "rowspan", "align")
            .addAttributes("th", "colspan", "rowspan", "align", "scope")
            .addAttributes("a", "title")
            .addAttributes("code", "class")
            .addAttributes("span", "class")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https");

    /**
     * jsoup needs an absolute base URI to keep relative links at all, but no URL
     * should ever be resolved against it — {@code preserveRelativeLinks} is on,
     * so the value is only there to satisfy that requirement.
     *
     * <p>The unresolvable host is the point: if a future jsoup version ever does
     * rewrite a relative link against the base, the preview would silently point
     * at a dead host, and this string is what would show up. A test asserts it
     * never reaches the output, so that change fails a test instead of shipping.
     */
    private static final String SANITIZER_BASE_URI = "https://preview-base.invalid/";

    private static final Document.OutputSettings SANITIZER_OUTPUT =
            new Document.OutputSettings().prettyPrint(false);

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
        String html = RENDERER.render(document);

        // The renderer emits whatever raw HTML the source contained; clean it
        // before it can reach a page on this origin. See the class comment.
        String safeHtml = Jsoup.clean(html, SANITIZER_BASE_URI, PREVIEW_SAFELIST, SANITIZER_OUTPUT);
        if (!safeHtml.equals(html)) {
            log.warn("Markdown preview at {} contained markup removed by sanitisation ({} -> {} chars)",
                    virtualPath, html.length(), safeHtml.length());
        }
        return safeHtml;
    }

    /**
     * Render an FsFile's Markdown content to HTML.
     *
     * <p>Content selection logic:
     * <ul>
     *   <li>For text-based files ({@code isText=true}): always uses {@code contentTxt},
     *       treating a null value as empty content</li>
     *   <li>For binary files ({@code isText=false} or null): uses {@code contentTxt} if available,
     *       otherwise falls back to {@code contentBin}</li>
     * </ul>
     *
     * @param markdownFile the file to render (may be null)
     * @return rendered HTML string, or a placeholder message if the file is null or empty
     */
    public String renderToHtml(FsFile markdownFile) {
        if (markdownFile == null) {
            return "<p><em>File not found.</em></p>";
        }

        String content;
        if (Boolean.TRUE.equals(markdownFile.getIsText())) {
            // Text-based file: contentTxt must be present
            // Null contentTxt means empty content for a text-marked file
            content = markdownFile.getContentTxt();
        } else {
            // Binary or unspecified: prefer text field, fall back to binary
            if (markdownFile.getContentTxt() != null) {
                content = markdownFile.getContentTxt();
            } else if (markdownFile.getContentBin() != null) {
                content = new String(markdownFile.getContentBin(), StandardCharsets.UTF_8);
            } else {
                content = null;
            }
        }

        return renderToHtml(content, markdownFile.getPath());
    }
}