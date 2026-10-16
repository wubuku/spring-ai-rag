package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollectionCoverageMergerTest {

    @Test
    void givesEveryNonEmptyCollectionTheFirstCandidateBeforeFillingByScore() {
        RetrievalResult first = result("doc-a", 0, 0.99, 1L);
        RetrievalResult second = result("doc-a", 1, 0.50, 1L);
        RetrievalResult other = result("doc-b", 0, 0.10, 2L);
        Map<Long, List<RetrievalResult>> perCollection = new LinkedHashMap<>();
        perCollection.put(1L, List.of(first, second));
        perCollection.put(2L, List.of(other));
        perCollection.put(3L, List.of());

        List<RetrievalResult> merged = CollectionCoverageMerger.merge(
                perCollection, 3);

        assertEquals(List.of("doc-a", "doc-b", "doc-a"),
                merged.stream().map(RetrievalResult::getDocumentId).toList());
        assertEquals(List.of(0, 0, 1),
                merged.stream().map(RetrievalResult::getChunkIndex).toList());
    }

    @Test
    void deduplicatesTheSameDocumentChunkAndKeepsTieOrderStable() {
        RetrievalResult duplicate = result("doc-a", 0, 0.9, 1L);
        RetrievalResult sameDuplicate = result("doc-a", 0, 0.8, 2L);
        RetrievalResult docB = result("doc-b", 0, 0.8, 2L);
        Map<Long, List<RetrievalResult>> perCollection = new LinkedHashMap<>();
        perCollection.put(1L, List.of(duplicate));
        perCollection.put(2L, List.of(sameDuplicate, docB));

        List<RetrievalResult> merged = CollectionCoverageMerger.merge(
                perCollection, 3);

        assertEquals(List.of("doc-a", "doc-b"),
                merged.stream().map(RetrievalResult::getDocumentId).toList());
    }

    @Test
    void countsOnlyReturnedAuthorizedCollectionIds() {
        List<RetrievalResult> results = List.of(
                result("doc-a", 0, 0.9, 7L),
                result("doc-b", 0, 0.8, 7L));

        assertEquals(Map.of(7L, 2, 8L, 0),
                CollectionCoverageMerger.countByCollection(
                        List.of(7L, 8L), results));
    }

    private RetrievalResult result(
            String documentId, int chunkIndex, double score, long collectionId) {
        RetrievalResult result = RetrievalUtils.createResult(
                documentId, documentId, chunkIndex, score);
        result.setCollectionId(collectionId);
        return result;
    }
}
