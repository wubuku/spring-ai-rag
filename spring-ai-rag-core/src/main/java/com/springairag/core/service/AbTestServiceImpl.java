package com.springairag.core.service;

import com.springairag.api.service.AbTestService;
import com.springairag.core.entity.RagAbExperiment;
import com.springairag.core.entity.RagAbResult;
import com.springairag.core.repository.RagAbExperimentRepository;
import com.springairag.core.repository.RagAbResultRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A/B 测试服务实现
 */
@Service
@Transactional
public class AbTestServiceImpl implements AbTestService {

    private static final Logger log = LoggerFactory.getLogger(AbTestServiceImpl.class);

    private final RagAbExperimentRepository experimentRepository;
    private final RagAbResultRepository resultRepository;
    private final ObjectMapper objectMapper;

    public AbTestServiceImpl(RagAbExperimentRepository experimentRepository,
                             RagAbResultRepository resultRepository,
                             ObjectMapper objectMapper) {
        this.experimentRepository = experimentRepository;
        this.resultRepository = resultRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Experiment createExperiment(CreateExperimentRequest request) {
        if (experimentRepository.existsByExperimentName(request.getExperimentName())) {
            throw new IllegalArgumentException("Experiment '" + request.getExperimentName() + "' already exists");
        }

        RagAbExperiment entity = new RagAbExperiment();
        entity.setExperimentName(request.getExperimentName());
        entity.setDescription(request.getDescription());
        entity.setTrafficSplit(request.getTrafficSplit());
        entity.setTargetMetric(request.getTargetMetric());
        entity.setMinSampleSize(request.getMinSampleSize() != null ? request.getMinSampleSize() : 100);
        entity.setStatus("DRAFT");
        entity.setCreatedAt(ZonedDateTime.now());

        RagAbExperiment saved = experimentRepository.save(entity);
        log.info("Created experiment: {}", saved.getExperimentName());
        return toExperiment(saved);
    }

    @Override
    public void updateExperiment(Long id, UpdateExperimentRequest request) {
        RagAbExperiment entity = experimentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + id));

