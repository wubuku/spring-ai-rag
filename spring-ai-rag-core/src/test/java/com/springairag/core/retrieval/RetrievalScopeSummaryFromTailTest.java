package com.springairag.core.retrieval;

import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.core.config.EmbeddingProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetrievalScopeSummary.from 长尾（Batch 582，JaCoCo 驱动）：null
 * scope/filters 回退、SELECTED 集合计数与键清单（空键过滤 + 100 上
 * 限截断）、inferMode 三分支、documentType 与 profile 可选键、match
 * None 零集合、filter 摘要 absent/present、countTopLevelKeys 边界。
 */
class RetrievalScopeSummaryFromTailTest {

    private static EmbeddingProfile profile(String profileKey) {
        return new EmbeddingProfile(
                9L, profileKey, "siliconflow", "BAAI/bge-m3", "v1",
                1024, "COSINE", "NONE", true);
    }

    private static RetrievalScope selected(List<Long> ids) {
        return RetrievalScope.selectedCollections(ids, List.of(), null);
    }

    @Test
    void fromInfersCallerVisibleForNoneFilter() {
        var summary = RetrievalScopeSummary.from(
                null,
                RetrievalScope.unscoped(),
                null,
                null,
                null);

        assertEquals("CALLER_VISIBLE",
                summary.get("collectionScopeMode"));
        assertEquals(0, summary.get("documentIdCount"));
        assertEquals(Boolean.FALSE, summary.get("matchNone"));
        assertTrue(!summary.containsKey("embeddingProfileKey"));
    }

    @Test
    void fromInfersAnyCollectionForAnyAssignedFilter() {
        var summary = RetrievalScopeSummary.from(
                null,
                new RetrievalScope(
                        RetrievalScope.CollectionFilter.ANY_ASSIGNED,
                        List.of(), List.of(), null, false),
                null, null, null);

        assertEquals("ANY_COLLECTION", summary.get("collectionScopeMode"));
    }

    @Test
    void fromCountsSelectedCollectionsAndSanitizesKeys() {
        var scope = selected(List.of(10L, 11L));

        var summary = RetrievalScopeSummary.from(
                null, scope,
                arrayList("kb-a", " ", null, "kb-b"),
                null, null);

        assertEquals(2, summary.get("collectionCount"));
        assertEquals(List.of("kb-a", "kb-b"), summary.get("collectionKeys"));
    }

    @Test
    void fromCapsSelectedKeysAtHundred() {
        var keys = new ArrayList<String>();
        for (int i = 0; i < 150; i++) {
            keys.add("kb-" + i);
        }
        var scope = selected(List.of(1L));

        var summary = RetrievalScopeSummary.from(
                null, scope, keys, null, null);

        assertEquals(100,
                ((List<?>) summary.get("collectionKeys")).size());
    }

    @Test
    void fromWritesDocumentTypeAndProfileKeyWhenPresent() {
        var embeddingProfile = profile("bge-m3");
        var scope = new RetrievalScope(
                RetrievalScope.CollectionFilter.NONE,
                List.of(), List.of(), "pdf", false);

        var summary = RetrievalScopeSummary.from(
                null, scope, null, null, embeddingProfile);

        assertEquals("pdf", summary.get("documentType"));
        assertEquals("bge-m3", summary.get("embeddingProfileKey"));
    }

    @Test
    void matchNoneScopeReportsZeroCollections() {
        var summary = RetrievalScopeSummary.from(
                null,
                new RetrievalScope(
                        RetrievalScope.CollectionFilter.NONE,
                        List.of(), List.of(), null, true),
                null, null, null);

        assertEquals(Boolean.TRUE, summary.get("matchNone"));
        assertEquals(0, summary.get("collectionCount"));
    }

    @Test
    void filterSummaryTreatsNullMetadataAsAbsent() {
        var summary = RetrievalScopeSummary.filterSummary(null);

        var metadata = (Map<?, ?>) summary.get("metadataContains");
        assertEquals(Boolean.FALSE, metadata.get("present"));
        assertEquals(0, metadata.get("canonicalBytes"));
        assertTrue(((List<?>) summary.get("payloadContains")).isEmpty());
    }

    @Test
    void filterSummaryCountsCanonicalBytesAndTopLevelKeys()
            throws Exception {
        var filters = new RetrievalFilters(
                new JsonbContainmentFilter(
                        "{\"brand\":\"acme\",\"year\":2026}"),
                List.of(new JsonbContainmentFilter("{\"tag\":\"x\"}")));

        var summary = RetrievalScopeSummary.filterSummary(filters);

        var metadata = (Map<?, ?>) summary.get("metadataContains");
        assertEquals(Boolean.TRUE, metadata.get("present"));
        assertEquals(2, metadata.get("topLevelKeyCount"));
        var payload = (List<?>) summary.get("payloadContains");
        assertEquals(1, payload.size());
        assertEquals(1, ((Map<?, ?>) payload.getFirst()).get("topLevelKeyCount"));
    }

    @Test
    void countTopLevelKeysHandlesBlankInvalidAndNonObject() {
        assertEquals(0, RetrievalScopeSummary.countTopLevelKeys(null));
        assertEquals(0, RetrievalScopeSummary.countTopLevelKeys("  "));
        assertEquals(0, RetrievalScopeSummary.countTopLevelKeys("[1,2]"));
        assertEquals(3, RetrievalScopeSummary.countTopLevelKeys(
                "{\"a\":1,\"b\":2,\"c\":3}"));
    }

    private static List<String> arrayList(String... values) {
        List<String> list = new ArrayList<>();
        for (String value : values) {
            list.add(value);
        }
        return list;
    }
}
