package com.springairag.core.service.extract;

import com.springairag.api.enums.ErrorCode;
import com.springairag.api.enums.FailureClass;
import com.springairag.core.exception.DocumentExtractionException;
import com.springairag.core.exception.RagException;
import com.springairag.core.service.DocumentTextExtractor;
import com.springairag.documents.chunk.BoundedTextChunker;
import com.springairag.documents.chunk.ChunkingResult;
import com.springairag.documents.chunk.TextChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档解析质量与格式覆盖的确定性验收。
 *
 * <p>不依赖真实模型：抽取是纯函数，切分使用真实 {@link HierarchicalTextChunker}，
 * 向量部分用固定哈希生成的假 embedding 验证每个 chunk 可嵌入且维度稳定。
 * 语料由 {@link DocumentParsingCorpus} 在运行时确定性生成。
 */
class DocumentParsingCorpusAcceptanceTest {

    @Test
    @DisplayName("多栏 PDF：两栏内容完整，且按左栏整段、右栏整段输出")
    void multiColumnPdf_readsColumnByColumn() throws Exception {
        DocumentTextExtractor.ExtractedDocument doc = DocumentTextExtractor.extract(
                "multi-column.pdf", DocumentParsingCorpus.multiColumnPdf());
        String text = doc.text();

        assertTrue(text.contains("LEFT-ALPHA first column"));
        assertTrue(text.contains("LEFT-GAMMA first column"));
        assertTrue(text.contains("RIGHT-ALPHA second column"));
        assertTrue(text.contains("RIGHT-GAMMA second column"));
        // 左栏整体先于右栏，避免逐行交错。
        assertTrue(text.indexOf("LEFT-GAMMA") < text.indexOf("RIGHT-ALPHA"));
    }

    @Test
    @DisplayName("表格 PDF：同一行的单元格保持在同一输出行，不跨行串列")
    void tablePdf_keepsRowsTogether() throws Exception {
        DocumentTextExtractor.ExtractedDocument doc = DocumentTextExtractor.extract(
                "table.pdf", DocumentParsingCorpus.tablePdf());

        for (String[] row : List.of(
                new String[]{"A1", "B1", "C1"},
                new String[]{"A2", "B2", "C2"},
                new String[]{"A3", "B3", "C3"})) {
            String line = lineContaining(doc.text(), row[0]);
            assertTrue(line.contains(row[1]), "row merged incorrectly: " + line);
            assertTrue(line.contains(row[2]), "row merged incorrectly: " + line);
        }
    }

    @Test
    @DisplayName("无文字层扫描件 PDF：明确报错，不生成空文档")
    void scannedPdf_reportsNoExtractableText() throws Exception {
        DocumentExtractionException failure = assertThrows(
                DocumentExtractionException.class,
                () -> DocumentTextExtractor.extract(
                        "scanned.pdf", DocumentParsingCorpus.scannedPdf()));
        assertEquals(DocumentExtractionException.Reason.NO_EXTRACTABLE_TEXT,
                failure.getReason());
        assertEquals(ErrorCode.DOCUMENT_EXTRACTION_FAILED, failure.getErrorCodeEnum());
        assertEquals(422, failure.getErrorCodeEnum().getHttpStatus());
        assertEquals(FailureClass.CLIENT_REQUEST,
                FailureClass.from(422, failure.getErrorCodeEnum().getCode()));
    }

    @Test
    @DisplayName("DOCX：段落完整，表格保留行列结构")
    void docx_keepsParagraphsAndTableStructure() throws Exception {
        DocumentTextExtractor.ExtractedDocument doc = DocumentTextExtractor.extract(
                "corpus.docx", DocumentParsingCorpus.docx());

        assertTrue(doc.text().contains("DOCX 第一段"));
        assertTrue(doc.text().contains("DOCX 第二段 with code word"));
        assertTrue(doc.text().contains("DOCX 尾段"));
        assertTrue(doc.text().contains("| 姓名 | 年龄 |"));
        assertTrue(doc.text().contains("| --- | --- |"));
        assertTrue(doc.text().contains("| 张三 | 30 |"));
    }

    @Test
    @DisplayName("HTML：剔除 script/style/noscript 噪声，保留可见正文")
    void html_removesScriptAndStyleNoise() throws Exception {
        DocumentTextExtractor.ExtractedDocument doc = DocumentTextExtractor.extract(
                "corpus.html", DocumentParsingCorpus.htmlWithNoise());

        assertTrue(doc.text().contains("HTML 可见标题"));
        assertTrue(doc.text().contains("这是可见段落。"));
        assertTrue(doc.text().contains("A1"));
        assertTrue(doc.text().contains("B2"));
        assertFalse(doc.text().contains("IGNORE_ME"));
        assertFalse(doc.text().contains("SECRET_NOISE"));
        assertFalse(doc.text().contains("color: red"));
        assertFalse(doc.text().contains("NOSCRIPT_NOISE"));
    }

    @Test
    @DisplayName("Markdown：代码块与表格原样保留")
    void markdown_preservesCodeBlocksAndTables() throws Exception {
        DocumentTextExtractor.ExtractedDocument doc = DocumentTextExtractor.extract(
                "corpus.md", DocumentParsingCorpus.markdown());

        assertTrue(doc.text().contains("```java"));
        assertTrue(doc.text().contains("int answer = 42;"));
        assertTrue(doc.text().contains("| 列一 | 列二 |"));
        assertTrue(doc.text().contains("| 值一 | 值二 |"));
    }

