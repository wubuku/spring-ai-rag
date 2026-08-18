package com.springairag.core.retrieval;

/**
 * 只能由可观察事实支撑的检索结果/空结果代码。
 */
public final class RetrievalOutcomeCodes {

    public static final String RESULTS_RETURNED = "RESULTS_RETURNED";
    public static final String SCOPE_MATCH_NONE = "SCOPE_MATCH_NONE";
    public static final String NO_ELIGIBLE_DOCUMENTS = "NO_ELIGIBLE_DOCUMENTS";
    public static final String NO_FRESH_EMBEDDINGS = "NO_FRESH_EMBEDDINGS";
    public static final String NO_CANDIDATES = "NO_CANDIDATES";
    public static final String BELOW_MIN_SCORE = "BELOW_MIN_SCORE";
    public static final String VECTOR_TIMEOUT = "VECTOR_TIMEOUT";
    public static final String FULLTEXT_TIMEOUT = "FULLTEXT_TIMEOUT";
    public static final String VECTOR_ERROR = "VECTOR_ERROR";
    public static final String FULLTEXT_ERROR = "FULLTEXT_ERROR";
    public static final String PARTIAL_VECTOR = "PARTIAL_VECTOR";
    public static final String PARTIAL_FULLTEXT = "PARTIAL_FULLTEXT";
    public static final String RERANK_DEGRADED = "RERANK_DEGRADED";
    public static final String RETRIEVAL_BUDGET_EXHAUSTED = "RETRIEVAL_BUDGET_EXHAUSTED";
    public static final String DIAGNOSTIC_UNKNOWN = "DIAGNOSTIC_UNKNOWN";

    private RetrievalOutcomeCodes() {
    }

    public record Resolved(String outcomeCode, String emptyReasonCode) {
    }

    public static Resolved resolve(
            boolean matchNone,
            boolean budgetExhausted,
            boolean rerankDegraded,
            int finalResultCount,
            RetrievalBranchStage vector,
            RetrievalBranchStage fulltext,
            Integer eligibleDocuments,
            Integer freshEmbeddings,
            boolean probeFailed,
            int rawCandidateCount) {
        if (budgetExhausted && finalResultCount <= 0) {
            return new Resolved(RETRIEVAL_BUDGET_EXHAUSTED, RETRIEVAL_BUDGET_EXHAUSTED);
        }
        if (matchNone) {
            return new Resolved(SCOPE_MATCH_NONE, SCOPE_MATCH_NONE);
        }
        if (finalResultCount > 0) {
            if (rerankDegraded) {
                return new Resolved(RERANK_DEGRADED, null);
            }
            if (isPartial(vector, fulltext, true)) {
                return new Resolved(PARTIAL_VECTOR, null);
            }
            if (isPartial(fulltext, vector, true)) {
                return new Resolved(PARTIAL_FULLTEXT, null);
            }
            return new Resolved(RESULTS_RETURNED, null);
        }
        if (vector != null && vector.timedOut() && !hasValidResults(fulltext)) {
            return new Resolved(VECTOR_TIMEOUT, VECTOR_TIMEOUT);
        }
        if (fulltext != null && fulltext.timedOut() && !hasValidResults(vector)) {
            return new Resolved(FULLTEXT_TIMEOUT, FULLTEXT_TIMEOUT);
        }
        if (vector != null && vector.failed() && !hasValidResults(fulltext)) {
            return new Resolved(VECTOR_ERROR, VECTOR_ERROR);
        }
        if (fulltext != null && fulltext.failed() && !hasValidResults(vector)) {
            return new Resolved(FULLTEXT_ERROR, FULLTEXT_ERROR);
        }
        if (probeFailed) {
            return new Resolved(DIAGNOSTIC_UNKNOWN, DIAGNOSTIC_UNKNOWN);
        }
        if (eligibleDocuments != null && eligibleDocuments == 0) {
            return new Resolved(NO_ELIGIBLE_DOCUMENTS, NO_ELIGIBLE_DOCUMENTS);
        }
        if (eligibleDocuments != null
                && eligibleDocuments > 0
                && freshEmbeddings != null
                && freshEmbeddings == 0) {
            return new Resolved(NO_FRESH_EMBEDDINGS, NO_FRESH_EMBEDDINGS);
        }
        if (rawCandidateCount > 0) {
            return new Resolved(BELOW_MIN_SCORE, BELOW_MIN_SCORE);
        }
        return new Resolved(NO_CANDIDATES, NO_CANDIDATES);
    }

    private static boolean hasValidResults(RetrievalBranchStage stage) {
        return stage != null && stage.succeeded() && stage.resultCount() > 0;
    }

    private static boolean isPartial(
            RetrievalBranchStage produced,
            RetrievalBranchStage other,
            boolean requireOtherAttempted) {
        if (!hasValidResults(produced)) {
            return false;
        }
        if (other == null || !other.attempted()) {
            return false;
        }
        return requireOtherAttempted && !hasValidResults(other);
    }
}
