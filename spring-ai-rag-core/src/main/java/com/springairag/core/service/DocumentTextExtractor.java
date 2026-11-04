package com.springairag.core.service;

import com.springairag.core.exception.DocumentExtractionException;
import com.springairag.core.service.pdf.PdfConversionException;
import com.springairag.core.service.pdf.PdfTextExtractor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文档内容抽取的单一入口。
 *
 * <p>确定性、无模型、无临时文件。对每种格式做显式校验：
 * <ul>
 *   <li>PDF：损坏/加密/无文字层 → 明确 422，绝不返回空文本；</li>
 *   <li>DOCX：校验 ZIP 与 {@code word/document.xml}，保留段落与表格行结构；</li>
 *   <li>HTML：剔除 script/style 等噪声后取正文；</li>
 *   <li>Markdown/纯文本：保留结构；UTF-8 失败时按 GB18030 回退；</li>
 *   <li>伪造扩展名、二进制内容、零字节文件 → 明确 {@code FILE_TYPE_MISMATCH}。</li>
 * </ul>
 *
 * <p>本类不负责写入任何文档或占用配额；抽取失败时上层尚未产生持久化副作用。
 */
public final class DocumentTextExtractor {

    /** 单文件抽取上限，与 PDF 导入默认预算保持一致。 */
    public static final long MAX_CONTENT_BYTES = 20L * 1024 * 1024;
    private static final int MAX_PDF_PAGES = 2000;
    private static final long MAX_UNCOMPRESSED_DOCX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 2000;

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] OLE_MAGIC = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private DocumentTextExtractor() {
    }

    public enum Format {
        PDF, DOCX, HTML, MARKDOWN, TEXT, JSON, XML, CSV, LOG
    }

    public record ExtractedDocument(String title, String text, Format format) {
    }

    public static boolean isSupportedFilename(String filename) {
        return formatOf(filename) != null;
    }

    public static Format formatOf(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String name = filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return switch (name.substring(dot + 1)) {
            case "pdf" -> Format.PDF;
            case "docx" -> Format.DOCX;
            case "html", "htm" -> Format.HTML;
            case "md", "markdown", "mdx" -> Format.MARKDOWN;
            case "txt", "text" -> Format.TEXT;
            case "json" -> Format.JSON;
            case "xml" -> Format.XML;
            case "csv" -> Format.CSV;
            case "log" -> Format.LOG;
            default -> null;
        };
    }

    /**
     * 抽取文件文本。
     *
     * @throws DocumentExtractionException 文件为空、格式不符、编码不支持或内容无法抽取
     * @throws com.springairag.core.exception.RagException 超过大小预算（413）
     */
    public static ExtractedDocument extract(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new DocumentExtractionException(
                    DocumentExtractionException.Reason.EMPTY_FILE,
                    "File is empty (0 bytes); nothing can be extracted");
        }
        if (bytes.length > MAX_CONTENT_BYTES) {
            throw new com.springairag.core.exception.RagException(
                    com.springairag.api.enums.ErrorCode.PAYLOAD_TOO_LARGE,
                    "File exceeds the " + (MAX_CONTENT_BYTES / 1024 / 1024) + " MB extraction limit");
        }
        Format format = formatOf(filename);
        if (format == null) {
            throw new DocumentExtractionException(
                    DocumentExtractionException.Reason.UNSUPPORTED_FORMAT,
                    "Unsupported file format: " + safeName(filename));
        }
        String title = stripExtension(filename);
        try {
            String text = switch (format) {
                case PDF -> pdfText(bytes);
                case DOCX -> docxText(bytes);
                case HTML -> htmlText(bytes);
                case MARKDOWN, TEXT, JSON, XML, CSV, LOG -> decodeText(bytes, filename);
            };
            text = normalize(text);
            if (text.isBlank()) {
                throw new DocumentExtractionException(
                        DocumentExtractionException.Reason.NO_EXTRACTABLE_TEXT,
                        "No extractable text was found in " + safeName(filename));
            }
            return new ExtractedDocument(title, text, format);
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DocumentExtractionException(
                    DocumentExtractionException.Reason.CONVERSION_FAILED,
                    "Failed to extract text from " + safeName(filename), e);
        }
    }

    private static String pdfText(byte[] bytes) {
        if (startsWith(bytes, PDF_MAGIC)) {
            try {
                return PdfTextExtractor.extractText(bytes, MAX_PDF_PAGES);
            } catch (PdfConversionException e) {
                throw switch (e.getReason()) {
                    case ENCRYPTED_PDF -> new DocumentExtractionException(
                            DocumentExtractionException.Reason.ENCRYPTED_DOCUMENT,
                            "PDF is encrypted and text cannot be extracted");
                    case NO_EXTRACTABLE_TEXT -> new DocumentExtractionException(
                            DocumentExtractionException.Reason.NO_EXTRACTABLE_TEXT,
                            "PDF has no extractable text (possibly a scanned image PDF)");
                    case TOO_MANY_PAGES -> new DocumentExtractionException(
                            DocumentExtractionException.Reason.TOO_MANY_PAGES,
                            "PDF exceeds the max-pages limit");
                    case OUTPUT_TOO_LARGE -> new DocumentExtractionException(
                            DocumentExtractionException.Reason.OUTPUT_TOO_LARGE,
                            "PDF extracted text exceeds the output limit");
                    case INVALID_PDF, CONVERSION_FAILED -> new DocumentExtractionException(
                            DocumentExtractionException.Reason.INVALID_PDF,
                            "PDF is corrupt or not readable");
                };
            }
        }
        if (startsWith(bytes, ZIP_MAGIC) || startsWith(bytes, PNG_MAGIC)
                || startsWith(bytes, OLE_MAGIC)) {
            throw new DocumentExtractionException(
                    DocumentExtractionException.Reason.FILE_TYPE_MISMATCH,
                    "File extension .pdf does not match its content");
        }
        throw new DocumentExtractionException(
                DocumentExtractionException.Reason.INVALID_PDF,
                "PDF is corrupt or not readable");
    }

    private static String docxText(byte[] bytes) {
        if (startsWith(bytes, OLE_MAGIC)) {
            throw new DocumentExtractionException(
                    DocumentExtractionException.Reason.ENCRYPTED_DOCUMENT,
                    "DOCX is encrypted or uses an unsupported binary container");
        }
        if (!startsWith(bytes, ZIP_MAGIC)) {
            throw new DocumentExtractionException(
                    DocumentExtractionException.Reason.FILE_TYPE_MISMATCH,
                    "File extension .docx does not match its content");
        }
        byte[] documentXml = readZipEntry(bytes, "word/document.xml");
        if (documentXml == null) {
            throw new DocumentExtractionException(
                    DocumentExtractionException.Reason.INVALID_DOCX,
                    "DOCX is corrupt or missing word/document.xml");
        }
        String text = extractDocxXml(documentXml);
        if (text.isBlank()) {
            throw new DocumentExtractionException(
                    DocumentExtractionException.Reason.NO_EXTRACTABLE_TEXT,
                    "DOCX contains no extractable text");
        }
        return text;
    }

    private static byte[] readZipEntry(byte[] bytes, String wantedName) {
        long totalUncompressed = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ZIP_ENTRIES) {
                    throw new DocumentExtractionException(
                            DocumentExtractionException.Reason.INVALID_DOCX,
                            "DOCX contains too many entries");
                }
                if (!entry.getName().equals(wantedName)) {
                    continue;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    totalUncompressed += read;
                    if (totalUncompressed > MAX_UNCOMPRESSED_DOCX_BYTES) {
                        throw new DocumentExtractionException(
                                DocumentExtractionException.Reason.INVALID_DOCX,
                                "DOCX uncompressed content exceeds the limit");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } catch (IOException e) {
            throw new DocumentExtractionException(
                    DocumentExtractionException.Reason.INVALID_DOCX,
                    "DOCX is corrupt and cannot be read", e);
        }
        return null;
    }

    private static String extractDocxXml(byte[] xmlBytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            org.w3c.dom.Document xml = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xmlBytes));
            org.w3c.dom.Element root = xml.getDocumentElement();
            org.w3c.dom.Element body = firstChild(root, "body");
            if (body == null) {
                return "";
            }
            StringBuilder output = new StringBuilder();
            appendDocxBlocks(body, output);
            return output.toString();
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentExtractionException(
                    DocumentExtractionException.Reason.INVALID_DOCX,
                    "DOCX XML is malformed", e);
        }
    }

    private static void appendDocxBlocks(org.w3c.dom.Element parent, StringBuilder output) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = localName(node);
            switch (name) {
                case "p" -> appendParagraph((org.w3c.dom.Element) node, output);
                case "tbl" -> appendTable((org.w3c.dom.Element) node, output);
                case "sectPr" -> {
                    // 页面设置，不参与文本。
                }
                default -> appendDocxBlocks((org.w3c.dom.Element) node, output);
            }
        }
    }

    private static void appendParagraph(org.w3c.dom.Element paragraph, StringBuilder output) {
        StringBuilder line = new StringBuilder();
        appendInlineText(paragraph, line);
        String text = line.toString().replaceAll("[ \\t]+\\n", "\n").strip();
        if (!text.isEmpty()) {
            output.append(text).append('\n');
        }
    }

    private static void appendInlineText(org.w3c.dom.Element parent, StringBuilder output) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.TEXT_NODE) {
                continue;
            }
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = localName(node);
            switch (name) {
                case "t" -> output.append(node.getTextContent());
                case "tab" -> output.append('\t');
                case "br", "cr" -> output.append('\n');
                case "delText", "drawing", "object" -> {
                    // 修订删除内容与嵌入对象不出现在纯文本中。
                }
                default -> appendInlineText((org.w3c.dom.Element) node, output);
            }
        }
    }

    private static void appendTable(org.w3c.dom.Element table, StringBuilder output) {
        List<org.w3c.dom.Element> rows = childElements(table, "tr");
        int separatorWidth = 0;
        for (org.w3c.dom.Element row : rows) {
            List<String> cells = new ArrayList<>();
            for (org.w3c.dom.Element cell : childElements(row, "tc")) {
                cells.add(tableCellText(cell));
            }
            if (cells.isEmpty()) {
                continue;
            }
            output.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (separatorWidth == 0) {
                separatorWidth = cells.size();
                List<String> separators = new ArrayList<>();
                for (int i = 0; i < separatorWidth; i++) {
                    separators.add("---");
                }
                output.append("| ").append(String.join(" | ", separators)).append(" |\n");
            }
        }
        if (separatorWidth > 0) {
            output.append('\n');
        }
    }

    private static String tableCellText(org.w3c.dom.Element cell) {
        StringBuilder text = new StringBuilder();
        for (org.w3c.dom.Element paragraph : childElements(cell, "p")) {
            StringBuilder line = new StringBuilder();
            appendInlineText(paragraph, line);
            String value = line.toString().strip();
            if (!value.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(value.replace('\n', ' ').replace('|', '/'));
            }
        }
        return text.toString();
    }

    private static List<org.w3c.dom.Element> childElements(org.w3c.dom.Element parent,
                                                           String localName) {
        List<org.w3c.dom.Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName(node).equals(localName)) {
                result.add((org.w3c.dom.Element) node);
            }
        }
        return result;
    }

    private static org.w3c.dom.Element firstChild(org.w3c.dom.Element parent, String localName) {
        List<org.w3c.dom.Element> children = childElements(parent, localName);
        return children.isEmpty() ? null : children.get(0);
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null) {
            return local;
        }
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static String htmlText(byte[] bytes) {
        String html = decodeText(bytes, "html");
        Document document = Jsoup.parse(html);
        document.select("script, style, noscript, template, iframe, svg, canvas").remove();
        String text = document.body() != null ? document.body().wholeText() : "";
        if (text.isBlank()) {
            throw new DocumentExtractionException(
                    DocumentExtractionException.Reason.NO_EXTRACTABLE_TEXT,
                    "HTML contains no visible text after removing scripts and styles");
        }
        return text;
    }

    private static String decodeText(byte[] bytes, String filename) {
        if (startsWith(bytes, PNG_MAGIC) || startsWith(bytes, PDF_MAGIC)
                || startsWith(bytes, ZIP_MAGIC) || startsWith(bytes, OLE_MAGIC)
                || startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})
                || startsWith(bytes, new byte[]{'G', 'I', 'F', '8'})) {
            throw new DocumentExtractionException(
                    DocumentExtractionException.Reason.FILE_TYPE_MISMATCH,
                    "File content is binary and does not match text extension of "
                            + safeName(filename));
        }
        byte[] content = bytes;
        Charset charset = StandardCharsets.UTF_8;
        boolean hasBom = false;
        if (startsWith(bytes, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF})) {
            content = slice(bytes, 3);
            charset = StandardCharsets.UTF_8;
            hasBom = true;
        } else if (startsWith(bytes, new byte[]{(byte) 0xFE, (byte) 0xFF})) {
            charset = StandardCharsets.UTF_16BE;
            content = slice(bytes, 2);
            hasBom = true;
        } else if (startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xFE})) {
            charset = StandardCharsets.UTF_16LE;
            content = slice(bytes, 2);
            hasBom = true;
        }
        // UTF-16 文本天然含 0x00 高位字节，有 BOM 时不能按二进制样本误判。
        if (!hasBom && containsBinaryControlBytes(bytes)) {
            throw new DocumentExtractionException(
                    DocumentExtractionException.Reason.FILE_TYPE_MISMATCH,
                    "File content contains binary bytes and cannot be decoded as text");
        }
        String decoded = strictDecode(content, charset);
        if (decoded != null) {
            return decoded;
        }
        // UTF-8 失败后按中文常见编码回退。GB18030 是 GBK/GB2312 的超集。
        String gb = strictDecode(content, Charset.forName("GB18030"));
        if (gb != null) {
            return gb;
        }
        throw new DocumentExtractionException(
                DocumentExtractionException.Reason.TEXT_ENCODING_UNSUPPORTED,
                "Text encoding is neither UTF-8 nor GBK/GB18030");
    }

    private static String strictDecode(byte[] bytes, Charset charset) {
        try {
            CharBuffer decoded = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static boolean containsBinaryControlBytes(byte[] bytes) {
        int suspicious = 0;
        int sample = Math.min(bytes.length, 4096);
        for (int i = 0; i < sample; i++) {
            int value = bytes[i] & 0xFF;
            if (value == 0) {
                return true;
            }
            boolean allowedControl = value == '\n' || value == '\r' || value == '\t' || value == 0x0C;
            if (value < 0x20 && !allowedControl) {
                suspicious++;
            }
        }
        return suspicious > Math.max(2, sample / 100);
    }

    private static String normalize(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        normalized = normalized.replace("\u0000", "");
        normalized = normalized.replaceAll("[\\u0001-\\u0008\\u000B\\u000E-\\u001F]", "");
        normalized = normalized.replaceAll("\n{3,}", "\n\n");
        normalized = normalized.replaceAll("[ \\t]+\\n", "\n");
        return normalized.strip();
    }

    private static String stripExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "document";
        }
        String base = filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private static String safeName(String filename) {
        return filename == null || filename.isBlank() ? "<unnamed>" : stripExtension(filename);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] slice(byte[] bytes, int from) {
        byte[] result = new byte[bytes.length - from];
        System.arraycopy(bytes, from, result, 0, result.length);
        return result;
    }
}
