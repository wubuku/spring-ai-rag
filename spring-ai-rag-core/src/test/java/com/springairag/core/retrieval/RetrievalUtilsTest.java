package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetrievalUtils 单元测试
 */
class RetrievalUtilsTest {

    // ========== cosineSimilarity ==========

    @Test
    void cosineSimilarity_identicalVectors_returnsOne() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {1.0f, 0.0f, 0.0f};
        assertEquals(1.0, RetrievalUtils.cosineSimilarity(a, b), 1e-9);
    }

    @Test
    void cosineSimilarity_orthogonalVectors_returnsZero() {
        float[] a = {1.0f, 0.0f};
        float[] b = {0.0f, 1.0f};
        assertEquals(0.0, RetrievalUtils.cosineSimilarity(a, b), 1e-9);
    }

    @Test
    void cosineSimilarity_oppositeVectors_returnsNegativeOne() {
        float[] a = {1.0f, 0.0f};
        float[] b = {-1.0f, 0.0f};
        assertEquals(-1.0, RetrievalUtils.cosineSimilarity(a, b), 1e-9);
    }

    @Test
    void cosineSimilarity_differentDimensions_returnsZero() {
        float[] a = {1.0f, 0.0f};
        float[] b = {1.0f, 0.0f, 0.0f};
        assertEquals(0.0, RetrievalUtils.cosineSimilarity(a, b));
    }

    @Test
    void cosineSimilarity_nullInput_returnsZero() {
        assertEquals(0.0, RetrievalUtils.cosineSimilarity(null, new float[]{1.0f}));
        assertEquals(0.0, RetrievalUtils.cosineSimilarity(new float[]{1.0f}, null));
    }

    @Test
    void cosineSimilarity_emptyVectors_returnsZero() {
        assertEquals(0.0, RetrievalUtils.cosineSimilarity(new float[0], new float[0]));
    }

    @Test
    void cosineSimilarity_zeroVector_returnsZero() {
        float[] a = {0.0f, 0.0f};
        float[] b = {1.0f, 1.0f};
        assertEquals(0.0, RetrievalUtils.cosineSimilarity(a, b), 1e-9);
    }

    @Test
    void cosineSimilarity_highDimensional() {
        // 1024 维（BGE-M3 实际维度）
        float[] a = new float[1024];
        float[] b = new float[1024];
        for (int i = 0; i < 1024; i++) {
            a[i] = (float) Math.random();
            b[i] = (float) Math.random();
        }
        double sim = RetrievalUtils.cosineSimilarity(a, b);
        assertTrue(sim >= -1.0 && sim <= 1.0, "相似度应在 [-1, 1] 范围内");
    }

    // ========== vectorToString ==========

    @Test
    void vectorToString_normal() {
        float[] v = {0.1f, 0.2f, 0.3f};
        assertEquals("[0.1,0.2,0.3]", RetrievalUtils.vectorToString(v));
    }

    @Test
    void vectorToString_singleElement() {
        assertEquals("[0.5]", RetrievalUtils.vectorToString(new float[]{0.5f}));
    }

    @Test
    void vectorToString_empty_returnsEmptyBrackets() {
        assertEquals("[]", RetrievalUtils.vectorToString(new float[0]));
    }

    @Test
    void vectorToString_null_returnsEmptyBrackets() {
        assertEquals("[]", RetrievalUtils.vectorToString(null));
    }

    // ========== parseVector ==========

    @Test
    void parseVector_floatArray() {
        float[] input = {0.1f, 0.2f, 0.3f};
        assertArrayEquals(input, RetrievalUtils.parseVector(input));
    }

    @Test
    void parseVector_doubleArray() {
        double[] input = {0.1, 0.2, 0.3};
        float[] result = RetrievalUtils.parseVector(input);
        assertEquals(3, result.length);
        assertEquals(0.1f, result[0], 1e-6);
        assertEquals(0.2f, result[1], 1e-6);
        assertEquals(0.3f, result[2], 1e-6);
    }

    @Test
    void parseVector_stringWithBrackets() {
        float[] result = RetrievalUtils.parseVector("[0.1, 0.2, 0.3]");
        assertEquals(3, result.length);
        assertEquals(0.1f, result[0], 1e-6);
        assertEquals(0.3f, result[2], 1e-6);
    }

    @Test
    void parseVector_stringWithoutBrackets() {
        float[] result = RetrievalUtils.parseVector("0.1,0.2");
        assertEquals(2, result.length);
    }

    @Test
    void parseVector_emptyString() {
        float[] result = RetrievalUtils.parseVector("");
        assertEquals(0, result.length);
    }

    @Test
    void parseVector_null_returnsEmpty() {
        assertEquals(0, RetrievalUtils.parseVector(null).length);
    }

    @Test
    void parseVector_unknownType_returnsEmpty() {
        assertEquals(0, RetrievalUtils.parseVector(123).length);
    }

    // ========== fuseResults ==========

    @Test
    void fuseResults_mergesOverlappingEntries() {
        // 同一个 doc:chunk 同时出现在向量和全文结果中
        RetrievalResult v1 = RetrievalUtils.createResult("doc-1", "chunk A", 0, 0.9);
        RetrievalResult f1 = RetrievalUtils.createResult("doc-1", "chunk A", 0, 0.7);

        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(v1), List.of(f1), 10, 0.5f, 0.5f);

        assertEquals(1, fused.size());
        assertTrue(fused.get(0).getVectorScore() > 0);
        assertTrue(fused.get(0).getFulltextScore() > 0);
    }

    @Test
    void fuseResults_vectorOnlyResults() {
        RetrievalResult v1 = RetrievalUtils.createResult("doc-1", "vec only", 0, 0.8);
        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(v1), List.of(), 10, 0.5f, 0.5f);

        assertEquals(1, fused.size());
        assertTrue(fused.get(0).getFulltextScore() == 0);
    }

    @Test
    void fuseResults_fulltextOnlyResults() {
        RetrievalResult f1 = RetrievalUtils.createResult("doc-1", "ft only", 0, 0.6);
        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(), List.of(f1), 10, 0.5f, 0.5f);

        assertEquals(1, fused.size());
        assertTrue(fused.get(0).getVectorScore() == 0);
    }

    @Test
    void fuseResults_respectsLimit() {
        List<RetrievalResult> vectors = List.of(
                RetrievalUtils.createResult("doc-1", "a", 0, 0.9),
                RetrievalUtils.createResult("doc-2", "b", 0, 0.8),
                RetrievalUtils.createResult("doc-3", "c", 0, 0.7)
        );
        List<RetrievalResult> fused = RetrievalUtils.fuseResults(vectors, List.of(), 2, 0.5f, 0.5f);
        assertEquals(2, fused.size());
    }

    @Test
    void fuseResults_sortedByDescendingScore() {
        List<RetrievalResult> vectors = List.of(
                RetrievalUtils.createResult("doc-1", "low", 0, 0.3),
                RetrievalUtils.createResult("doc-2", "high", 0, 0.9)
        );
        List<RetrievalResult> fused = RetrievalUtils.fuseResults(vectors, List.of(), 10, 0.5f, 0.5f);
        assertTrue(fused.get(0).getScore() >= fused.get(1).getScore());
    }

    @Test
    void fuseResults_bothNull_returnsEmpty() {
        List<RetrievalResult> fused = RetrievalUtils.fuseResults(null, null, 10, 0.5f, 0.5f);
        assertTrue(fused.isEmpty());
    }

    @Test
    void fuseResults_differentChunkIndex_notMerged() {
        // 同一个 doc 但不同 chunk index，不应该合并
        RetrievalResult v1 = RetrievalUtils.createResult("doc-1", "chunk 0", 0, 0.9);
        RetrievalResult f1 = RetrievalUtils.createResult("doc-1", "chunk 1", 1, 0.7);

        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(v1), List.of(f1), 10, 0.5f, 0.5f);

        assertEquals(2, fused.size());
    }

    @Test
    void fuseResults_higherVectorWeight_biasesTowardVector() {
        RetrievalResult v1 = RetrievalUtils.createResult("doc-1", "vec", 0, 0.8);
        RetrievalResult f1 = RetrievalUtils.createResult("doc-2", "ft", 0, 0.8);

        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(v1), List.of(f1), 10, 0.9f, 0.1f);

        // vector weight 更高，doc-1 应排在前面
        assertEquals("doc-1", fused.get(0).getDocumentId());
    }

    // ========== createResult ==========

    @Test
    void createResult_setsFields() {
        RetrievalResult r = RetrievalUtils.createResult("doc-x", "text", 3, 0.75);
        assertEquals("doc-x", r.getDocumentId());
        assertEquals("text", r.getChunkText());
        assertEquals(3, r.getChunkIndex());
        assertEquals(0.75, r.getScore(), 1e-9);
    }

    // ========== Edge Cases: All-Zero and NaN Scores ==========

    @Test
    void fuseResults_allZeroVectorScores_noNaN() {
        // Bug fix: when all vector scores are 0, division by zero produced NaN
        RetrievalResult v1 = RetrievalUtils.createResult("doc-1", "zero vec", 0, 0.0);
        RetrievalResult v2 = RetrievalUtils.createResult("doc-2", "also zero", 0, 0.0);

        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(v1, v2), List.of(), 10, 0.5f, 0.5f);

        assertEquals(2, fused.size());
        for (RetrievalResult r : fused) {
            assertFalse(Double.isNaN(r.getScore()), "Score should not be NaN when all inputs are 0");
            assertEquals(0.0, r.getScore(), 0.0, "Score should be 0.0 when all inputs are 0");
        }
    }

    @Test
    void fuseResults_allZeroFulltextScores_noNaN() {
        RetrievalResult f1 = RetrievalUtils.createResult("doc-1", "zero ft", 0, 0.0);

        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(), List.of(f1), 10, 0.5f, 0.5f);

        assertEquals(1, fused.size());
        assertFalse(Double.isNaN(fused.get(0).getScore()), "Score should not be NaN");
        assertEquals(0.0, fused.get(0).getScore(), 0.0, "Score should be 0.0");
    }

    @Test
    void fuseResults_emptyVectorList_noNaN() {
        RetrievalResult f1 = RetrievalUtils.createResult("doc-1", "ft only", 0, 0.5);

        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(), List.of(f1), 10, 0.5f, 0.5f);

        assertEquals(1, fused.size());
        assertFalse(Double.isNaN(fused.get(0).getScore()), "Score should not be NaN with empty vector list");
    }

    @Test
    void fuseResults_emptyFulltextList_noNaN() {
        RetrievalResult v1 = RetrievalUtils.createResult("doc-1", "vec only", 0, 0.5);

        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(v1), List.of(), 10, 0.5f, 0.5f);

        assertEquals(1, fused.size());
        assertFalse(Double.isNaN(fused.get(0).getScore()), "Score should not be NaN with empty fulltext list");
    }

    @Test
    void fuseResults_mixedZeroAndValidScores_noNaN() {
        // Some valid scores mixed with zeros should work fine
        RetrievalResult v1 = RetrievalUtils.createResult("doc-1", "valid", 0, 0.8);
        RetrievalResult v2 = RetrievalUtils.createResult("doc-2", "zero", 0, 0.0);

        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(v1, v2), List.of(), 10, 0.5f, 0.5f);

        assertEquals(2, fused.size());
        for (RetrievalResult r : fused) {
            assertFalse(Double.isNaN(r.getScore()), "Score should not be NaN: " + r.getDocumentId());
        }
    }
}
