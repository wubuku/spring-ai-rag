package com.springairag.core.service.extract;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 文档解析验收语料的确定性构造器。
 *
 * <p>所有二进制夹具在测试运行时生成，避免把不可审阅的 blob 提交进仓库，同时
 * 保证每次内容完全一致。语料覆盖：多栏 PDF、表格 PDF、无文字层扫描件 PDF、
 * DOCX（段落 + 表格）、含脚本/样式噪声的 HTML、Markdown、UTF-8/GBK 纯文本、
 * 超长单行，以及损坏/加密/伪造扩展名等异常文件。
 */
public final class DocumentParsingCorpus {

    private DocumentParsingCorpus() {
    }

    static byte[] multiColumnPdf() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(font(), 12);
                stream.setLeading(20f);
                stream.newLineAtOffset(40, 760);
                stream.showText("LEFT-ALPHA first column");
                stream.newLine();
                stream.showText("LEFT-BETA first column");
                stream.newLine();
                stream.showText("LEFT-GAMMA first column");
                // 从当前文本行起点跳到右栏顶部（相对偏移）。
                stream.newLineAtOffset(280, 40);
                stream.showText("RIGHT-ALPHA second column");
                stream.newLine();
                stream.showText("RIGHT-BETA second column");
                stream.newLine();
                stream.showText("RIGHT-GAMMA second column");
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    static byte[] tablePdf() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(font(), 12);
                stream.setLeading(20f);
                stream.newLineAtOffset(40, 720);
                stream.showText("A1");
                stream.newLineAtOffset(160, 0);
                stream.showText("B1");
                stream.newLineAtOffset(160, 0);
                stream.showText("C1");
                stream.newLineAtOffset(-320, -20);
                stream.showText("A2");
                stream.newLineAtOffset(160, 0);
                stream.showText("B2");
                stream.newLineAtOffset(160, 0);
                stream.showText("C2");
                stream.newLineAtOffset(-320, -20);
                stream.showText("A3");
                stream.newLineAtOffset(160, 0);
                stream.showText("B3");
                stream.newLineAtOffset(160, 0);
                stream.showText("C3");
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /** 图片型扫描件：页面只有图像，没有可抽取的文字层。 */
    static byte[] scannedPdf() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < image.getWidth(); x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    image.setRGB(x, y, (x + y) % 2 == 0 ? 0xFFFFFF : 0x000000);
                }
            }
            PDImageXObject imageObject = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(imageObject, 40, 500, 300, 200);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    static byte[] encryptedPdf() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(font(), 12);
                stream.newLineAtOffset(40, 760);
                stream.showText("Encrypted body");
                stream.endText();
            }
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("owner-pass", "user-pass", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(output);
            return output.toByteArray();
        }
    }

    public static byte[] docx() throws IOException {
        String documentXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>DOCX 第一段</w:t></w:r></w:p>
                    <w:p><w:r><w:t>DOCX 第二段 with code word</w:t></w:r></w:p>
                    <w:tbl>
                      <w:tr>
                        <w:tc><w:p><w:r><w:t>姓名</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>年龄</w:t></w:r></w:p></w:tc>
                      </w:tr>
                      <w:tr>
                        <w:tc><w:p><w:r><w:t>张三</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>30</w:t></w:r></w:p></w:tc>
                      </w:tr>
                    </w:tbl>
                    <w:p><w:r><w:t>DOCX 尾段</w:t></w:r></w:p>
                  </w:body>
                </w:document>
                """;
        return zip(Map.of(
                "[Content_Types].xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                          <Default Extension="xml" ContentType="application/xml"/>
                          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                        </Types>
                        """,
                "word/document.xml", documentXml));
    }

    static byte[] zipWithoutDocumentXml() throws IOException {
        return zip(Map.of("word/other.xml", "<root>no document</root>"));
    }

    static byte[] zip(Map<String, String> entries) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    public static byte[] htmlWithNoise() {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <title>Corpus HTML</title>
                  <style>.hidden { color: red; }</style>
                  <script>var SECRET_NOISE = "should-not-appear";</script>
                </head>
                <body>
                  <h1>HTML 可见标题</h1>
                  <script>IGNORE_ME = 1;</script>
                  <p>这是可见段落。</p>
                  <table>
                    <tr><td>A1</td><td>B1</td></tr>
                    <tr><td>A2</td><td>B2</td></tr>
                  </table>
                  <noscript>NOSCRIPT_NOISE</noscript>
                </body>
                </html>
                """.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] markdown() {
        return """
                # Markdown 语料

                代码块：

                ```java
                int answer = 42;
                System.out.println(answer);
                ```

                | 列一 | 列二 |
                | --- | --- |
                | 值一 | 值二 |
                """.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] gbkText() {
        return "中文编码测试：你好，世界。".getBytes(Charset.forName("GBK"));
    }

    static byte[] utf16Text() {
        String value = "UTF-16 文本";
        byte[] body = value.getBytes(StandardCharsets.UTF_16LE);
        byte[] result = new byte[body.length + 2];
        result[0] = (byte) 0xFF;
        result[1] = (byte) 0xFE;
        System.arraycopy(body, 0, result, 2, body.length);
        return result;
    }

    public static byte[] pngBytes() {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                image.setRGB(x, y, 0x123456);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String longSingleLine() {
        return "超长单行".repeat(5000);
    }

    private static PDType1Font font() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }
}
