package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetrievalUtils Unit Tests
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

    // ========== euclideanDistance ==========

    @Test
    void euclideanDistance_identicalVectors_returnsZero() {
        float[] a = {3.0f, 4.0f};
        float[] b = {3.0f, 4.0f};
        assertEquals(0.0, RetrievalUtils.euclideanDistance(a, b), 1e-9);
    }

    @Test
    void euclideanDistance_knownDistance() {
        // Distance from (0,0) to (3,4) = 5
        float[] a = {0.0f, 0.0f};
        float[] b = {3.0f, 4.0f};
        assertEquals(5.0, RetrievalUtils.euclideanDistance(a, b), 1e-9);
    }

    @Test
    void euclideanDistance_differentDimensions_returnsMaxValue() {
        float[] a = {1.0f, 0.0f};
        float[] b = {1.0f, 0.0f, 0.0f};
        assertEquals(Double.MAX_VALUE, RetrievalUtils.euclideanDistance(a, b), 1e-9);
    }

    @Test
    void euclideanDistance_nullInput_returnsMaxValue() {
        assertEquals(Double.MAX_VALUE, RetrievalUtils.euclideanDistance(null, new float[]{1.0f}), 1e-9);
        assertEquals(Double.MAX_VALUE, RetrievalUtils.euclideanDistance(new float[]{1.0f}, null), 1e-9);
    }

    @Test
    void euclideanDistance_emptyVectors_returnsMaxValue() {
        assertEquals(Double.MAX_VALUE, RetrievalUtils.euclideanDistance(new float[0], new float[0]), 1e-9);
    }

    @Test
    void euclideanDistance_highDimensional() {
        float[] a = new float[1024];
        float[] b = new float[1024];
        for (int i = 0; i < 1024; i++) {
            a[i] = (float) Math.random();
            b[i] = (float) Math.random();
        }
        double dist = RetrievalUtils.euclideanDistance(a, b);
        assertTrue(dist >= 0.0, "Distance should be non-negative");
    }

    // ========== dotProduct ==========

    @Test
    void dotProduct_identicalUnitVectors_returnsOne() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {1.0f, 0.0f, 0.0f};
        assertEquals(1.0, RetrievalUtils.dotProduct(a, b), 1e-9);
    }

    @Test
    void dotProduct_orthogonalVectors_returnsZero() {
        float[] a = {1.0f, 0.0f};
        float[] b = {0.0f, 1.0f};
        assertEquals(0.0, RetrievalUtils.dotProduct(a, b), 1e-9);
    }

    @Test
    void dotProduct_oppositeVectors_returnsNegative() {
        float[] a = {1.0f, 0.0f};
        float[] b = {-1.0f, 0.0f};
        assertEquals(-1.0, RetrievalUtils.dotProduct(a, b), 1e-9);
    }

    @Test
    void dotProduct_knownValues() {
        // [1,2,3] · [4,5,6] = 4+10+18 = 32
        float[] a = {1.0f, 2.0f, 3.0f};
        float[] b = {4.0f, 5.0f, 6.0f};
        assertEquals(32.0, RetrievalUtils.dotProduct(a, b), 1e-9);
    }

    @Test
    void dotProduct_differentDimensions_returnsZero() {
        float[] a = {1.0f, 0.0f};
        float[] b = {1.0f, 0.0f, 0.0f};
        assertEquals(0.0, RetrievalUtils.dotProduct(a, b));
    }

    @Test
    void dotProduct_nullInput_returnsZero() {
        assertEquals(0.0, RetrievalUtils.dotProduct(null, new float[]{1.0f}));
        assertEquals(0.0, RetrievalUtils.dotProduct(new float[]{1.0f}, null));
    }

    @Test
    void dotProduct_emptyVectors_returnsZero() {
        assertEquals(0.0, RetrievalUtils.dotProduct(new float[0], new float[0]));
    }

    @Test
    void dotProduct_allZeroVector_returnsZero() {
        float[] a = {0.0f, 0.0f};
        float[] b = {1.0f, 2.0f};
        assertEquals(0.0, RetrievalUtils.dotProduct(a, b), 1e-9);
    }

    @Test
    void dotProduct_highDimensional() {
        float[] a = new float[1024];
        float[] b = new float[1024];
        for (int i = 0; i < 1024; i++) {
            a[i] = (float) (Math.random() * 2 - 1); // [-1, 1]
            b[i] = (float) (Math.random() * 2 - 1);
        }
        double product = RetrievalUtils.dotProduct(a, b);
        assertTrue(Double.isFinite(product), "Dot product should be finite");
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
        v1.setSource("pdf-import:uuid/default.md");
        v1.setOriginalFilename("manual.pdf");
        v1.setFileDirectoryPath("uuid/");
        v1.setIndexedFilePath("uuid/default.md");
        v1.setOriginalFilePath("uuid/original.pdf");
        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(v1), List.of(), 10, 0.5f, 0.5f);

        assertEquals(1, fused.size());
        assertTrue(fused.get(0).getFulltextScore() == 0);
        assertEquals(v1.getSource(), fused.get(0).getSource());
        assertEquals(v1.getOriginalFilename(), fused.get(0).getOriginalFilename());
        assertEquals(v1.getFileDirectoryPath(), fused.get(0).getFileDirectoryPath());
        assertEquals(v1.getIndexedFilePath(), fused.get(0).getIndexedFilePath());
        assertEquals(v1.getOriginalFilePath(), fused.get(0).getOriginalFilePath());
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
    void fuseResults_usesRankInsteadOfCrossChannelRawScoreScale() {
        List<RetrievalResult> first = RetrievalUtils.fuseResults(
                List.of(
                        RetrievalUtils.createResult("doc-a", "vector-a", 0, 0.9),
                        RetrievalUtils.createResult("doc-b", "vector-b", 0, 0.8)),
                List.of(
                        RetrievalUtils.createResult("doc-b", "fulltext-b", 0, 0.7),
                        RetrievalUtils.createResult("doc-c", "fulltext-c", 0, 0.6)),
                10, 0.5f, 0.5f);
        List<RetrievalResult> second = RetrievalUtils.fuseResults(
                List.of(
                        RetrievalUtils.createResult("doc-a", "vector-a", 0, 0.9),
                        RetrievalUtils.createResult("doc-b", "vector-b", 0, 0.1)),
                List.of(
                        RetrievalUtils.createResult("doc-b", "fulltext-b", 0, 700.0),
                        RetrievalUtils.createResult("doc-c", "fulltext-c", 0, 1.0)),
                10, 0.5f, 0.5f);

        assertEquals(
                first.stream().map(RetrievalResult::getDocumentId).toList(),
                second.stream().map(RetrievalResult::getDocumentId).toList());
        assertEquals(
                first.stream().map(RetrievalResult::getScore).toList(),
                second.stream().map(RetrievalResult::getScore).toList());
        assertEquals(List.of("doc-b", "doc-a", "doc-c"),
                first.stream().map(RetrievalResult::getDocumentId).toList());
    }

    @Test
    void fuseResults_overlappingCandidate_sumsBothRankContributions() {
        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(
                        RetrievalUtils.createResult("doc-a", "a", 0, 0.9),
                        RetrievalUtils.createResult("doc-b", "b", 0, 0.8)),
                List.of(RetrievalUtils.createResult("doc-b", "b", 0, 100.0)),
                10, 0.5f, 0.5f);

        assertEquals("doc-b", fused.get(0).getDocumentId());
        assertEquals(
                0.5 + (61.0 * 0.5 / 62.0),
                fused.get(0).getScore(),
                1e-9);
        assertEquals(0.5, fused.get(1).getScore(), 1e-9);
        assertEquals(0.8, fused.get(0).getVectorScore(), 1e-9);
        assertEquals(100.0, fused.get(0).getFulltextScore(), 1e-9);
    }

    @Test
    void fuseResults_duplicateIdentityUsesFirstRankButConsumesDuplicatePosition() {
        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(
                        RetrievalUtils.createResult("doc-a", "first", 0, 0.9),
                        RetrievalUtils.createResult("doc-a", "duplicate", 0, 0.8),
                        RetrievalUtils.createResult("doc-b", "second", 0, 0.7)),
                List.of(), 10, 0.5f, 0.5f);

        assertEquals(List.of("doc-a", "doc-b"),
                fused.stream().map(RetrievalResult::getDocumentId).toList());
        assertEquals(0.5, fused.get(0).getScore(), 1e-9);
        assertEquals(61.0 * 0.5 / 63.0, fused.get(1).getScore(), 1e-9);
        assertEquals("first", fused.get(0).getChunkText());
    }

    @Test
    void fuseResults_sameScoreUsesStableDocumentAndChunkOrder() {
        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(
                        RetrievalUtils.createResult("doc-b", "b", 1, 0.5),
                        RetrievalUtils.createResult("doc-a", "a", 2, 0.5),
                        RetrievalUtils.createResult("doc-a", "a0", 0, 0.5)),
                List.of(), 10, 0.5f, 0.5f);

        assertEquals(
                List.of("doc-a:0", "doc-a:2", "doc-b:1"),
                fused.stream()
                        .map(r -> r.getDocumentId() + ":" + r.getChunkIndex())
                .toList());
    }

    @Test
    void fuseResults_sameFinalScoreAcrossChannelsUsesStableIdentityOrder() {
        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(RetrievalUtils.createResult("doc-b", "vector", 0, 0.9)),
                List.of(RetrievalUtils.createResult("doc-a", "fulltext", 0, 900.0)),
                10, 0.5f, 0.5f);

        assertEquals(
                List.of("doc-a", "doc-b"),
                fused.stream().map(RetrievalResult::getDocumentId).toList());
        assertEquals(0.5, fused.get(0).getScore(), 1e-9);
        assertEquals(0.5, fused.get(1).getScore(), 1e-9);
    }

    @Test
    void fuseResults_nonFiniteProviderScoresRemainDeterministicAndDoNotPolluteRrf() {
        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(
                        RetrievalUtils.createResult("doc-b", "nan", 0, Double.NaN),
                        RetrievalUtils.createResult("doc-c", "finite", 0, 0.5),
                        RetrievalUtils.createResult("doc-a", "infinite", 0, Double.POSITIVE_INFINITY)),
                List.of(), 10, 0.5f, 0.5f);

        assertEquals(List.of("doc-c", "doc-a", "doc-b"),
                fused.stream().map(RetrievalResult::getDocumentId).toList());
        assertTrue(fused.stream().allMatch(result -> Double.isFinite(result.getScore())));
    }

    @Test
    void fuseResults_invalidWeightsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RetrievalUtils.fuseResults(
                List.of(RetrievalUtils.createResult("doc", "text", 0, 1.0)),
                List.of(), 10, Float.NaN, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> RetrievalUtils.fuseResults(
                List.of(), List.of(), 10, 0.5f, 1.1f));
    }

    @Test
    void fuseResults_nonPositiveLimit_returnsEmpty() {
        RetrievalResult result = RetrievalUtils.createResult("doc", "text", 0, 1.0);

        assertTrue(RetrievalUtils.fuseResults(
                List.of(result), List.of(), 0, 0.5f, 0.5f).isEmpty());
        assertTrue(RetrievalUtils.fuseResults(
                List.of(result), List.of(), -1, 0.5f, 0.5f).isEmpty());
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

    // ========== Edge Cases: All-Zero and Non-Finite Scores ==========

    @Test
    void fuseResults_allZeroVectorScores_useRankBasedFiniteScores() {
        RetrievalResult v1 = RetrievalUtils.createResult("doc-1", "zero vec", 0, 0.0);
        RetrievalResult v2 = RetrievalUtils.createResult("doc-2", "also zero", 0, 0.0);

        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(v1, v2), List.of(), 10, 0.5f, 0.5f);

        assertEquals(2, fused.size());
        assertEquals(0.5, fused.get(0).getScore(), 1e-9);
        assertEquals(61.0 * 0.5 / 62.0, fused.get(1).getScore(), 1e-9);
    }

    @Test
    void fuseResults_allZeroFulltextScores_useRankBasedFiniteScore() {
        RetrievalResult f1 = RetrievalUtils.createResult("doc-1", "zero ft", 0, 0.0);

        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(), List.of(f1), 10, 0.5f, 0.5f);

        assertEquals(1, fused.size());
        assertEquals(0.5, fused.get(0).getScore(), 1e-9);
    }

    @Test
    void fuseResults_emptyVectorList_noNaN() {
        RetrievalResult f1 = RetrievalUtils.createResult("doc-1", "ft only", 0, 0.5);

        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(), List.of(f1), 10, 0.5f, 0.5f);

        assertEquals(1, fused.size());
        assertTrue(Double.isFinite(fused.get(0).getScore()),
                "Score should be finite with empty vector list");
    }

    @Test
    void fuseResults_emptyFulltextList_noNaN() {
        RetrievalResult v1 = RetrievalUtils.createResult("doc-1", "vec only", 0, 0.5);

        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(v1), List.of(), 10, 0.5f, 0.5f);

        assertEquals(1, fused.size());
        assertTrue(Double.isFinite(fused.get(0).getScore()),
                "Score should be finite with empty fulltext list");
    }

    @Test
    void fuseResults_mixedZeroAndValidScores_noNaN() {
        // RRF ranks candidates even when one provider score is zero.
        RetrievalResult v1 = RetrievalUtils.createResult("doc-1", "valid", 0, 0.8);
        RetrievalResult v2 = RetrievalUtils.createResult("doc-2", "zero", 0, 0.0);

        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                List.of(v1, v2), List.of(), 10, 0.5f, 0.5f);

        assertEquals(2, fused.size());
        assertTrue(fused.stream().allMatch(result -> Double.isFinite(result.getScore())));
        assertEquals(0.5, fused.get(0).getScore(), 1e-9);
        assertEquals(61.0 * 0.5 / 62.0, fused.get(1).getScore(), 1e-9);
    }
}
