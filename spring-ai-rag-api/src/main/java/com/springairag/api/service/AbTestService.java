package com.springairag.api.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * A/B 测试服务接口
 *
 * <p>支持检索策略对比实验，通过流量分割将用户分配到不同变体，
 * 记录检索指标并进行统计分析，找出最优策略。
 */
public interface AbTestService {

    /** 创建实验 */
    Experiment createExperiment(CreateExperimentRequest request);

    /** 更新实验（仅 DRAFT/PAUSED 状态可编辑） */
    void updateExperiment(Long id, UpdateExperimentRequest request);

    /** 启动实验 */
    void startExperiment(Long id);

    /** 暂停实验 */
    void pauseExperiment(Long id);

    /** 停止实验 */
    void stopExperiment(Long id);

    /** 获取正在运行的实验 */
    List<Experiment> getRunningExperiments();

    /** 根据 sessionId 获取应分配的变体 */
    String getVariantForSession(String sessionId, Long experimentId);

    /** 记录实验结果 */
    void recordResult(Long experimentId, String variantName, String sessionId,
                      String query, List<Long> retrievedDocIds,
                      Map<String, Double> metrics);

    /** 分析实验结果 */
    ExperimentAnalysis analyzeExperiment(Long experimentId);

    /** 获取实验结果列表（分页） */
    List<ExperimentResult> getExperimentResults(Long experimentId, int page, int size);

    // ==================== Inner DTOs ====================

    /** 实验 */
    class Experiment {
        private Long id;
        private String experimentName;
        private String description;
        private String status;
        private Map<String, Double> trafficSplit;
        private String targetMetric;
        private Integer minSampleSize;
        private ZonedDateTime startTime;
        private ZonedDateTime endTime;
        private ZonedDateTime createdAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getExperimentName() { return experimentName; }
        public void setExperimentName(String experimentName) { this.experimentName = experimentName; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Map<String, Double> getTrafficSplit() { return trafficSplit; }
        public void setTrafficSplit(Map<String, Double> trafficSplit) { this.trafficSplit = trafficSplit; }
        public String getTargetMetric() { return targetMetric; }
        public void setTargetMetric(String targetMetric) { this.targetMetric = targetMetric; }
        public Integer getMinSampleSize() { return minSampleSize; }
        public void setMinSampleSize(Integer minSampleSize) { this.minSampleSize = minSampleSize; }
        public ZonedDateTime getStartTime() { return startTime; }
        public void setStartTime(ZonedDateTime startTime) { this.startTime = startTime; }
        public ZonedDateTime getEndTime() { return endTime; }
        public void setEndTime(ZonedDateTime endTime) { this.endTime = endTime; }
        public ZonedDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(ZonedDateTime createdAt) { this.createdAt = createdAt; }
    }

    /** 实验结果 */
    class ExperimentResult {
        private Long id;
        private String variantName;
        private String sessionId;
        private String query;
        private Map<String, Double> metrics;
        private Boolean isConverted;
        private ZonedDateTime createdAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getVariantName() { return variantName; }
        public void setVariantName(String variantName) { this.variantName = variantName; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public Map<String, Double> getMetrics() { return metrics; }
        public void setMetrics(Map<String, Double> metrics) { this.metrics = metrics; }
        public Boolean getIsConverted() { return isConverted; }
        public void setIsConverted(Boolean isConverted) { this.isConverted = isConverted; }
        public ZonedDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(ZonedDateTime createdAt) { this.createdAt = createdAt; }
    }

    /** 创建实验请求 */
    class CreateExperimentRequest {
        private String experimentName;
        private String description;
        private Map<String, Double> trafficSplit;
        private String targetMetric;
        private Integer minSampleSize;

        public String getExperimentName() { return experimentName; }
        public void setExperimentName(String experimentName) { this.experimentName = experimentName; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Map<String, Double> getTrafficSplit() { return trafficSplit; }
        public void setTrafficSplit(Map<String, Double> trafficSplit) { this.trafficSplit = trafficSplit; }
        public String getTargetMetric() { return targetMetric; }
        public void setTargetMetric(String targetMetric) { this.targetMetric = targetMetric; }
        public Integer getMinSampleSize() { return minSampleSize; }
        public void setMinSampleSize(Integer minSampleSize) { this.minSampleSize = minSampleSize; }
    }

    /** 更新实验请求 */
    class UpdateExperimentRequest {
        private String description;
        private Map<String, Double> trafficSplit;
        private String targetMetric;
        private Integer minSampleSize;

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Map<String, Double> getTrafficSplit() { return trafficSplit; }
        public void setTrafficSplit(Map<String, Double> trafficSplit) { this.trafficSplit = trafficSplit; }
        public String getTargetMetric() { return targetMetric; }
        public void setTargetMetric(String targetMetric) { this.targetMetric = targetMetric; }
        public Integer getMinSampleSize() { return minSampleSize; }
        public void setMinSampleSize(Integer minSampleSize) { this.minSampleSize = minSampleSize; }
    }

    /** 实验分析结果 */
    class ExperimentAnalysis {
        private Long experimentId;
        private String status;
        private Map<String, VariantStats> variantStats;
        private String winner;
        private double confidenceLevel;
        private boolean isSignificant;
        private String recommendation;
        private ZonedDateTime analyzedAt;

        public Long getExperimentId() { return experimentId; }
        public void setExperimentId(Long experimentId) { this.experimentId = experimentId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Map<String, VariantStats> getVariantStats() { return variantStats; }
        public void setVariantStats(Map<String, VariantStats> variantStats) { this.variantStats = variantStats; }
        public String getWinner() { return winner; }
        public void setWinner(String winner) { this.winner = winner; }
        public double getConfidenceLevel() { return confidenceLevel; }
        public void setConfidenceLevel(double confidenceLevel) { this.confidenceLevel = confidenceLevel; }
        public boolean isIsSignificant() { return isSignificant; }
        public void setIsSignificant(boolean isSignificant) { this.isSignificant = isSignificant; }
        public String getRecommendation() { return recommendation; }
        public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
        public ZonedDateTime getAnalyzedAt() { return analyzedAt; }
        public void setAnalyzedAt(ZonedDateTime analyzedAt) { this.analyzedAt = analyzedAt; }
    }

    /** 变体统计 */
    class VariantStats {
        private String variantName;
        private int sampleSize;
        private double meanMetric;
        private double variance;
        private double stdDev;
        private double conversionRate;

        public String getVariantName() { return variantName; }
        public void setVariantName(String variantName) { this.variantName = variantName; }
        public int getSampleSize() { return sampleSize; }
        public void setSampleSize(int sampleSize) { this.sampleSize = sampleSize; }
        public double getMeanMetric() { return meanMetric; }
        public void setMeanMetric(double meanMetric) { this.meanMetric = meanMetric; }
        public double getVariance() { return variance; }
        public void setVariance(double variance) { this.variance = variance; }
        public double getStdDev() { return stdDev; }
        public void setStdDev(double stdDev) { this.stdDev = stdDev; }
        public double getConversionRate() { return conversionRate; }
        public void setConversionRate(double conversionRate) { this.conversionRate = conversionRate; }
    }
}
