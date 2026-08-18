package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内部详细检索结果。公开 list API 只取 {@link #results()}。
 */
public record RetrievalOutcome(
        UUID traceId,
        List<RetrievalResult> results,
        String originalQuery,
        List<QueryStat> effectiveQueries,
        Map<String, Object> scopeSummary,
        Map<String, Object> filterSummary,
        List<RetrievalBranchStage> branchStages,
        RetrievalBranchStage fusionStage,
        RetrievalBranchStage rerankStage,
        String outcomeCode,
        String emptyReasonCode,
        long elapsedMs,
        int rawCandidateCount) {

    public RetrievalOutcome {
        traceId = traceId != null ? traceId : UUID.randomUUID();
        results = results == null ? List.of() : List.copyOf(results);
        effectiveQueries = effectiveQueries == null ? List.of() : List.copyOf(effectiveQueries);
        scopeSummary = scopeSummary == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(scopeSummary));
        filterSummary = filterSummary == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(filterSummary));
        branchStages = branchStages == null ? List.of() : List.copyOf(branchStages);
    }

    public static RetrievalOutcome ofResults(List<RetrievalResult> results) {
        List<RetrievalResult> copy = results == null ? List.of() : List.copyOf(results);
        String code = copy.isEmpty()
                ? RetrievalOutcomeCodes.NO_CANDIDATES
                : RetrievalOutcomeCodes.RESULTS_RETURNED;
        return new RetrievalOutcome(
                UUID.randomUUID(),
                copy,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                null,
                null,
                code,
                copy.isEmpty() ? code : null,
                0L,
                copy.size());
    }

    public RetrievalOutcome withTraceId(UUID id) {
        return new RetrievalOutcome(
                id, results, originalQuery, effectiveQueries, scopeSummary,
                filterSummary, branchStages, fusionStage, rerankStage,
                outcomeCode, emptyReasonCode, elapsedMs, rawCandidateCount);
    }

    public RetrievalOutcome withRerank(
            RetrievalBranchStage rerank,
            List<RetrievalResult> reranked,
            boolean degraded) {
        RetrievalOutcomeCodes.Resolved resolved = RetrievalOutcomeCodes.resolve(
                false,
                false,
                degraded,
                reranked == null ? 0 : reranked.size(),
                vectorStage(),
                fulltextStage(),
                null,
                null,
                false,
                rawCandidateCount);
        return new RetrievalOutcome(
                traceId,
                reranked,
                originalQuery,
                effectiveQueries,
                scopeSummary,
                filterSummary,
                branchStages,
                fusionStage,
                rerank,
                resolved.outcomeCode(),
                resolved.emptyReasonCode(),
                elapsedMs + (rerank == null ? 0 : Math.max(0, rerank.elapsedMs())),
                rawCandidateCount);
    }

    public RetrievalBranchStage vectorStage() {
        return findStage(RetrievalBranchStage.VECTOR);
    }

    public RetrievalBranchStage fulltextStage() {
        return findStage(RetrievalBranchStage.FULLTEXT);
    }

    public Map<String, Object> positionalScores(int maxItems) {
        Map<String, Object> scores = new LinkedHashMap<>();
        int limit = Math.min(Math.max(0, maxItems), results.size());
        for (int i = 0; i < limit; i++) {
            scores.put("rank_" + (i + 1), results.get(i).getScore());
        }
        return scores;
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("originalQueryChars", originalQuery == null ? 0 : originalQuery.length());
        List<Map<String, Object>> queries = new ArrayList<>();
        for (QueryStat stat : effectiveQueries) {
            queries.add(stat.toMap());
        }
        map.put("effectiveQueries", queries);
        map.put("scope", scopeSummary);
        map.put("filter", filterSummary);
        List<Map<String, Object>> stages = new ArrayList<>();
        for (RetrievalBranchStage stage : branchStages) {
            stages.add(stage.toMap());
        }
        map.put("branchStages", stages);
        if (fusionStage != null) {
            map.put("fusionStage", fusionStage.toMap());
        }
        if (rerankStage != null) {
            map.put("rerankStage", rerankStage.toMap());
        }
        map.put("rawCandidateCount", rawCandidateCount);
        return map;
    }

    private RetrievalBranchStage findStage(String branch) {
        for (RetrievalBranchStage stage : branchStages) {
            if (branch.equals(stage.branch())) {
                return stage;
            }
        }
        return null;
    }

    public record QueryStat(int index, int charCount) {
        public Map<String, Object> toMap() {
            return Map.of("index", index, "charCount", charCount);
        }
    }
}
