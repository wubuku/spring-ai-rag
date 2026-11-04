package com.springairag.core.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.service.DocumentTextExtractor;
import com.springairag.core.service.extract.DocumentParsingCorpus;
import com.springairag.documents.chunk.BoundedTextChunker;
import com.springairag.documents.chunk.ChunkDiagnostics;
import com.springairag.documents.chunk.ChunkingResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析 / 分块 / 检索的确定性质量门禁（可进 {@code mvn test}）。
 *
 * <p>不依赖真实模型、数据库或 Docker：语料由 {@link DocumentParsingCorpus} 在
 * 运行时确定生成，向量由测试内的特征哈希假 embedding 计算，排序使用
 * {@code score desc → documentId → chunkIndex} 的稳定 tie-break。门禁度量
 * Recall@K、MRR、nDCG@K，并额外断言分块质量、元数据可用性与两次索引完全一致。
 *
 * <p>阈值、查询集与语料版本固定在
 * {@code src/test/resources/retrieval/parsing-retrieval-quality-gate-v1.json}；
 * 阈值来源与余量见 {@code docs/quality-defaults-zh-CN.md} 的「解析与分块检索质量门禁」。
 */
class ParsingRetrievalQualityGateTest {

    private static final String GOLDENSET =
            "/retrieval/parsing-retrieval-quality-gate-v1.json";
    private static final int DIMENSIONS = 1024;
    private static final double EPSILON = 1.0e-12;

    private static final Comparator<ScoredChunk> RANK_ORDER = Comparator
            .comparingDouble(ScoredChunk::score).reversed()
            .thenComparing(scored -> scored.chunk().documentId())
            .thenComparingInt(scored -> scored.chunk().chunkIndex());

    private static Spec spec;
    private static CorpusIndex index;

    @BeforeAll
    static void loadCorpus() throws Exception {
        spec = Spec.load();
        index = buildIndex(spec);
    }

    @Test
    @DisplayName("检索质量门禁：Recall@K / MRR / nDCG@K 达到落档阈值")
    void retrievalQualityMeetsGate() {
        MetricsResult metrics = evaluate(spec, index.chunks(), false);
        System.out.println("[parsing-retrieval-gate] recall@"
                + spec.metrics().recallK() + "=" + metrics.recallAtK()
                + " mrr=" + metrics.mrr()
                + " nDCG@" + spec.metrics().ndcgK() + "=" + metrics.ndcgAtK());

        assertTrue(metrics.recallAtK() >= spec.thresholds().recallAtK(),
                () -> "Recall@" + spec.metrics().recallK() + " regressed: "
                        + metrics.recallAtK() + " < " + spec.thresholds().recallAtK());
        assertTrue(metrics.mrr() >= spec.thresholds().mrr(),
                () -> "MRR regressed: " + metrics.mrr()
                        + " < " + spec.thresholds().mrr());
        assertTrue(metrics.ndcgAtK() >= spec.thresholds().ndcgAtK(),
                () -> "nDCG@" + spec.metrics().ndcgK() + " regressed: "
                        + metrics.ndcgAtK() + " < " + spec.thresholds().ndcgAtK());
    }

    @Test
    @DisplayName("分块质量：无空 chunk、无超长 chunk、覆盖完整、诊断无违规")
    void chunkQualityIsBoundedAndComplete() {
        int size = spec.chunking().size();
        for (DocumentSpec document : spec.documents()) {
            ChunkingResult result = index.chunkings().get(document.documentId());
            ChunkDiagnostics diagnostics = result.diagnostics();
            assertTrue(diagnostics.valid(),
                    () -> document.documentId() + " diagnostics invalid: "
                            + diagnostics.violations());
            assertTrue(diagnostics.violations().isEmpty(),
                    () -> document.documentId() + " violations: "
                            + diagnostics.violations());
            assertTrue(diagnostics.maxPayloadChars() <= size,
                    () -> document.documentId() + " payload exceeds budget: "
                            + diagnostics.maxPayloadChars());
            assertEquals(countNonWhitespace(index.texts().get(document.documentId())),
                    diagnostics.coveredNonWhitespaceChars(),
                    () -> document.documentId() + " lost source coverage");

            List<ChunkRecord> chunks = chunksOf(document.documentId());
            assertFalse(chunks.isEmpty(), document.documentId() + " produced no chunk");
            for (ChunkRecord chunk : chunks) {
                assertFalse(chunk.text().isBlank(),
                        () -> document.documentId() + " produced a blank chunk");
                assertTrue(chunk.text().length() <= size,
                        () -> document.documentId() + " chunk #" + chunk.chunkIndex()
                                + " exceeds budget: " + chunk.text().length());
            }
        }
    }

