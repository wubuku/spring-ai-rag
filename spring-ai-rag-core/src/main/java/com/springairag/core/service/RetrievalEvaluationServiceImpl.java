package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.entity.RagRetrievalEvaluation;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.*;
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

    public RetrievalEvaluationServiceImpl(RagRetrievalEvaluationRepository evaluationRepository,
                                          ObjectMapper objectMapper) {
        this.evaluationRepository = evaluationRepository;
        this.objectMapper = objectMapper;
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

        RagRetrievalEvaluation saved = evaluationRepository.save(evaluation);
        log.info("[RetrievalEval] query=\"{}\", MRR={:.4f}, NDCG={:.4f}, hitRate={}",
                query, metrics.getMrr(), metrics.getNdcg(), metrics.getHitRate());
        return saved;
    }

    @Override
    public List<RagRetrievalEvaluation> batchEvaluate(List<EvaluationCase> cases) {
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

        // Precision@K 和 Recall@K
        Map<Integer, Double> precisionAtK = new LinkedHashMap<>();
        Map<Integer, Double> recallAtK = new LinkedHashMap<>();

        int hitCount = 0;
        int relevantCount = relevantSet.size();

        for (int i = 0; i < Math.min(k, retrieved.size()); i++) {
            if (relevantSet.contains(retrieved.get(i))) {
                hitCount++;
            }
            int position = i + 1;
            precisionAtK.put(position, (double) hitCount / position);
            recallAtK.put(position, (double) hitCount / relevantCount);
        }

        // 补齐未达到 K 的位置
        for (int i = retrieved.size(); i < k; i++) {
            int position = i + 1;
            precisionAtK.putIfAbsent(position, precisionAtK.getOrDefault(i, 0.0));
            recallAtK.putIfAbsent(position, recallAtK.getOrDefault(i, (double) hitCount / relevantCount));
        }

        // MRR: 第一个相关结果的排名倒数
        double mrr = 0.0;
        for (int i = 0; i < retrieved.size(); i++) {
            if (relevantSet.contains(retrieved.get(i))) {
                mrr = 1.0 / (i + 1);
                break;
            }
        }

        // NDCG
        double ndcg = calculateNDCG(retrieved, relevantSet, k);

        // Hit Rate
        double hitRate = hitCount > 0 ? 1.0 : 0.0;

        return new EvaluationMetrics(precisionAtK, recallAtK, mrr, ndcg, hitRate);
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

        double sumMrr = 0, sumNdcg = 0, sumHitRate = 0;
        Map<Integer, Double> sumPrecisionAtK = new HashMap<>();
        Map<Integer, Double> sumRecallAtK = new HashMap<>();
        int count = 0;

        for (RagRetrievalEvaluation e : evaluations) {
            if (e.getMrr() != null) sumMrr += e.getMrr();
            if (e.getNdcg() != null) sumNdcg += e.getNdcg();
            if (e.getHitRate() != null) sumHitRate += e.getHitRate();
            count++;

            if (e.getPrecisionAtK() != null) {
                for (Map.Entry<Integer, Double> entry : e.getPrecisionAtK().entrySet()) {
                    sumPrecisionAtK.merge(entry.getKey(), entry.getValue(), Double::sum);
                }
            }
            if (e.getRecallAtK() != null) {
                for (Map.Entry<Integer, Double> entry : e.getRecallAtK().entrySet()) {
                    sumRecallAtK.merge(entry.getKey(), entry.getValue(), Double::sum);
                }
            }
        }

        if (count > 0) {
            double c = count;
            metrics.setAvgMrr(sumMrr / c);
            metrics.setAvgNdcg(sumNdcg / c);
            metrics.setAvgHitRate(sumHitRate / c);

            Map<Integer, Double> avgPrecisionAtK = new HashMap<>();
            for (Map.Entry<Integer, Double> entry : sumPrecisionAtK.entrySet()) {
                avgPrecisionAtK.put(entry.getKey(), entry.getValue() / c);
            }
            metrics.setAvgPrecisionAtK(avgPrecisionAtK);

            Map<Integer, Double> avgRecallAtK = new HashMap<>();
            for (Map.Entry<Integer, Double> entry : sumRecallAtK.entrySet()) {
                avgRecallAtK.put(entry.getKey(), entry.getValue() / c);
            }
            metrics.setAvgRecallAtK(avgRecallAtK);
        }

        return metrics;
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
