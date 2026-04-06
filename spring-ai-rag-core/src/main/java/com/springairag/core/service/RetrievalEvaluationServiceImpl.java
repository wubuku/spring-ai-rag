package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.entity.RagRetrievalEvaluation;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 检索效果评估服务实现
 *
 * <p>计算标准 IR 评估指标并持久化到 rag_retrieval_evaluations 表。
 */
@Service
@Transactional
public class RetrievalEvaluationServiceImpl implements RetrievalEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvaluationServiceImpl.class);
    private static final int DEFAULT_K = 10;

    private final RagRetrievalEvaluationRepository evaluationRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private Timer evaluateTimer;
    private Counter evaluationCounter;
    private Counter batchEvaluationCounter;
    private Counter evaluationHitCounter;
    private Counter evaluationMissCounter;

    public RetrievalEvaluationServiceImpl(RagRetrievalEvaluationRepository evaluationRepository,
                                          ObjectMapper objectMapper,
                                          MeterRegistry meterRegistry) {
        this.evaluationRepository = evaluationRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initMetrics() {
        evaluateTimer = Timer.builder("rag.evaluation.duration")
                .description("Single retrieval evaluation latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        evaluationCounter = Counter.builder("rag.evaluation.count")
                .description("Total number of retrieval evaluations performed")
                .register(meterRegistry);

        batchEvaluationCounter = Counter.builder("rag.evaluation.batch_count")
                .description("Total number of batch evaluation cases processed")
                .register(meterRegistry);

        evaluationHitCounter = Counter.builder("rag.evaluation.hits")
                .description("Evaluations where at least one relevant document was retrieved")
                .register(meterRegistry);

        evaluationMissCounter = Counter.builder("rag.evaluation.misses")
                .description("Evaluations where no relevant documents were retrieved")
                .register(meterRegistry);
    }

    @Override
    public RagRetrievalEvaluation evaluate(String query, List<Long> retrievedDocIds, List<Long> relevantDocIds) {
        return evaluate(query, retrievedDocIds, relevantDocIds, "AUTO", null);
    }

    @Override
    public RagRetrievalEvaluation evaluate(String query, List<Long> retrievedDocIds, List<Long> relevantDocIds,
                                           String evaluationMethod, String evaluatorId) {
        EvaluationMetrics metrics = calculateMetrics(retrievedDocIds, relevantDocIds, DEFAULT_K);

        RagRetrievalEvaluation evaluation = new RagRetrievalEvaluation();
        evaluation.setQuery(query);
        evaluation.setRetrievedDocumentIds(toJson(retrievedDocIds));
        evaluation.setExpectedDocumentIds(toJson(relevantDocIds));
        evaluation.setPrecisionAtK(metrics.getPrecisionAtK());
        evaluation.setRecallAtK(metrics.getRecallAtK());
        evaluation.setMrr(metrics.getMrr());
        evaluation.setNdcg(metrics.getNdcg());
        evaluation.setHitRate(metrics.getHitRate());
        evaluation.setEvaluationMethod(evaluationMethod);
        evaluation.setEvaluatorId(evaluatorId);

        Map<String, Object> evalResult = new HashMap<>();
        evalResult.put("precisionAt10", metrics.getPrecisionAtK().getOrDefault(DEFAULT_K, 0.0));
        evalResult.put("recallAt10", metrics.getRecallAtK().getOrDefault(DEFAULT_K, 0.0));
        evaluation.setEvaluationResult(evalResult);

        long start = System.currentTimeMillis();
        RagRetrievalEvaluation saved = evaluationRepository.save(evaluation);
        evaluateTimer.record(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
        evaluationCounter.increment();
        if (metrics.getHitRate() > 0) {
            evaluationHitCounter.increment();
        } else {
            evaluationMissCounter.increment();
        }
        log.info("[RetrievalEval] query=\"{}\", MRR={}, NDCG={}, hitRate={}",
                query, metrics.getMrr(), metrics.getNdcg(), metrics.getHitRate());
        return saved;
    }

    @Override
    public List<RagRetrievalEvaluation> batchEvaluate(List<EvaluationCase> cases) {
        if (cases != null) {
            batchEvaluationCounter.increment(cases.size());
        }
        return cases.stream()
                .map(c -> evaluate(c.getQuery(), c.getRetrievedDocIds(), c.getRelevantDocIds(),
                        c.getEvaluationMethod(), c.getEvaluatorId()))
                .collect(Collectors.toList());
    }

    @Override
    public EvaluationMetrics calculateMetrics(List<Long> retrieved, List<Long> relevant, int k) {
        if (retrieved == null || relevant == null || retrieved.isEmpty() || relevant.isEmpty()) {
            return new EvaluationMetrics(Map.of(), Map.of(), 0.0, 0.0, 0.0);
        }

        Set<Long> relevantSet = new HashSet<>(relevant);
        int relevantCount = relevantSet.size();

        // 累计命中计数，计算 Precision@K / Recall@K
        Map<Integer, Double> precisionAtK = new LinkedHashMap<>();
        Map<Integer, Double> recallAtK = new LinkedHashMap<>();
        int hitCount = 0;

        for (int i = 0; i < Math.min(k, retrieved.size()); i++) {
            if (relevantSet.contains(retrieved.get(i))) {
                hitCount++;
            }
            int pos = i + 1;
            precisionAtK.put(pos, (double) hitCount / pos);
            recallAtK.put(pos, (double) hitCount / relevantCount);
        }

        // 补齐未达到 K 的位置
        for (int i = retrieved.size(); i < k; i++) {
            int pos = i + 1;
            precisionAtK.putIfAbsent(pos, precisionAtK.getOrDefault(i, 0.0));
            recallAtK.putIfAbsent(pos, recallAtK.getOrDefault(i, (double) hitCount / relevantCount));
        }

        double mrr = calculateMRR(retrieved, relevantSet);
        double ndcg = calculateNDCG(retrieved, relevantSet, k);
        double hitRate = hitCount > 0 ? 1.0 : 0.0;

        return new EvaluationMetrics(precisionAtK, recallAtK, mrr, ndcg, hitRate);
    }

    private double calculateMRR(List<Long> retrieved, Set<Long> relevantSet) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (relevantSet.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * 计算 NDCG@K
     * DCG = Σ(relevance_i / log2(position_i + 1))
     * IDCG = 理想排序下的 DCG
     * NDCG = DCG / IDCG
     */
    private double calculateNDCG(List<Long> retrieved, Set<Long> relevantSet, int k) {
        double dcg = 0.0;
        for (int i = 0; i < Math.min(k, retrieved.size()); i++) {
            double relevance = relevantSet.contains(retrieved.get(i)) ? 1.0 : 0.0;
            dcg += relevance / (Math.log(i + 2) / Math.log(2)); // log2(position+1), position = i+1
        }

        double idcg = 0.0;
        int idealCount = Math.min(relevantSet.size(), k);
        for (int i = 0; i < idealCount; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }

        return idcg > 0 ? dcg / idcg : 0.0;
    }

    @Override
    @Transactional(readOnly = true)
    public EvaluationReport getReport(ZonedDateTime startDate, ZonedDateTime endDate) {
        List<RagRetrievalEvaluation> evaluations =
                evaluationRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startDate, endDate);

        EvaluationReport report = new EvaluationReport();
        report.setTotalEvaluations(evaluations.size());

        if (evaluations.isEmpty()) {
            return report;
        }

        double sumMrr = 0, sumNdcg = 0, sumHitRate = 0;
        double sumPrecisionAt10 = 0, sumRecallAt10 = 0;
        int countWithMetrics = 0;

        for (RagRetrievalEvaluation e : evaluations) {
            if (e.getMrr() != null) sumMrr += e.getMrr();
            if (e.getNdcg() != null) sumNdcg += e.getNdcg();
            if (e.getHitRate() != null) sumHitRate += e.getHitRate();
            if (e.getPrecisionAtK() != null && e.getPrecisionAtK().containsKey(DEFAULT_K)) {
                sumPrecisionAt10 += e.getPrecisionAtK().get(DEFAULT_K);
                countWithMetrics++;
            }
            if (e.getRecallAtK() != null && e.getRecallAtK().containsKey(DEFAULT_K)) {
                sumRecallAt10 += e.getRecallAtK().get(DEFAULT_K);
            }
        }

        int n = evaluations.size();
        report.setAvgMrr(sumMrr / n);
        report.setAvgNdcg(sumNdcg / n);
        report.setAvgHitRate(sumHitRate / n);
        report.setAvgPrecision(countWithMetrics > 0 ? sumPrecisionAt10 / countWithMetrics : 0);
        report.setAvgRecall(countWithMetrics > 0 ? sumRecallAt10 / countWithMetrics : 0);

        return report;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RagRetrievalEvaluation> getHistory(int page, int size) {
        return evaluationRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public AggregatedMetrics getAggregatedMetrics(ZonedDateTime startDate, ZonedDateTime endDate) {
        List<RagRetrievalEvaluation> evaluations =
                evaluationRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startDate, endDate);

        AggregatedMetrics metrics = new AggregatedMetrics();
        metrics.setTotalEvaluations((long) evaluations.size());

        if (evaluations.isEmpty()) {
            return metrics;
        }

        int count = evaluations.size();
        Accumulator acc = accumulateMetrics(evaluations);
        applyAverages(metrics, acc, count);
        return metrics;
    }

    private record Accumulator(
            double sumMrr, double sumNdcg, double sumHitRate,
            Map<Integer, Double> sumPrecisionAtK, Map<Integer, Double> sumRecallAtK
    ) {}

    private Accumulator accumulateMetrics(List<RagRetrievalEvaluation> evaluations) {
        double sumMrr = 0, sumNdcg = 0, sumHitRate = 0;
        Map<Integer, Double> sumPrecisionAtK = new HashMap<>();
        Map<Integer, Double> sumRecallAtK = new HashMap<>();

        for (RagRetrievalEvaluation e : evaluations) {
            if (e.getMrr() != null) sumMrr += e.getMrr();
            if (e.getNdcg() != null) sumNdcg += e.getNdcg();
            if (e.getHitRate() != null) sumHitRate += e.getHitRate();

            if (e.getPrecisionAtK() != null) {
                for (var entry : e.getPrecisionAtK().entrySet()) {
                    sumPrecisionAtK.merge(entry.getKey(), entry.getValue(), Double::sum);
                }
            }
            if (e.getRecallAtK() != null) {
                for (var entry : e.getRecallAtK().entrySet()) {
                    sumRecallAtK.merge(entry.getKey(), entry.getValue(), Double::sum);
                }
            }
        }
        return new Accumulator(sumMrr, sumNdcg, sumHitRate, sumPrecisionAtK, sumRecallAtK);
    }

    private void applyAverages(AggregatedMetrics metrics, Accumulator acc, int count) {
        double c = count;
        metrics.setAvgMrr(acc.sumMrr() / c);
        metrics.setAvgNdcg(acc.sumNdcg() / c);
        metrics.setAvgHitRate(acc.sumHitRate() / c);

        Map<Integer, Double> avgPrecision = new HashMap<>();
        acc.sumPrecisionAtK().forEach((k, v) -> avgPrecision.put(k, v / c));
        metrics.setAvgPrecisionAtK(avgPrecision);

        Map<Integer, Double> avgRecall = new HashMap<>();
        acc.sumRecallAtK().forEach((k, v) -> avgRecall.put(k, v / c));
        metrics.setAvgRecallAtK(avgRecall);
    }

    private String toJson(List<Long> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize list to JSON", e);
            return "[]";
        }
    }

    @SuppressWarnings("unused")
    private List<Long> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize JSON to list", e);
            return Collections.emptyList();
        }
    }
}
