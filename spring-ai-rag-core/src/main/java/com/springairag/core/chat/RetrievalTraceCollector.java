package com.springairag.core.chat;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.retrieval.RetrievalBranchStage;
import com.springairag.core.retrieval.RetrievalOutcome;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe attempt-local retrieval trace and tool budget.
 */
public class RetrievalTraceCollector {

    private final AtomicReference<String> effectiveQuery = new AtomicReference<>();
    private final Map<String, RetrievalResult> sources = new LinkedHashMap<>();
    private final Map<String, Integer> sourcePositions = new LinkedHashMap<>();
    private final Set<String> exposedSourceKeys = new LinkedHashSet<>();
    private final Map<String, CachedResults> queryResults = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ChatEvent> toolEvents =
            new ConcurrentLinkedQueue<>();
    private final AtomicInteger retrievalCalls = new AtomicInteger();
    private final AtomicInteger toolRounds = new AtomicInteger();
    private final AtomicReference<RetrievalOutcome> latestOutcome =
            new AtomicReference<>();
    private final int maxRetrievalCalls;
    private final int maxToolRounds;
    private final int maxUniqueSources;
    private final RetrievalTraceSession parentSession;
    private final String attemptKey;
    private volatile boolean lastBudgetExhausted;

    public RetrievalTraceCollector() {
        this(3, 3, 20);
    }

    public RetrievalTraceCollector(int maxRetrievalCalls, int maxToolRounds, int maxUniqueSources) {
        this(maxRetrievalCalls, maxToolRounds, maxUniqueSources, null, null);
    }

    public RetrievalTraceCollector(
            int maxRetrievalCalls,
            int maxToolRounds,
            int maxUniqueSources,
            RetrievalTraceSession parentSession,
            String attemptKey) {
        this.maxRetrievalCalls = Math.max(1, maxRetrievalCalls);
        this.maxToolRounds = Math.max(1, maxToolRounds);
        this.maxUniqueSources = Math.max(1, maxUniqueSources);
        this.parentSession = parentSession;
        this.attemptKey = attemptKey;
    }

    public RetrievalTraceSession parentSession() {
        return parentSession;
    }

    public String attemptKey() {
        return attemptKey;
    }

    public void setEffectiveQuery(String query) {
        if (query != null && !query.isBlank()) {
            effectiveQuery.set(query.trim());
        }
    }

    public String effectiveQuery(String fallback) {
        String value = effectiveQuery.get();
        return value != null ? value : fallback;
    }

    public boolean tryBeginRetrieval(String query) {
        if (retrievalCalls.incrementAndGet() > maxRetrievalCalls) {
            retrievalCalls.decrementAndGet();
            lastBudgetExhausted = true;
            if (parentSession != null) {
                parentSession.recordBudgetExhausted(attemptKey, query);
            }
            return false;
        }
        lastBudgetExhausted = false;
        return true;
    }

    public boolean lastBudgetExhausted() {
        return lastBudgetExhausted;
    }

    public void recordOutcome(RetrievalOutcome outcome) {
        recordOutcome(outcome, outcome != null ? outcome.results().size() : 0);
    }

    public void recordOutcome(RetrievalOutcome outcome, int coverageLimit) {
        recordOutcome(
                outcome != null ? outcome.originalQuery() : null,
                outcome,
                coverageLimit);
    }

    public void recordOutcome(
            String query,
            RetrievalOutcome outcome,
            int coverageLimit) {
        if (outcome == null) {
            return;
        }
        latestOutcome.set(outcome);
        record(query, outcome.results(), coverageLimit);
        if (parentSession != null) {
            parentSession.recordRetrieval(attemptKey, outcome);
        }
    }

    /**
     * Records an intermediate retrieval outcome without consuming citation/source budget.
     * A subsequent rerank result replaces this outcome in the parent trace.
     */
    public void recordCandidateOutcome(RetrievalOutcome outcome) {
        if (outcome == null) {
            return;
        }
        latestOutcome.set(outcome);
        if (parentSession != null) {
            parentSession.recordRetrieval(attemptKey, outcome);
        }
    }

    public void recordRerank(
            RetrievalBranchStage stage,
            List<RetrievalResult> results,
            boolean degraded) {
        recordRerank(stage, results, degraded, null, 0);
    }

    public void recordRerank(
            RetrievalBranchStage stage,
            List<RetrievalResult> results,
            boolean degraded,
            String query,
            int coverageLimit) {
        RetrievalOutcome previous = latestOutcome.get();
        RetrievalOutcome replacement = (previous != null
                ? previous
                : RetrievalOutcome.ofResults(results))
                .withRerank(stage, results, degraded);
        latestOutcome.set(replacement);
        String effectiveQuery = query != null && !query.isBlank()
                ? query
                : replacement.originalQuery();
        record(effectiveQuery, replacement.results(), coverageLimit);
        if (parentSession != null) {
            parentSession.replaceRetrieval(
                    attemptKey, previous, replacement);
        }
    }