    @Test
    @DisplayName("纯文本：UTF-8 与 GBK 都能正确解码")
    void plainText_decodesUtf8AndGbk() throws Exception {
        String expected = "中文编码测试：你好，世界。";
        DocumentTextExtractor.ExtractedDocument utf8 = DocumentTextExtractor.extract(
                "utf8.txt", expected.getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, utf8.text());

        DocumentTextExtractor.ExtractedDocument gbk = DocumentTextExtractor.extract(
                "gbk.txt", DocumentParsingCorpus.gbkText());
        assertEquals(expected, gbk.text());

        DocumentTextExtractor.ExtractedDocument utf16 = DocumentTextExtractor.extract(
                "utf16.txt", DocumentParsingCorpus.utf16Text());
        assertEquals("UTF-16 文本", utf16.text());
    }

    @Test
    @DisplayName("超长单行：切分后每个 chunk 有界，且都可生成确定性向量")
    void longSingleLine_isChunkedAndEmbeddable() throws Exception {
        String longLine = DocumentParsingCorpus.longSingleLine();
        DocumentTextExtractor.ExtractedDocument doc =
                DocumentTextExtractor.extract("long.txt", longLine.getBytes(StandardCharsets.UTF_8));

        int maxChunk = 400;
        // 默认 profile 是有界分块（BOUNDED_V3）；它必须能切开无标点长行。
        ChunkingResult result = new BoundedTextChunker(maxChunk, 10, 50, 1_000_000, 20_000)
                .split(doc.text());
        List<TextChunk> chunks = result.chunks();
        assertTrue(chunks.size() > 1, "long single line must be split");
        for (TextChunk chunk : chunks) {
            assertFalse(chunk.text().isBlank());
            assertTrue(chunk.text().length() <= maxChunk,
                    "chunk exceeds budget: " + chunk.text().length());
            double[] vector = deterministicFakeEmbedding(chunk.text(), 1024);
            assertEquals(1024, vector.length);
            for (double value : vector) {
                assertTrue(Double.isFinite(value));
            }
        }
    }

    @Test
    @DisplayName("零字节、伪造扩展名、二进制内容都给出明确错误")
    void malformedFiles_haveExplicitReasons() throws Exception {
        assertReason(DocumentExtractionException.Reason.EMPTY_FILE,
                () -> DocumentTextExtractor.extract("empty.txt", new byte[0]));
        assertReason(DocumentExtractionException.Reason.FILE_TYPE_MISMATCH,
                () -> DocumentTextExtractor.extract("fake.docx", DocumentParsingCorpus.pngBytes()));
        assertReason(DocumentExtractionException.Reason.FILE_TYPE_MISMATCH,
                () -> DocumentTextExtractor.extract("fake.md", DocumentParsingCorpus.pngBytes()));
        assertReason(DocumentExtractionException.Reason.FILE_TYPE_MISMATCH,
                () -> DocumentTextExtractor.extract("fake.pdf", DocumentParsingCorpus.zip(
                        java.util.Map.of("word/document.xml", "<root/>"))));
        assertReason(DocumentExtractionException.Reason.INVALID_DOCX,
                () -> DocumentTextExtractor.extract("fake.docx",
                        DocumentParsingCorpus.zipWithoutDocumentXml()));
    }

    @Test
    @DisplayName("损坏与加密 PDF 都返回 422 语义，而不是 500")
    void corruptAndEncryptedPdf_haveClientRequestSemantics() throws Exception {
        DocumentExtractionException corrupt = assertThrows(
                DocumentExtractionException.class,
                () -> DocumentTextExtractor.extract(
                        "corrupt.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8)));
        assertEquals(DocumentExtractionException.Reason.INVALID_PDF, corrupt.getReason());

        DocumentExtractionException encrypted = assertThrows(
                DocumentExtractionException.class,
                () -> DocumentTextExtractor.extract(
                        "encrypted.pdf", DocumentParsingCorpus.encryptedPdf()));
        assertEquals(DocumentExtractionException.Reason.ENCRYPTED_DOCUMENT,
                encrypted.getReason());
    }

    @Test
    @DisplayName("超过抽取上限的文件返回 413 语义")
    void oversizeFile_mapsToPayloadTooLarge() {
        byte[] oversize = new byte[(int) DocumentTextExtractor.MAX_CONTENT_BYTES + 1];
        RagException failure = assertThrows(RagException.class,
                () -> DocumentTextExtractor.extract("oversize.txt", oversize));
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, failure.getErrorCodeEnum());
        assertEquals(413, failure.getErrorCodeEnum().getHttpStatus());
    }

    private static String lineContaining(String text, String token) {
        return text.lines().filter(line -> line.contains(token)).findFirst()
                .orElseThrow(() -> new AssertionError("missing token: " + token));
    }

    private static void assertReason(DocumentExtractionException.Reason expected,
                                     Executable invocation) {
        DocumentExtractionException failure =
                assertThrows(DocumentExtractionException.class, invocation);
        assertEquals(expected, failure.getReason());
    }

    /** 固定哈希假 embedding：同一文本永远得到同一向量，不依赖任何模型。 */
    private static double[] deterministicFakeEmbedding(String text, int dimensions) {
        double[] vector = new double[dimensions];
        int seed = text.hashCode();
        for (int i = 0; i < dimensions; i++) {
            seed = seed * 31 + i;
            vector[i] = ((seed & 0xFF) - 128) / 128.0;
        }
        return vector;
    }
}
