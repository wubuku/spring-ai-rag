package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RerankResultSelectorTest {

    @Test
    void selectPrefersDocumentCoverageAndPreservesProviderObjectsAndFields() {
        RetrievalResult a0 = result("A", 0, 0.99);
        RetrievalResult a1 = result("A", 1, 0.98);
        RetrievalResult a2 = result("A", 2, 0.97);
        RetrievalResult b0 = result("B", 0, 0.96);
        RetrievalResult c0 = result("C", 0, 0.95);
        a0.setMetadata(Map.of("source", "provider"));
        List<RetrievalResult> ranked = List.of(a0, a1, a2, b0, c0);

        List<RetrievalResult> selected =
                RerankResultSelector.select(ranked, 4, 2);

        assertEquals(List.of("A", "A", "B", "C"), documentIds(selected));
        assertSame(a0, selected.get(0));
        assertSame(a1, selected.get(1));
        assertSame(b0, selected.get(2));
        assertSame(c0, selected.get(3));
        assertEquals(0.99, selected.get(0).getScore(), 1e-9);
        assertSame(a0.getMetadata(), selected.get(0).getMetadata());
    }

    @Test
    void selectBackfillsSkippedChunksInProviderOrderWhenCoverageIsInsufficient() {
        RetrievalResult a0 = result("A", 0, 0.99);
        RetrievalResult a1 = result("A", 1, 0.98);
        RetrievalResult a2 = result("A", 2, 0.97);
        RetrievalResult b0 = result("B", 0, 0.96);
        RetrievalResult a3 = result("A", 3, 0.95);

        List<RetrievalResult> selected = RerankResultSelector.select(
                List.of(a0, a1, a2, b0, a3), 4, 2);

        assertEquals(List.of(a0, a1, a2, b0), selected);
        assertSame(a2, selected.get(2));
    }

    @Test
    void selectTreatsBlankIdsAsIndependentAndNonblankIdsAsExactStrings() {
        RetrievalResult nullOne = result(null, 0, 0.99);
        RetrievalResult nullTwo = result(null, 1, 0.98);
        RetrievalResult blank = result("   ", 2, 0.97);
        RetrievalResult exact = result("A", 0, 0.96);
        RetrievalResult duplicate = result("A", 1, 0.95);
        RetrievalResult spaced = result(" A", 0, 0.94);

        List<RetrievalResult> selected = RerankResultSelector.select(
                List.of(nullOne, nullTwo, blank, exact, duplicate, spaced),
                5,
                1);

        assertEquals(
                List.of(nullOne, nullTwo, blank, exact, spaced),
                selected);
    }

    @Test
    void selectUsesAtMostThreeLinearCandidatePasses() {
        List<RetrievalResult> values = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            values.add(result("same-document", index, 1.0 - index / 1000.0));
        }
        CountingList ranked = new CountingList(values);

        List<RetrievalResult> selected =
                RerankResultSelector.select(ranked, 20, 2);

        assertEquals(20, selected.size());
        assertTrue(
                ranked.visits() <= 3 * ranked.size(),
                "选择器候选访问次数应保持常数次线性扫描");
    }

    private static List<String> documentIds(List<RetrievalResult> results) {
        return results.stream().map(RetrievalResult::getDocumentId).toList();
    }

    private static RetrievalResult result(
            String documentId,
            int chunkIndex,
            double score) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        result.setChunkIndex(chunkIndex);
        result.setChunkText("chunk-" + chunkIndex);
        result.setTitle("title-" + documentId);
        result.setScore(score);
        result.setVectorScore(score - 0.01);
        result.setFulltextScore(score - 0.02);
        return result;
    }

    private static final class CountingList extends AbstractList<RetrievalResult> {

        private final List<RetrievalResult> delegate;
        private int visits;

        private CountingList(List<RetrievalResult> delegate) {
            this.delegate = delegate;
        }

        @Override
        public RetrievalResult get(int index) {
            visits++;
            return delegate.get(index);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        private int visits() {
            return visits;
        }
    }
}
