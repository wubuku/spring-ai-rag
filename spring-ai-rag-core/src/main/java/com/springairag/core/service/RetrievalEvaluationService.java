package com.springairag.core.service;

import com.springairag.core.entity.RagRetrievalEvaluation;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * 检索效果评估服务接口
 *
 * <p>提供 IR 评估指标的计算和持久化能力：
 * <ul>
 *   <li>Precision@K — 前 K 个结果中的精确率</li>
 *   <li>Recall@K — 前 K 个结果中的召回率</li>
 *   <li>MRR — Mean Reciprocal Rank，第一个相关结果排名的倒数</li>
 *   <li>NDCG — Normalized Discounted Cumulative Gain</li>
 *   <li>Hit Rate — top-K 中是否命中至少一个相关文档</li>
 * </ul>
 */
public interface RetrievalEvaluationService {

    /**
     * 评估单次检索效果
     *
     * @param query           查询文本
     * @param retrievedDocIds 实际检索到的文档 ID 列表（按排名顺序）
     * @param relevantDocIds  期望相关的文档 ID 列表（Ground Truth）
     * @return 评估记录
     */
    RagRetrievalEvaluation evaluate(String query, List<Long> retrievedDocIds, List<Long> relevantDocIds);

    /**
     * 评估单次检索效果（带方法和评估人标识）
     */
    RagRetrievalEvaluation evaluate(String query, List<Long> retrievedDocIds, List<Long> relevantDocIds,
                                    String evaluationMethod, String evaluatorId);

    /**
     * 批量评估
     */
    List<RagRetrievalEvaluation> batchEvaluate(List<EvaluationCase> cases);

    /**
     * 计算评估指标（不持久化）
     *
     * @param retrieved 实际检索到的文档 ID 列表
     * @param relevant  期望相关的文档 ID 列表
     * @param k         计算 Precision@K 和 Recall@K 的 K 值
     * @return 评估指标
     */
    EvaluationMetrics calculateMetrics(List<Long> retrieved, List<Long> relevant, int k);

    /**
     * 获取评估报告（按时间段聚合）
     */
    EvaluationReport getReport(ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * 获取评估历史（分页）
     */
    List<RagRetrievalEvaluation> getHistory(int page, int size);

    /**
     * 获取聚合指标
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
     * 评估用例
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
     * 评估指标结果
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
    }

    /**
     * 评估报告
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
    }

    /**
     * 聚合指标
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
    }
}