    @Test
    @DisplayName("多栏 PDF：不得逐行跨栏串行，左栏整体先于右栏")
    void multiColumnPdfIsNotInterleaved() {
        String text = index.texts().get("pdf-multicolumn");
        List<String> ordered = List.of(
                "LEFT-ALPHA", "LEFT-BETA", "LEFT-GAMMA",
                "RIGHT-ALPHA", "RIGHT-BETA", "RIGHT-GAMMA");
        int previous = -1;
        for (String marker : ordered) {
            int position = text.indexOf(marker);
            assertTrue(position >= 0, "missing column marker: " + marker);
            assertTrue(position > previous,
                    "column marker out of order: " + marker);
            previous = position;
        }
        for (ChunkRecord chunk : chunksOf("pdf-multicolumn")) {
            if (chunk.text().contains("LEFT-") && chunk.text().contains("RIGHT-")) {
                assertTrue(chunk.text().lastIndexOf("LEFT-")
                                < chunk.text().indexOf("RIGHT-"),
                        "chunk mixes columns row by row: " + chunk.text());
            }
        }
    }

    @Test
    @DisplayName("表格：同一行单元格保持在同一行号，且整行落在同一个 chunk")
    void tableRowsStayTogether() {
        for (Map.Entry<String, List<List<String>>> entry
                : spec.tableRows().entrySet()) {
            String text = index.texts().get(entry.getKey());
            for (List<String> row : entry.getValue()) {
                String anchor = row.get(0);
                String line = text.lines()
                        .filter(candidate -> candidate.contains(anchor))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "missing table row anchor: " + anchor));
                for (String cell : row) {
                    assertTrue(line.contains(cell),
                            () -> "table row split across lines: " + line);
                }
                String needle = line.strip();
                boolean insideOneChunk = chunksOf(entry.getKey()).stream()
                        .anyMatch(chunk -> chunk.text().contains(needle));
                assertTrue(insideOneChunk,
                        () -> "table row split across chunks: " + needle);
            }
        }
    }

    @Test
    @DisplayName("元数据可用：chunk 携带 documentId/chunkIndex/title，且命中可定位")
    void chunkMetadataIsUsableOnRetrievalSide() {
        for (DocumentSpec document : spec.documents()) {
            List<ChunkRecord> chunks = chunksOf(document.documentId());
            for (int i = 0; i < chunks.size(); i++) {
                ChunkRecord chunk = chunks.get(i);
                assertEquals(i, chunk.chunkIndex(),
                        "chunkIndex must be contiguous and stable");
                assertEquals(index.titles().get(document.documentId()), chunk.title(),
                        "chunk must carry its document title");
                assertFalse(chunk.title().isBlank(), "title must not be blank");
                assertNotNull(chunk.kind(), "chunk kind must be present");
            }
        }

        // 代码块 / 表格 protected block 在检索侧仍可被识别。
        assertTrue(chunksOf("markdown-runbook").stream()
                        .anyMatch(chunk -> chunk.kind().equals("CODE")
                                && chunk.text().contains("int answer = 42;")),
                "markdown code block must keep CODE metadata");
        assertTrue(chunksOf("markdown-runbook").stream()
                        .anyMatch(chunk -> chunk.kind().equals("TABLE")
                                && chunk.text().contains("| 值一 | 值二 |")),
                "markdown table must keep TABLE metadata");
        assertTrue(chunksOf("docx-policy").stream()
                        .anyMatch(chunk -> chunk.kind().equals("TABLE")
                                && chunk.text().contains("| 张三 | 30 |")),
                "DOCX table must keep TABLE metadata");

        for (QuerySpec query : spec.queries()) {
            List<ScoredChunk> ranked = rank(query.query(), index.chunks());
            ScoredChunk hit = ranked.stream()
                    .filter(scored -> isRelevant(scored.chunk(), query))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "query lost its relevant chunk: " + query.id()));
            assertEquals(query.expectedDocumentId(), hit.chunk().documentId(),
                    () -> "query hit wrong document: " + query.id());
            assertFalse(hit.chunk().title().isBlank(),
                    () -> "hit lost title metadata: " + query.id());
            assertTrue(ranked.indexOf(hit) < spec.metrics().recallK(),
                    () -> "relevant chunk outside Recall@K: " + query.id());
        }
    }

    @Test
    @DisplayName("确定性：同一语料两次索引结果逐 chunk 完全一致")
    void twoIndexBuildsAreIdentical() throws Exception {
        CorpusIndex second = buildIndex(spec);
        assertEquals(index.chunks(), second.chunks(),
                "two index builds must produce identical chunks");
        assertEquals(index.texts(), second.texts(),
                "two extractions must produce identical text");
        assertEquals(index.chunkings(), second.chunkings(),
                "two chunking runs must produce identical diagnostics");
    }

    @Test
    @DisplayName("tie-break 稳定：同分结果按 documentId、chunkIndex 排序且重复一致")
    void tieBreakIsDeterministicForEqualScores() {
        // 无命中查询让所有候选同分（0.0），只由 tie-break 决定顺序。
        String zeroScoreQuery = "@@@";
        List<ScoredChunk> ranked = rank(zeroScoreQuery, index.chunks());
        for (ScoredChunk scored : ranked) {
            assertEquals(0.0, scored.score(), EPSILON,
                    "expected zero score for a non-matching query");
        }
        List<ChunkRecord> expectedOrder = index.chunks().stream()
                .sorted(Comparator.comparing(ChunkRecord::documentId)
                        .thenComparingInt(ChunkRecord::chunkIndex))
                .toList();
        assertEquals(expectedOrder,
                ranked.stream().map(ScoredChunk::chunk).toList(),
                "equal scores must be ordered by documentId then chunkIndex");

        List<ScoredChunk> repeated = rank(zeroScoreQuery, index.chunks());
        assertEquals(ranked, repeated, "repeated ranking must be identical");
    }

    @Test
    @DisplayName("灵敏度反证：相关 chunk 被丢弃时同一阈值必须变红")
    void gateRejectsDroppedRelevantChunks() {
        List<ChunkRecord> withoutRelevant = index.chunks().stream()
                .filter(chunk -> spec.queries().stream()
                        .noneMatch(query -> isRelevant(chunk, query)))
                .toList();
        MetricsResult degraded = evaluate(spec, withoutRelevant, false);

        assertTrue(degraded.recallAtK() < spec.thresholds().recallAtK(),
                "gate failed to reject a corpus that lost its evidence: "
                        + degraded.recallAtK());
        assertTrue(degraded.mrr() < spec.thresholds().mrr(),
                "gate failed to reject a corpus that lost its evidence: "
                        + degraded.mrr());
        assertTrue(degraded.ndcgAtK() < spec.thresholds().ndcgAtK(),
                "gate failed to reject a corpus that lost its evidence: "
                        + degraded.ndcgAtK());
    }

    @Test
    @DisplayName("灵敏度反证：排序被反转时 MRR / nDCG 必须变红")
    void gateRejectsShuffledRanking() {
        MetricsResult shuffled = evaluate(spec, index.chunks(), true);

        assertTrue(shuffled.mrr() < spec.thresholds().mrr(),
                "gate failed to reject a reversed ranking: " + shuffled.mrr());
        assertTrue(shuffled.ndcgAtK() < spec.thresholds().ndcgAtK(),
                "gate failed to reject a reversed ranking: " + shuffled.ndcgAtK());
    }

    // ─── 索引与检索 ───────────────────────────────────────────────

    private static CorpusIndex buildIndex(Spec spec) throws Exception {
        List<ChunkRecord> chunks = new ArrayList<>();
        Map<String, String> texts = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        Map<String, ChunkingResult> chunkings = new LinkedHashMap<>();
        for (DocumentSpec document : spec.documents()) {
            byte[] fixture = fixture(document.fixture());
            DocumentTextExtractor.ExtractedDocument extracted =
                    DocumentTextExtractor.extract(document.filename(), fixture);
            texts.put(document.documentId(), extracted.text());
            titles.put(document.documentId(), extracted.title());
            ChunkingResult result = new BoundedTextChunker(
                    spec.chunking().size(),
                    spec.chunking().minSize(),
                    spec.chunking().overlap(),
                    spec.chunking().maxInputChars(),
                    spec.chunking().maxChunks())
                    .split(extracted.text());
            chunkings.put(document.documentId(), result);
            for (int i = 0; i < result.chunks().size(); i++) {
                ChunkingResult.ChunkMetadata metadata = result.metadata().get(i);
                chunks.add(new ChunkRecord(
                        document.documentId(),
                        i,
                        extracted.title(),
                        metadata.kind(),
                        metadata.titlePath(),
                        result.chunks().get(i).text()));
            }
        }
        return new CorpusIndex(List.copyOf(chunks), Map.copyOf(texts),
                Map.copyOf(titles), Map.copyOf(chunkings));
    }

    private static byte[] fixture(String name) throws IOException {
        return switch (name) {
            case "multiColumnPdf" -> DocumentParsingCorpus.multiColumnPdf();
            case "retrievalTablePdf" -> DocumentParsingCorpus.retrievalTablePdf();
            case "docx" -> DocumentParsingCorpus.docx();
            case "markdown" -> DocumentParsingCorpus.markdown();
            case "htmlWithNoise" -> DocumentParsingCorpus.htmlWithNoise();
            case "retrievalGbkText" -> DocumentParsingCorpus.retrievalGbkText();
            default -> throw new IllegalArgumentException(
                    "Unknown retrieval fixture: " + name);
        };
    }

    private static List<ChunkRecord> chunksOf(String documentId) {
        return index.chunks().stream()
                .filter(chunk -> chunk.documentId().equals(documentId))
                .toList();
    }

    private static MetricsResult evaluate(Spec spec, List<ChunkRecord> chunks,
                                          boolean reverseRanking) {
        double recall = 0;
        double mrr = 0;
        double ndcg = 0;
        for (QuerySpec query : spec.queries()) {
            List<ScoredChunk> ranked = new ArrayList<>(rank(query.query(), chunks));
            if (reverseRanking) {
                java.util.Collections.reverse(ranked);
            }
            int firstRelevant = 0;
            int relevantCount = 0;
            double dcg = 0;
            for (int i = 0; i < ranked.size(); i++) {
                if (!isRelevant(ranked.get(i).chunk(), query)) {
                    continue;
                }
                relevantCount++;
                if (firstRelevant == 0) {
                    firstRelevant = i + 1;
                }
                if (i < spec.metrics().ndcgK()) {
                    dcg += 1.0 / log2(i + 2);
                }
            }
            if (firstRelevant > 0 && firstRelevant <= spec.metrics().recallK()) {
                recall += 1.0;
            }
            if (firstRelevant > 0) {
                mrr += 1.0 / firstRelevant;
            }
            double idcg = 0;
            int ideal = Math.min(relevantCount, spec.metrics().ndcgK());
            for (int i = 0; i < ideal; i++) {
                idcg += 1.0 / log2(i + 2);
            }
            if (idcg > 0) {
                ndcg += dcg / idcg;
            }
        }
        int count = spec.queries().size();
        return new MetricsResult(recall / count, mrr / count, ndcg / count);
    }

    private static List<ScoredChunk> rank(String query, List<ChunkRecord> chunks) {
        double[] queryVector = embed(query);
        List<ScoredChunk> scored = new ArrayList<>(chunks.size());
        for (ChunkRecord chunk : chunks) {
            scored.add(new ScoredChunk(chunk,
                    dot(queryVector, embed(chunk.text()))));
        }
        scored.sort(RANK_ORDER);
        return scored;
    }

    private static boolean isRelevant(ChunkRecord chunk, QuerySpec query) {
        if (!chunk.documentId().equals(query.expectedDocumentId())) {
            return false;
        }
        String text = chunk.text().toLowerCase(Locale.ROOT);
        for (String term : query.expectedTerms()) {
            if (!text.contains(term.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    // ─── 确定性假 embedding ────────────────────────────────────────

    /** 特征哈希 embedding：同一文本永远得到同一向量，不依赖任何模型。 */
    static double[] embed(String text) {
        double[] vector = new double[DIMENSIONS];
        for (String token : tokens(text)) {
            int hash = fnv1a(token);
            int index = Math.floorMod(hash, DIMENSIONS);
            double sign = (hash & 1) == 0 ? 1.0 : -1.0;
            vector[index] += sign;
        }
        double norm = 0;
        for (double value : vector) {
            norm += value * value;
        }
        if (norm > 0) {
            double scale = 1.0 / Math.sqrt(norm);
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }

    /**
     * 拉丁字母/数字按连续 run 取词；CJK 连续片段取 unigram + bigram。
     * 标点、空白和符号都是分隔符，保证中英文查询都能与正文稳定匹配。
     */
    static List<String> tokens(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        int offset = 0;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                flushLatin(latin, result);
                cjk.appendCodePoint(codePoint);
            } else if (Character.isLetterOrDigit(codePoint)) {
                flushCjk(cjk, result);
                latin.appendCodePoint(codePoint);
            } else {
                flushLatin(latin, result);
                flushCjk(cjk, result);
            }
        }
        flushLatin(latin, result);
        flushCjk(cjk, result);
        return List.copyOf(result);
    }

    private static void flushLatin(StringBuilder token, LinkedHashSet<String> result) {
        if (!token.isEmpty()) {
            result.add(token.toString().toLowerCase(Locale.ROOT));
            token.setLength(0);
        }
    }

    private static void flushCjk(StringBuilder run, LinkedHashSet<String> result) {
        if (run.isEmpty()) {
            return;
        }
        String value = run.toString();
        run.setLength(0);
        List<Integer> points = value.codePoints().boxed().toList();
        for (int i = 0; i < points.size(); i++) {
            result.add(value.substring(
                    value.offsetByCodePoints(0, i),
                    value.offsetByCodePoints(0, i + 1)).toLowerCase(Locale.ROOT));
            if (i + 1 < points.size()) {
                result.add(value.substring(
                        value.offsetByCodePoints(0, i),
                        value.offsetByCodePoints(0, i + 2)).toLowerCase(Locale.ROOT));
            }
        }
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static int fnv1a(String token) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < token.length(); i++) {
            hash ^= token.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }

    private static double dot(double[] left, double[] right) {
        double value = 0;
        for (int i = 0; i < left.length; i++) {
            value += left[i] * right[i];
        }
        return value;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    private static int countNonWhitespace(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    // ─── 数据模型 ─────────────────────────────────────────────────

    record CorpusIndex(List<ChunkRecord> chunks,
                       Map<String, String> texts,
                       Map<String, String> titles,
                       Map<String, ChunkingResult> chunkings) {
    }

    record ChunkRecord(String documentId, int chunkIndex, String title,
                       String kind, List<String> titlePath, String text) {
    }

    record ScoredChunk(ChunkRecord chunk, double score) {
    }

    record MetricsResult(double recallAtK, double mrr, double ndcgAtK) {
    }

    record Chunking(int size, int minSize, int overlap,
                    int maxInputChars, int maxChunks) {
    }

    record Metrics(int recallK, int ndcgK) {
    }

    record Thresholds(double recallAtK, double mrr, double ndcgAtK) {
    }

    record DocumentSpec(String documentId, String filename,
                        String fixture, String description) {
    }

    record QuerySpec(String id, String query, String expectedDocumentId,
                     List<String> expectedTerms) {
    }

    record Spec(int version, String name, String description,
                Chunking chunking, Metrics metrics, Thresholds thresholds,
                List<DocumentSpec> documents, List<QuerySpec> queries,
                Map<String, List<List<String>>> tableRows) {

        static Spec load() throws IOException {
            try (InputStream input = ParsingRetrievalQualityGateTest.class
                    .getResourceAsStream(GOLDENSET)) {
                Objects.requireNonNull(input, "Missing goldenset: " + GOLDENSET);
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(input);
                return mapper.convertValue(root, Spec.class);
            }
        }
    }
}
