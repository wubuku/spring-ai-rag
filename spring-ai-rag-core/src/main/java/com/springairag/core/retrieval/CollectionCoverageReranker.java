package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;

import java.util.List;

/** EACH_COLLECTION 的候选池完整重排与最终覆盖选择。 */
public final class CollectionCoverageReranker {

    private CollectionCoverageReranker() {
    }

    public static Result rerank(
            String query,
            List<RetrievalResult> candidates,
            List<Long> collectionIds,
            int finalLimit,
            ReRankingService rerankingService) {
        List<RetrievalResult> source = candidates == null ? List.of() : candidates;
        if (source.isEmpty() || !rerankingService.isProviderActive()) {
            return new Result(
                    CollectionCoverageMerger.retainCoverage(
                            source, collectionIds, finalLimit),
                    false,
                    false,
                    null);
        }
        try {
            List<RetrievalResult> ranked = rerankingService.rerankAllCandidates(
                    query, source);
            if (!CollectionCoverageMerger.isCompleteRanking(
                    source, ranked, collectionIds)) {
                return fallback(source, collectionIds, finalLimit, "RERANK_INCOMPLETE");
            }
            return new Result(
                    CollectionCoverageMerger.retainCoverageInRankOrder(
                            ranked, collectionIds, finalLimit),
                    false,
                    true,
                    null);
        } catch (RuntimeException e) {
            com.springairag.core.admission.AdmissionRejectedException.propagate(e);
            return fallback(source, collectionIds, finalLimit, "RERANK_ERROR");
        }
    }

    private static Result fallback(
            List<RetrievalResult> candidates,
            List<Long> collectionIds,
            int finalLimit,
            String errorCode) {
        return new Result(
                CollectionCoverageMerger.retainCoverage(
                        candidates, collectionIds, finalLimit),
                true,
                true,
                errorCode);
    }

    public record Result(
            List<RetrievalResult> results,
            boolean degraded,
            boolean executed,
            String errorCode) {
        public Result {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }
}