        if ("RUNNING".equals(entity.getStatus())) {
            throw new IllegalStateException("Cannot update a running experiment");
        }

        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }
        if (request.getTrafficSplit() != null) {
            entity.setTrafficSplit(request.getTrafficSplit());
        }
        if (request.getTargetMetric() != null) {
            entity.setTargetMetric(request.getTargetMetric());
        }
        if (request.getMinSampleSize() != null) {
            entity.setMinSampleSize(request.getMinSampleSize());
        }
        entity.setUpdatedAt(ZonedDateTime.now());
        experimentRepository.save(entity);
    }

    @Override
    public void startExperiment(Long id) {
        RagAbExperiment entity = experimentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + id));

        if (!"DRAFT".equals(entity.getStatus()) && !"PAUSED".equals(entity.getStatus())) {
            throw new IllegalStateException("Can only start DRAFT or PAUSED experiments");
        }

        entity.setStatus("RUNNING");
        entity.setStartTime(ZonedDateTime.now());
        entity.setUpdatedAt(ZonedDateTime.now());
        experimentRepository.save(entity);
        log.info("Started experiment: {}", entity.getExperimentName());
    }

    @Override
    public void pauseExperiment(Long id) {
        RagAbExperiment entity = experimentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + id));

        if (!"RUNNING".equals(entity.getStatus())) {
            throw new IllegalStateException("Can only pause RUNNING experiments");
        }

        entity.setStatus("PAUSED");
        entity.setUpdatedAt(ZonedDateTime.now());
        experimentRepository.save(entity);
        log.info("Paused experiment: {}", entity.getExperimentName());
    }

    @Override
    public void stopExperiment(Long id) {
        RagAbExperiment entity = experimentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + id));

        entity.setStatus("COMPLETED");
        entity.setEndTime(ZonedDateTime.now());
        entity.setUpdatedAt(ZonedDateTime.now());
        experimentRepository.save(entity);
        log.info("Stopped experiment: {}", entity.getExperimentName());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Experiment> getRunningExperiments() {
        return experimentRepository.findRunningExperiments().stream()
                .map(this::toExperiment)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String getVariantForSession(String sessionId, Long experimentId) {
        RagAbExperiment entity = experimentRepository.findById(experimentId)
                .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + experimentId));

        int hash = Math.abs(Objects.hash(sessionId, experimentId) % 100);
        Map<String, Double> trafficSplit = entity.getTrafficSplit();
        int cumulative = 0;

        for (Map.Entry<String, Double> entry : trafficSplit.entrySet()) {
            cumulative += (int) (entry.getValue() * 100);
            if (hash < cumulative) {
                return entry.getKey();
            }
        }
        return "control";
    }

    @Override
    public void recordResult(Long experimentId, String variantName, String sessionId,
                             String query, List<Long> retrievedDocIds,
                             Map<String, Double> metrics) {
        if (resultRepository.existsBySessionIdAndExperimentId(sessionId, experimentId)) {
            log.debug("Duplicate result for session {} in experiment {}", sessionId, experimentId);
            return;
        }

        RagAbExperiment experiment = experimentRepository.findById(experimentId)
                .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + experimentId));

        RagAbResult result = new RagAbResult();
        result.setExperiment(experiment);
        result.setVariantName(variantName);
        result.setSessionId(sessionId);
        result.setQuery(query);
        if (retrievedDocIds != null) {
            try {
                result.setRetrievedDocumentIds(objectMapper.writeValueAsString(retrievedDocIds));
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize doc IDs", e);
                result.setRetrievedDocumentIds("[]");
            }
        }
        result.setMetrics(metrics);
        result.setIsConverted(metrics != null && metrics.containsKey("converted") && metrics.get("converted") > 0);
        result.setCreatedAt(ZonedDateTime.now());

        resultRepository.save(result);
    }

    @Override
    @Transactional(readOnly = true)
    public ExperimentAnalysis analyzeExperiment(Long experimentId) {
        RagAbExperiment experiment = experimentRepository.findById(experimentId)
                .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + experimentId));

        List<RagAbResult> results = resultRepository.findByExperimentId(experimentId);
        Map<String, List<RagAbResult>> resultsByVariant = results.stream()
                .collect(Collectors.groupingBy(RagAbResult::getVariantName));

        Map<String, VariantStats> variantStatsMap = new HashMap<>();
        for (var entry : resultsByVariant.entrySet()) {
            variantStatsMap.put(entry.getKey(), calculateVariantStats(entry.getKey(), entry.getValue(), experiment));
        }

        ExperimentAnalysis analysis = new ExperimentAnalysis();
        analysis.setExperimentId(experimentId);
        analysis.setStatus(experiment.getStatus());
        analysis.setVariantStats(variantStatsMap);
        analysis.setAnalyzedAt(ZonedDateTime.now());

        if (variantStatsMap.size() >= 2) {
            determineWinner(analysis);
        }
        return analysis;
    }

    private VariantStats calculateVariantStats(String variantName, List<RagAbResult> variantResults,
                                                RagAbExperiment experiment) {
        List<Double> metricValues = new ArrayList<>();
        int converted = 0;

        for (RagAbResult r : variantResults) {
            if (r.getMetrics() != null && experiment.getTargetMetric() != null) {
                Double value = r.getMetrics().get(experiment.getTargetMetric());
                if (value != null) metricValues.add(value);
            }
            if (Boolean.TRUE.equals(r.getIsConverted())) converted++;
        }

        double mean = metricValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = calculateVariance(metricValues, mean);

        VariantStats stats = new VariantStats();
        stats.setVariantName(variantName);
        stats.setSampleSize(variantResults.size());
        stats.setMeanMetric(mean);
        stats.setVariance(variance);
        stats.setStdDev(Math.sqrt(variance));
        stats.setConversionRate(variantResults.isEmpty() ? 0 : (double) converted / variantResults.size());
        return stats;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExperimentResult> getExperimentResults(Long experimentId, int page, int size) {
        return resultRepository.findByExperimentIdOrderByCreatedAtDesc(experimentId, PageRequest.of(page, size))
                .getContent().stream()
                .map(this::toExperimentResult)
                .toList();
    }

    // ==================== Private ====================

    private void determineWinner(ExperimentAnalysis analysis) {
        Map<String, VariantStats> stats = analysis.getVariantStats();

        String bestVariant = null;
        double bestMean = -1;
        for (Map.Entry<String, VariantStats> entry : stats.entrySet()) {
            if (entry.getValue().getMeanMetric() > bestMean) {
                bestMean = entry.getValue().getMeanMetric();
                bestVariant = entry.getKey();
            }
        }

        List<VariantStats> statsList = new ArrayList<>(stats.values());
        double zScore = calculateZScore(statsList.get(0), statsList.get(1));
        double confidence = Math.min(0.99, Math.abs(zScore) / 2.0 + 0.5);

        analysis.setConfidenceLevel(confidence);
        analysis.setIsSignificant(confidence >= 0.95);
        analysis.setWinner(analysis.isIsSignificant() ? bestVariant : null);
        analysis.setRecommendation(analysis.isIsSignificant()
                ? "实验结果显著，建议将获胜变体 '" + bestVariant + "' 推广至全量流量"
                : "样本量不足或差异不显著，建议增加流量或延长测试周期");
    }

    private double calculateVariance(List<Double> values, double mean) {
        if (values.isEmpty()) return 0;
        return values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum() / values.size();
    }

    private double calculateZScore(VariantStats a, VariantStats b) {
        double meanDiff = b.getMeanMetric() - a.getMeanMetric();
        double pooledSE = Math.sqrt(
                (a.getVariance() / Math.max(1, a.getSampleSize())) +
                (b.getVariance() / Math.max(1, b.getSampleSize())));
        return pooledSE > 0 ? meanDiff / pooledSE : 0.0;
    }

    private Experiment toExperiment(RagAbExperiment entity) {
        Experiment dto = new Experiment();
        dto.setId(entity.getId());
        dto.setExperimentName(entity.getExperimentName());
        dto.setDescription(entity.getDescription());
        dto.setStatus(entity.getStatus());
        dto.setTrafficSplit(entity.getTrafficSplit());
        dto.setTargetMetric(entity.getTargetMetric());
        dto.setMinSampleSize(entity.getMinSampleSize());
        dto.setStartTime(entity.getStartTime());
        dto.setEndTime(entity.getEndTime());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    private ExperimentResult toExperimentResult(RagAbResult entity) {
        ExperimentResult dto = new ExperimentResult();
        dto.setId(entity.getId());
        dto.setVariantName(entity.getVariantName());
        dto.setSessionId(entity.getSessionId());
        dto.setQuery(entity.getQuery());
        dto.setMetrics(entity.getMetrics());
        dto.setIsConverted(entity.getIsConverted());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
