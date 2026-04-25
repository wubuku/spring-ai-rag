package com.springairag.api.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A/B test service interface
 *
 * <p>Supports retrieval strategy comparison experiments, assigns users to different variants
 * via traffic splitting, records retrieval metrics and performs statistical analysis to find
 * the optimal strategy.
 */
public interface AbTestService {

    /** Create a new experiment */
    Experiment createExperiment(CreateExperimentRequest request);

    /** Update experiment (only DRAFT/PAUSED status is editable) */
    void updateExperiment(Long id, UpdateExperimentRequest request);

    /** Start the experiment */
    void startExperiment(Long id);

    /** Pause the experiment */
    void pauseExperiment(Long id);

    /** Stop the experiment */
    void stopExperiment(Long id);

    /** Get all running experiments */
    List<Experiment> getRunningExperiments();

    /** Get the variant assigned to a session by sessionId */
    String getVariantForSession(String sessionId, Long experimentId);

    /** Record an experiment result */
    void recordResult(Long experimentId, String variantName, String sessionId,
                      String query, List<Long> retrievedDocIds,
                      Map<String, Double> metrics);

    /** Analyze experiment results */
    ExperimentAnalysis analyzeExperiment(Long experimentId);

    /** Get paginated experiment results */
    List<ExperimentResult> getExperimentResults(Long experimentId, int page, int size);

    // ==================== Inner DTOs ====================

    /** Experiment */
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Experiment that = (Experiment) o;
            return Objects.equals(id, that.id)
                    && Objects.equals(experimentName, that.experimentName)
                    && Objects.equals(description, that.description)
                    && Objects.equals(status, that.status)
                    && Objects.equals(trafficSplit, that.trafficSplit)
                    && Objects.equals(targetMetric, that.targetMetric)
                    && Objects.equals(minSampleSize, that.minSampleSize)
                    && Objects.equals(startTime, that.startTime)
                    && Objects.equals(endTime, that.endTime)
                    && Objects.equals(createdAt, that.createdAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, experimentName, description, status, trafficSplit,
                    targetMetric, minSampleSize, startTime, endTime, createdAt);
        }

        @Override
        public String toString() {
            return "Experiment{" +
                    "id=" + id +
                    ", experimentName='" + experimentName + '\'' +
                    ", description='" + description + '\'' +
                    ", status='" + status + '\'' +
                    ", trafficSplit=" + trafficSplit +
                    ", targetMetric='" + targetMetric + '\'' +
                    ", minSampleSize=" + minSampleSize +
                    ", startTime=" + startTime +
                    ", endTime=" + endTime +
                    ", createdAt=" + createdAt +
                    '}';
        }
    }

    /** Experiment result */
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ExperimentResult that = (ExperimentResult) o;
            return Objects.equals(id, that.id)
                    && Objects.equals(variantName, that.variantName)
                    && Objects.equals(sessionId, that.sessionId)
                    && Objects.equals(query, that.query)
                    && Objects.equals(metrics, that.metrics)
                    && Objects.equals(isConverted, that.isConverted)
                    && Objects.equals(createdAt, that.createdAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, variantName, sessionId, query, metrics, isConverted, createdAt);
        }

        @Override
        public String toString() {
            return "ExperimentResult{" +
                    "id=" + id +
                    ", variantName='" + variantName + '\'' +
                    ", sessionId='" + sessionId + '\'' +
                    ", query='" + query + '\'' +
                    ", metrics=" + metrics +
                    ", isConverted=" + isConverted +
                    ", createdAt=" + createdAt +
                    '}';
        }
    }

    /** Create experiment request */
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CreateExperimentRequest that = (CreateExperimentRequest) o;
            return Objects.equals(experimentName, that.experimentName)
                    && Objects.equals(description, that.description)
                    && Objects.equals(trafficSplit, that.trafficSplit)
                    && Objects.equals(targetMetric, that.targetMetric)
                    && Objects.equals(minSampleSize, that.minSampleSize);
        }

        @Override
        public int hashCode() {
            return Objects.hash(experimentName, description, trafficSplit, targetMetric, minSampleSize);
        }

        @Override
        public String toString() {
            return "CreateExperimentRequest{" +
                    "experimentName='" + experimentName + '\'' +
                    ", description='" + description + '\'' +
                    ", trafficSplit=" + trafficSplit +
                    ", targetMetric='" + targetMetric + '\'' +
                    ", minSampleSize=" + minSampleSize +
                    '}';
        }
    }

    /** Update experiment request */
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UpdateExperimentRequest that = (UpdateExperimentRequest) o;
            return Objects.equals(description, that.description)
                    && Objects.equals(trafficSplit, that.trafficSplit)
                    && Objects.equals(targetMetric, that.targetMetric)
                    && Objects.equals(minSampleSize, that.minSampleSize);
        }

        @Override
        public int hashCode() {
            return Objects.hash(description, trafficSplit, targetMetric, minSampleSize);
        }

        @Override
        public String toString() {
            return "UpdateExperimentRequest{" +
                    "description='" + description + '\'' +
                    ", trafficSplit=" + trafficSplit +
                    ", targetMetric='" + targetMetric + '\'' +
                    ", minSampleSize=" + minSampleSize +
                    '}';
        }
    }

    /** Experiment analysis result */
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ExperimentAnalysis that = (ExperimentAnalysis) o;
            return Objects.equals(experimentId, that.experimentId)
                    && Objects.equals(status, that.status)
                    && Objects.equals(variantStats, that.variantStats)
                    && Objects.equals(winner, that.winner)
                    && Double.compare(that.confidenceLevel, confidenceLevel) == 0
                    && isSignificant == that.isSignificant
                    && Objects.equals(recommendation, that.recommendation)
                    && Objects.equals(analyzedAt, that.analyzedAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(experimentId, status, variantStats, winner,
                    confidenceLevel, isSignificant, recommendation, analyzedAt);
        }

        @Override
        public String toString() {
            return "ExperimentAnalysis{" +
                    "experimentId=" + experimentId +
                    ", status='" + status + '\'' +
                    ", variantStats=" + variantStats +
                    ", winner='" + winner + '\'' +
                    ", confidenceLevel=" + confidenceLevel +
                    ", isSignificant=" + isSignificant +
                    ", recommendation='" + recommendation + '\'' +
                    ", analyzedAt=" + analyzedAt +
                    '}';
        }
    }

    /** Variant statistics */
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            VariantStats that = (VariantStats) o;
            return sampleSize == that.sampleSize
                    && Double.compare(that.meanMetric, meanMetric) == 0
                    && Double.compare(that.variance, variance) == 0
                    && Double.compare(that.stdDev, stdDev) == 0
                    && Double.compare(that.conversionRate, conversionRate) == 0
                    && Objects.equals(variantName, that.variantName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(variantName, sampleSize, meanMetric, variance, stdDev, conversionRate);
        }

        @Override
        public String toString() {
            return "VariantStats{" +
                    "variantName='" + variantName + '\'' +
                    ", sampleSize=" + sampleSize +
                    ", meanMetric=" + meanMetric +
                    ", variance=" + variance +
                    ", stdDev=" + stdDev +
                    ", conversionRate=" + conversionRate +
                    '}';
        }
    }
}
