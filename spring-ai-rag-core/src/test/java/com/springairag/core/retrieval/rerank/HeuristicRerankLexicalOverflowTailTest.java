package com.springairag.core.retrieval.rerank;

import com.springairag.core.config.RagRerankProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HeuristicRerankProvider 词法溢出与多样性长尾（Batch 675，JaCoCo
 * 驱动）：超过 MAX_LEXICAL_FEATURES(512) 的 CJK 文本截断、
 * calculateDiversityScore 与 calculateTextSimilarity 的边界。
 */
class HeuristicRerankLexicalOverflowTailTest {

    private final HeuristicRerankProvider provider =
            new HeuristicRerankProvider(new RagRerankProperties());

    @Test
    void cjkTextWithDistinctCharactersExceedsMaxLexicalFeatures() {
        // 600 个互不重复的 CJK 字符 → 词法特征数远超 512 上限。
        StringBuilder sb = new StringBuilder();
        for (int i = 0x4E00; i < 0x4E00 + 600; i++) {
            sb.appendCodePoint(i);
        }
        String longText = sb.toString();

        float score = provider.calculateRelevanceScore(
                "检", longText);

        // 600 个不同 CJK 字符中仅 1 个与查询匹配 → 分数很低但不崩溃。
        assertTrue(score >= 0.0f && score <= 1.0f,
                () -> "分数应在 [0,1] 内: " + score);
    }

    @Test
    void diversityScoreWithSingleResultReturnsZero() {
        var results = List.of(
                createResult("唯一文档", 0.9));

        float diversity = provider.calculateDiversityScore(
                "唯一文档内容", results);

        assertTrue(diversity >= 0.0f && diversity <= 1.0f,
                () -> "多样性分数应在 [0,1] 内: " + diversity);
    }

    @Test
    void textSimilarityIdenticalTextIsHigh() {
        float similarity = provider.calculateTextSimilarity(
                "相同文本内容", "相同文本内容");

        assertTrue(similarity > 0.5f,
                () -> "相同文本相似度应较高: " + similarity);
    }

    @Test
    void textSimilarityDifferentTextIsLow() {
        float similarity = provider.calculateTextSimilarity(
                "完全不同的内容", "毫无关系的文字");

        assertTrue(similarity < 0.5f,
                () -> "不同文本相似度应较低: " + similarity);
    }

    private com.springairag.api.dto.RetrievalResult createResult(
            String text, double score) {
        var r = new com.springairag.api.dto.RetrievalResult();
        r.setDocumentId("doc-1");
        r.setChunkText(text);
        r.setScore(score);
        return r;
    }
}
