package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** searchInScope 默认实现的 fail-closed 语义与 documentIds 透传。 */
class FulltextSearchProviderScopeTest {

    private static final class RecordingProvider implements FulltextSearchProvider {
        List<Long> lastDocumentIds;
        int calls;

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public List<RetrievalResult> search(
                String query, List<Long> documentIds, List<Long> excludeIds,
                int limit, double minScore, long embeddingProfileId) {
            calls++;
            lastDocumentIds = documentIds;
            return new ArrayList<>();
        }
    }

    @Test
    void nullScopeIsTreatedAsUnscopedSearchWithoutDocumentFilter() {
        RecordingProvider provider = new RecordingProvider();

        List<RetrievalResult> results = provider.searchInScope(
                "q", null, List.of(), 10, 0.2, 1L);

        assertEquals(1, provider.calls);
        assertEquals(null, provider.lastDocumentIds);
        assertTrue(results.isEmpty());
    }

    @Test
    void matchNoneScopeFailsClosedWithoutCallingTheBackend() {
        RecordingProvider provider = new RecordingProvider();

        List<RetrievalResult> results = provider.searchInScope(
                "q", RetrievalScope.noMatches(), List.of(), 10, 0.2, 1L);

        assertTrue(results.isEmpty());
        assertEquals(0, provider.calls);
    }

    @Test
    void nonNoneCollectionFilterFailsClosed() {
        RecordingProvider provider = new RecordingProvider();
        RetrievalScope scope = new RetrievalScope(
                RetrievalScope.CollectionFilter.SELECTED,
                List.of(3L), List.of(), null, false);

        List<RetrievalResult> results = provider.searchInScope(
                "q", scope, List.of(), 10, 0.2, 1L);

        assertTrue(results.isEmpty());
        assertEquals(0, provider.calls);
    }

    @Test
    void documentTypeFailsClosed() {
        RecordingProvider provider = new RecordingProvider();
        RetrievalScope scope = new RetrievalScope(
                RetrievalScope.CollectionFilter.NONE,
                List.of(), List.of(), "text", false);

        List<RetrievalResult> results = provider.searchInScope(
                "q", scope, List.of(), 10, 0.2, 1L);

        assertTrue(results.isEmpty());
        assertEquals(0, provider.calls);
    }

    @Test
    void noneScopeWithDocumentIdsPassesTheFilterThrough() {
        RecordingProvider provider = new RecordingProvider();
        RetrievalScope scope = new RetrievalScope(
                RetrievalScope.CollectionFilter.NONE,
                List.of(), List.of(5L, 9L), null, false);

        List<RetrievalResult> results = provider.searchInScope(
                "q", scope, List.of(), 10, 0.2, 1L);

        assertEquals(List.of(5L, 9L), provider.lastDocumentIds);
        assertTrue(results.isEmpty());
    }

    @Test
    void payloadFilterFailsClosedWithoutCallingTheBackend() {
        RecordingProvider provider = new RecordingProvider();
        RetrievalScope scope = new RetrievalScope(
                RetrievalScope.CollectionFilter.NONE,
                List.of(), List.of(), null, false);

        List<RetrievalResult> results = provider.searchInScope(
                "q", scope, List.of(), 10, 0.2, 1L,
                new com.springairag.core.retrieval.JsonbContainmentFilter("{\"k\":\"v\"}"));

        assertTrue(results.isEmpty());
        assertEquals(0, provider.calls);
    }
}
