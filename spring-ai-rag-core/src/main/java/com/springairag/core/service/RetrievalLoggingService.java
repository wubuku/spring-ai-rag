package com.springairag.core.service;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.entity.RagRetrievalLog;
import com.springairag.core.repository.RagRetrievalLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retrieval Logging Service
 *
 * <p>Records performance data for each retrieval, including vector search latency, full-text search latency,
 * rerank latency, total latency, result count, and scores.
 * Registered conditionally via {@link ConditionalOnBean} — only created when RagRetrievalLogRepository is available.
 */
@Service
@ConditionalOnBean(RagRetrievalLogRepository.class)
public class RetrievalLoggingService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalLoggingService.class);

    private final RagRetrievalLogRepository repository;

    @Autowired
    public RetrievalLoggingService(RagRetrievalLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Record retrieval operation
     *
     * @param sessionId           session ID (can be null)
     * @param query               query text
     * @param strategy            retrieval strategy (hybrid/vector/fulltext)
     * @param vectorSearchTimeMs  vector search time (ms)
     * @param fulltextSearchTimeMs full-text search time (ms)
     * @param rerankTimeMs        reranking time (ms)
     * @param results             retrieval results list
     */
    public void logRetrieval(String sessionId, String query, String strategy,
                             long vectorSearchTimeMs, long fulltextSearchTimeMs,
                             long rerankTimeMs, List<RetrievalResult> results) {
        try {
            RagRetrievalLog entry = new RagRetrievalLog();
            entry.setSessionId(sessionId);
            entry.setQuery(query);
            entry.setRetrievalStrategy(strategy);
            entry.setVectorSearchTimeMs(vectorSearchTimeMs);
            entry.setFulltextSearchTimeMs(fulltextSearchTimeMs);
            entry.setRerankTimeMs(rerankTimeMs);
            entry.setTotalTimeMs(vectorSearchTimeMs + fulltextSearchTimeMs + rerankTimeMs);
            entry.setResultCount(results != null ? results.size() : 0);
            entry.setResultScores(extractResultScores(results));

            repository.save(entry);
            log.debug("[RetrievalLogging] Retrieval log recorded: query=\"{}\", strategy={}, total={}ms, results={}",
                    query, strategy, entry.getTotalTimeMs(), entry.getResultCount());
        } catch (Exception e) { // Resilience: retrieval logging is non-critical
            log.warn("[RetrievalLogging] Failed to record retrieval log: {}", e.getMessage());
        }
    }

    private Map<String, Object> extractResultScores(List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) return null;
        Map<String, Object> scores = new LinkedHashMap<>();
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult r = results.get(i);
            String key = r.getDocumentId() != null ? r.getDocumentId() : "idx_" + i;
            scores.put(key, r.getScore());
        }
        return scores;
    }

    /**
     * Query average total time for specified period
     */
    public Double getAvgTotalTime(ZonedDateTime start, ZonedDateTime end) {
        return repository.findAvgTotalTime(start, end);
    }

    /**
     * Query total log count for specified period
     */
    public long count(ZonedDateTime start, ZonedDateTime end) {
        return repository.countByCreatedAtBetween(start, end);
    }

    /**
     * Clean up logs before specified time
     *
     * @param cutoff cutoff time
     * @return number of records deleted
     */
    public long cleanup(ZonedDateTime cutoff) {
        return repository.deleteByCreatedAtBefore(cutoff);
    }
}