    public RetrievalOutcome latestOutcome() {
        return latestOutcome.get();
    }

    public boolean tryBeginToolRound() {
        return toolRounds.incrementAndGet() <= maxToolRounds;
    }

    public boolean isRepeatedQuery(String query) {
        return cachedResults(query) != null;
    }

    public void record(List<RetrievalResult> results) {
        record(null, results);
    }

    public synchronized void record(String query, List<RetrievalResult> results) {
        record(query, results, results != null ? results.size() : 0);
    }

    public synchronized void record(
            String query,
            List<RetrievalResult> results,
            int coverageLimit) {
        if (results == null) {
            return;
        }
        if (query != null && !query.isBlank()) {
            queryResults.put(
                    normalizeQuery(query),
                    new CachedResults(
                            List.copyOf(results),
                            Math.max(0, coverageLimit)));
        }
        for (RetrievalResult result : results) {
            String key = sourceKey(result);
            RetrievalResult existing = sources.get(key);
            if (existing != null) {
                if (result.getScore() > existing.getScore()) {
                    sources.put(key, result);
                }
                continue;
            }
            if (sources.size() >= maxUniqueSources) {
                continue;
            }
            sourcePositions.put(key, sourcePositions.size() + 1);
            sources.put(key, result);
        }
    }

    public List<RetrievalResult> cachedResults(String query) {
        CachedResults cached = cachedEntry(query);
        return cached != null ? cached.results() : null;
    }

    /**
     * Returns a final-result cache entry only when it covers the requested limit.
     * Smaller requests are truncated locally; larger requests must retrieve again.
     */
    public List<RetrievalResult> cachedResults(String query, int requestedLimit) {
        CachedResults cached = cachedEntry(query);
        if (cached == null || cached.coverageLimit() < requestedLimit) {
            return null;
        }
        int limit = Math.max(0, requestedLimit);
        return cached.results().size() <= limit
                ? cached.results()
                : List.copyOf(cached.results().subList(0, limit));
    }

    public int cachedCoverageLimit(String query) {
        CachedResults cached = cachedEntry(query);
        return cached != null ? cached.coverageLimit() : 0;
    }

    private CachedResults cachedEntry(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return queryResults.get(normalizeQuery(query));
    }

    public synchronized List<RetrievalResult> sources() {
        List<RetrievalResult> exposed = new ArrayList<>();
        for (String key : exposedSourceKeys) {
            RetrievalResult result = sources.get(key);
            if (result != null) {
                exposed.add(result);
            }
        }
        return exposed;
    }

    /**
     * Returns the stable citation assigned when the source first entered this attempt.
     *
     * <p>A null result means the unique-source budget excluded the source, so it must
     * not be exposed to the model as citable evidence.</p>
     */
    public synchronized String citationId(RetrievalResult result) {
        if (result == null) {
            return null;
        }
        Integer position = sourcePositions.get(sourceKey(result));
        return position != null ? "S" + position : null;
    }

    public synchronized void markExposed(List<RetrievalResult> results) {
        if (results == null) {
            return;
        }
        for (RetrievalResult result : results) {
            String key = sourceKey(result);
            if (sources.containsKey(key)) {
                exposedSourceKeys.add(key);
            }
        }
    }

    public int retrievalCalls() {
        return retrievalCalls.get();
    }

    public int toolRounds() {
        return toolRounds.get();
    }

    public void recordToolStarted(String toolCallId, String tool, String query) {
        toolEvents.add(new ChatEvent.ToolStarted(toolCallId, tool, query));
    }

    public void recordToolFinished(
            String toolCallId,
            String tool,
            int resultCount,
            long elapsedMs) {
        toolEvents.add(new ChatEvent.ToolFinished(
                toolCallId, tool, resultCount, elapsedMs));
        if (parentSession != null) {
            parentSession.recordToolCall(
                    attemptKey, tool, resultCount, elapsedMs, lastBudgetExhausted);
        }
    }

    public List<ChatEvent> drainToolEvents() {
        List<ChatEvent> drained = new ArrayList<>();
        ChatEvent event;
        while ((event = toolEvents.poll()) != null) {
            drained.add(event);
        }
        return List.copyOf(drained);
    }

    public String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        return Normalizer.normalize(query.trim().replaceAll("\\s+", " "), Normalizer.Form.NFKC);
    }

    public synchronized Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("effectiveQuery", effectiveQuery.get());
        result.put("retrievalCalls", retrievalCalls());
        result.put("toolRounds", toolRounds());
        result.put("sourceCount", sources.size());
        return result;
    }

    private String sourceKey(RetrievalResult result) {
        return result.getDocumentId() + ":" + result.getChunkIndex();
    }

    private record CachedResults(
            List<RetrievalResult> results,
            int coverageLimit) {
    }
}
