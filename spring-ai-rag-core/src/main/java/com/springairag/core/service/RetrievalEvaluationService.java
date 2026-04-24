package com.springairag.core.service;

import com.springairag.core.entity.RagRetrievalEvaluation;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Retrieval evaluation service interface.
 *
 * <p>Provides IR evaluation metric computation and persistence:
 * <ul>
 *   <li>Precision@K — precision among the top K results</li>
 *   <li>Recall@K — recall among the top K results</li>
 *   <li>MRR — Mean Reciprocal Rank, reciprocal of the first relevant result rank</li>
 *   <li>NDCG — Normalized Discounted Cumulative Gain</li>
 *   <li>Hit Rate — whether at least one relevant document is in top-K</li>
 * </ul>
 */
public interface RetrievalEvaluationService {

    /**
     * Evaluates a single retrieval.
     *
     * @param query           query text
     * @param retrievedDocIds retrieved document IDs in ranking order
     * @param relevantDocIds  expected relevant document IDs (Ground Truth)
     * @return evaluation record
     */
    RagRetrievalEvaluation evaluate(String query, List<Long> retrievedDocIds, List<Long> relevantDocIds);

    /**
     * Evaluates a single retrieval with method and evaluator identifiers.
     */
    RagRetrievalEvaluation evaluate(String query, List<Long> retrievedDocIds, List<Long> relevantDocIds,
                                    String evaluationMethod, String evaluatorId);

    /**
     * Batch evaluation.
     */
    List<RagRetrievalEvaluation> batchEvaluate(List<EvaluationCase> cases);

    /**
     * Computes evaluation metrics without persisting.
     *
     * @param retrieved retrieved document IDs
     * @param relevant  expected relevant document IDs
     * @param k         K value for Precision@K and Recall@K
     * @return evaluation metrics
     */
    EvaluationMetrics calculateMetrics(List<Long> retrieved, List<Long> relevant, int k);

