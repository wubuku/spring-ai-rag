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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Retrieval evaluation service implementation.
 *
 * <p>Computes standard IR evaluation metrics and persists them to the rag_retrieval_evaluations table.
 */
@Service
@Transactional
public class RetrievalEvaluationServiceImpl implements RetrievalEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvaluationServiceImpl.class);
    private static final int DEFAULT_K = 10;

    private final RagRetrievalEvaluationRepository evaluationRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ChatClient.Builder chatClientBuilder;

    private Timer evaluateTimer;
    private Counter evaluationCounter;
    private Counter batchEvaluationCounter;
    private Counter evaluationHitCounter;
    private Counter evaluationMissCounter;

    public RetrievalEvaluationServiceImpl(RagRetrievalEvaluationRepository evaluationRepository,
                                          ObjectMapper objectMapper,
                                          MeterRegistry meterRegistry,
                                          @Autowired(required = false) ChatClient.Builder chatClientBuilder) {
        this.evaluationRepository = evaluationRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.chatClientBuilder = chatClientBuilder;
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

        // Cumulative hit count for Precision@K / Recall@K
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

        // Pad positions beyond retrieved.size() up to K
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
     * Computes NDCG@K.
     * DCG = Σ(relevance_i / log2(position_i + 1))
     * IDCG = DCG under ideal ranking
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

    @Override
    public AnswerQualityResult evaluateAnswerQuality(String query, String context, String answer) {
        if (chatClientBuilder == null) {
            throw new UnsupportedOperationException(
                    "ChatClient is not available. Please configure an LLM provider.");
        }

        String judgePrompt = buildJudgePrompt(query, context, answer);

        String responseText = chatClientBuilder.build()
                .prompt()
                .user(judgePrompt)
                .call()
                .content();

        return parseJudgeResponse(responseText);
    }

    private String buildJudgePrompt(String query, String context, String answer) {
        return """
            You are an expert RAG system evaluator. Evaluate the quality of the AI assistant's answer
            based on the retrieved context and the user's query.

            Provide your evaluation in the following structured format (JSON only, no additional text):
            {
              "groundedness": <score 1-5>,  // Is the answer supported by the context? (1=completely unsupported, 5=fully grounded)
              "relevance": <score 1-5>,      // Does the answer address the query? (1=totally irrelevant, 5=perfectly relevant)
              "helpfulness": <score 1-5>,    // Is the answer useful and clear? (1=useless, 5=extremely helpful)
              "reasoning": "<brief explanation (1-2 sentences)>",
              "recommendation": "<ACCEPT if all scores >= 3; REVISION if any score < 3; REJECT if any score <= 1>"
            }

            Query:
            %s

            Retrieved Context:
            %s

            AI Answer:
            %s
            """.formatted(query, context, answer);
    }

    private AnswerQualityResult parseJudgeResponse(String responseText) {
        try {
            var node = objectMapper.readTree(responseText.trim());
            int groundedness = Math.min(5, Math.max(1, node.path("groundedness").asInt(3)));
            int relevance = Math.min(5, Math.max(1, node.path("relevance").asInt(3)));
            int helpfulness = Math.min(5, Math.max(1, node.path("helpfulness").asInt(3)));
            String reasoning = node.path("reasoning").asText("No reasoning provided");
            String recommendation = node.path("recommendation").asText("REVISION");
            return new AnswerQualityResult(groundedness, relevance, helpfulness, reasoning, recommendation);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse LLM judge response as JSON, returning default result. Response: {}",
                    responseText, e);
            return new AnswerQualityResult(3, 3, 3,
                    "Evaluation failed to parse model response", "REVISION");
        }
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