    /**
     * Gets evaluation report aggregated by time range.
     */
    EvaluationReport getReport(ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * Gets evaluation history with pagination.
     */
    List<RagRetrievalEvaluation> getHistory(int page, int size);

    /**
     * Gets aggregated metrics.
     */
    AggregatedMetrics getAggregatedMetrics(ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * LLM-as-judge answer quality evaluation.
     *
     * <p>Evaluates a RAG answer using an LLM judge across three dimensions:
     * groundedness, relevance, and helpfulness (each 1-5).
     *
     * @param query  original user query
     * @param context retrieved context that should ground the answer
     * @param answer  generated RAG answer to evaluate
     * @return answer quality evaluation result (does NOT persist to DB)
     */
    AnswerQualityResult evaluateAnswerQuality(String query, String context, String answer);

    // ==================== Inner Classes ====================

    /**
     * Evaluation case.
     */
    class EvaluationCase {
        private String query;
        private List<Long> retrievedDocIds;
        private List<Long> relevantDocIds;
        private String evaluatorId;
        private String evaluationMethod = "AUTO";

        public EvaluationCase() {
        }

        public EvaluationCase(String query, List<Long> retrievedDocIds, List<Long> relevantDocIds) {
            this.query = query;
            this.retrievedDocIds = retrievedDocIds;
            this.relevantDocIds = relevantDocIds;
        }

        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public List<Long> getRetrievedDocIds() { return retrievedDocIds; }
        public void setRetrievedDocIds(List<Long> retrievedDocIds) { this.retrievedDocIds = retrievedDocIds; }
        public List<Long> getRelevantDocIds() { return relevantDocIds; }
        public void setRelevantDocIds(List<Long> relevantDocIds) { this.relevantDocIds = relevantDocIds; }
        public String getEvaluatorId() { return evaluatorId; }
        public void setEvaluatorId(String evaluatorId) { this.evaluatorId = evaluatorId; }
        public String getEvaluationMethod() { return evaluationMethod; }
        public void setEvaluationMethod(String evaluationMethod) { this.evaluationMethod = evaluationMethod; }
    }

    /**
     * Evaluation metrics result.
     */
    class EvaluationMetrics {
        private Map<Integer, Double> precisionAtK;
        private Map<Integer, Double> recallAtK;
        private double mrr;
        private double ndcg;
        private double hitRate;

        public EvaluationMetrics() {
        }

        public EvaluationMetrics(Map<Integer, Double> precisionAtK, Map<Integer, Double> recallAtK,
                                 double mrr, double ndcg, double hitRate) {
            this.precisionAtK = precisionAtK;
            this.recallAtK = recallAtK;
            this.mrr = mrr;
            this.ndcg = ndcg;
            this.hitRate = hitRate;
        }

        public Map<Integer, Double> getPrecisionAtK() { return precisionAtK; }
        public void setPrecisionAtK(Map<Integer, Double> precisionAtK) { this.precisionAtK = precisionAtK; }
        public Map<Integer, Double> getRecallAtK() { return recallAtK; }
        public void setRecallAtK(Map<Integer, Double> recallAtK) { this.recallAtK = recallAtK; }
        public double getMrr() { return mrr; }
        public void setMrr(double mrr) { this.mrr = mrr; }
        public double getNdcg() { return ndcg; }
        public void setNdcg(double ndcg) { this.ndcg = ndcg; }
        public double getHitRate() { return hitRate; }
        public void setHitRate(double hitRate) { this.hitRate = hitRate; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EvaluationMetrics that = (EvaluationMetrics) o;
            return Double.compare(that.mrr, mrr) == 0
                    && Double.compare(that.ndcg, ndcg) == 0
                    && Double.compare(that.hitRate, hitRate) == 0
                    && Objects.equals(precisionAtK, that.precisionAtK)
                    && Objects.equals(recallAtK, that.recallAtK);
        }

        @Override
        public int hashCode() {
            return Objects.hash(precisionAtK, recallAtK, mrr, ndcg, hitRate);
        }

        @Override
        public String toString() {
            return "EvaluationMetrics{" +
                    "mrr=" + mrr +
                    ", ndcg=" + ndcg +
                    ", hitRate=" + hitRate +
                    '}';
        }
    }

    /**
     * Evaluation report.
     */
    class EvaluationReport {
        private int totalEvaluations;
        private double avgPrecision;
        private double avgRecall;
        private double avgMrr;
        private double avgNdcg;
        private double avgHitRate;
        private Map<String, Object> distribution;
        private List<Map<String, Object>> trend;

        public int getTotalEvaluations() { return totalEvaluations; }
        public void setTotalEvaluations(int totalEvaluations) { this.totalEvaluations = totalEvaluations; }
        public double getAvgPrecision() { return avgPrecision; }
        public void setAvgPrecision(double avgPrecision) { this.avgPrecision = avgPrecision; }
        public double getAvgRecall() { return avgRecall; }
        public void setAvgRecall(double avgRecall) { this.avgRecall = avgRecall; }
        public double getAvgMrr() { return avgMrr; }
        public void setAvgMrr(double avgMrr) { this.avgMrr = avgMrr; }
        public double getAvgNdcg() { return avgNdcg; }
        public void setAvgNdcg(double avgNdcg) { this.avgNdcg = avgNdcg; }
        public double getAvgHitRate() { return avgHitRate; }
        public void setAvgHitRate(double avgHitRate) { this.avgHitRate = avgHitRate; }
        public Map<String, Object> getDistribution() { return distribution; }
        public void setDistribution(Map<String, Object> distribution) { this.distribution = distribution; }
        public List<Map<String, Object>> getTrend() { return trend; }
        public void setTrend(List<Map<String, Object>> trend) { this.trend = trend; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EvaluationReport that = (EvaluationReport) o;
            return totalEvaluations == that.totalEvaluations
                    && Double.compare(that.avgPrecision, avgPrecision) == 0
                    && Double.compare(that.avgRecall, avgRecall) == 0
                    && Double.compare(that.avgMrr, avgMrr) == 0
                    && Double.compare(that.avgNdcg, avgNdcg) == 0
                    && Double.compare(that.avgHitRate, avgHitRate) == 0
                    && Objects.equals(distribution, that.distribution)
                    && Objects.equals(trend, that.trend);
        }

        @Override
        public int hashCode() {
            return Objects.hash(totalEvaluations, avgPrecision, avgRecall, avgMrr, avgNdcg, avgHitRate, distribution, trend);
        }

        @Override
        public String toString() {
            return "EvaluationReport{" +
                    "totalEvaluations=" + totalEvaluations +
                    ", avgMrr=" + avgMrr +
                    ", avgNdcg=" + avgNdcg +
                    ", avgHitRate=" + avgHitRate +
                    '}';
        }
    }

    /**
     * Aggregated metrics.
     */
    class AggregatedMetrics {
        private Double avgMrr;
        private Double avgNdcg;
        private Double avgHitRate;
        private Long totalEvaluations;
        private Map<Integer, Double> avgPrecisionAtK;
        private Map<Integer, Double> avgRecallAtK;

        public Double getAvgMrr() { return avgMrr; }
        public void setAvgMrr(Double avgMrr) { this.avgMrr = avgMrr; }
        public Double getAvgNdcg() { return avgNdcg; }
        public void setAvgNdcg(Double avgNdcg) { this.avgNdcg = avgNdcg; }
        public Double getAvgHitRate() { return avgHitRate; }
        public void setAvgHitRate(Double avgHitRate) { this.avgHitRate = avgHitRate; }
        public Long getTotalEvaluations() { return totalEvaluations; }
        public void setTotalEvaluations(Long totalEvaluations) { this.totalEvaluations = totalEvaluations; }
        public Map<Integer, Double> getAvgPrecisionAtK() { return avgPrecisionAtK; }
        public void setAvgPrecisionAtK(Map<Integer, Double> avgPrecisionAtK) { this.avgPrecisionAtK = avgPrecisionAtK; }
        public Map<Integer, Double> getAvgRecallAtK() { return avgRecallAtK; }
        public void setAvgRecallAtK(Map<Integer, Double> avgRecallAtK) { this.avgRecallAtK = avgRecallAtK; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AggregatedMetrics that = (AggregatedMetrics) o;
            return Objects.equals(avgMrr, that.avgMrr)
                    && Objects.equals(avgNdcg, that.avgNdcg)
                    && Objects.equals(avgHitRate, that.avgHitRate)
                    && Objects.equals(totalEvaluations, that.totalEvaluations)
                    && Objects.equals(avgPrecisionAtK, that.avgPrecisionAtK)
                    && Objects.equals(avgRecallAtK, that.avgRecallAtK);
        }

        @Override
        public int hashCode() {
            return Objects.hash(avgMrr, avgNdcg, avgHitRate, totalEvaluations, avgPrecisionAtK, avgRecallAtK);
        }

        @Override
        public String toString() {
            return "AggregatedMetrics{" +
                    "totalEvaluations=" + totalEvaluations +
                    ", avgMrr=" + avgMrr +
                    ", avgNdcg=" + avgNdcg +
                    ", avgHitRate=" + avgHitRate +
                    '}';
        }
    }

    /**
     * LLM-as-judge answer quality evaluation result.
     *
     * <p>Three scores (1-5) plus reasoning and a summary recommendation.
     */
    class AnswerQualityResult {
        private int groundedness;   // 1-5: is answer supported by context?
        private int relevance;     // 1-5: does answer address the query?
        private int helpfulness;   // 1-5: is answer useful and clear?
        private String reasoning;
        private String recommendation; // ACCEPT / REVISION / REJECT

        public AnswerQualityResult() {
        }

        public AnswerQualityResult(int groundedness, int relevance, int helpfulness,
                                   String reasoning, String recommendation) {
            this.groundedness = groundedness;
            this.relevance = relevance;
            this.helpfulness = helpfulness;
            this.reasoning = reasoning;
            this.recommendation = recommendation;
        }

        public int getGroundedness() { return groundedness; }
        public void setGroundedness(int groundedness) { this.groundedness = groundedness; }
        public int getRelevance() { return relevance; }
        public void setRelevance(int relevance) { this.relevance = relevance; }
        public int getHelpfulness() { return helpfulness; }
        public void setHelpfulness(int helpfulness) { this.helpfulness = helpfulness; }
        public String getReasoning() { return reasoning; }
        public void setReasoning(String reasoning) { this.reasoning = reasoning; }
        public String getRecommendation() { return recommendation; }
        public void setRecommendation(String recommendation) { this.recommendation = recommendation; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AnswerQualityResult that = (AnswerQualityResult) o;
            return groundedness == that.groundedness
                    && relevance == that.relevance
                    && helpfulness == that.helpfulness
                    && Objects.equals(reasoning, that.reasoning)
                    && Objects.equals(recommendation, that.recommendation);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groundedness, relevance, helpfulness, reasoning, recommendation);
        }

        @Override
        public String toString() {
            return "AnswerQualityResult{" +
                    "groundedness=" + groundedness +
                    ", relevance=" + relevance +
                    ", helpfulness=" + helpfulness +
                    ", recommendation=" + recommendation +
                    '}';
        }
    }
}
