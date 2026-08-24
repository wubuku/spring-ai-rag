package com.springairag.core.diagnostics;

import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScopeSummary;
import com.springairag.core.retrieval.RetrievalTraceHeaders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次 Search/Chat 请求拥有的父级检索诊断会话。
 *
 * <p>V35 每个父 trace 只写一行 {@code rag_retrieval_logs}；attempt 与 tool
 * 作为有界数组写入 metadata。
 */
public final class RetrievalTraceSession {

    private final UUID traceId;
    private final String ownerPrincipalId;
    private final String operation;
    private final String sessionId;
    private final Instant createdAt = Instant.now();
    private final AtomicReference<Map<String, Object>> scopeSummary =
            new AtomicReference<>(Map.of());
    private final AtomicReference<RetrievalFilters> filters =
            new AtomicReference<>(RetrievalFilters.none());
    private final List<AttemptRecord> attempts = new CopyOnWriteArrayList<>();
    private final List<RetrievalOutcome> retrievals = new CopyOnWriteArrayList<>();
    private final AtomicBoolean budgetExhausted = new AtomicBoolean();
    private final AtomicReference<Map<String, Object>> citationValidation =
            new AtomicReference<>();

    public RetrievalTraceSession(
            ChatPrincipal principal,
            String operation,
            String sessionId) {
        this.traceId = UUID.randomUUID();
        this.ownerPrincipalId = principal != null
                ? principal.id()
                : ChatPrincipal.local().id();
        this.operation = operation != null && !operation.isBlank()
                ? operation
                : RetrievalTraceHeaders.OPERATION_SEARCH;
        this.sessionId = sessionId;
    }

    public UUID traceId() {
        return traceId;
    }

    public String ownerPrincipalId() {
        return ownerPrincipalId;
    }

    public String operation() {
        return operation;
    }

    public String sessionId() {
        return sessionId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public void attachScope(Map<String, Object> summary, RetrievalFilters nextFilters) {
        if (summary != null) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : summary.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
            scopeSummary.set(Map.copyOf(copy));
        }
        if (nextFilters != null) {
            filters.set(nextFilters);
        }
    }

    public RetrievalFilters filters() {
        return filters.get();
    }

    public Map<String, Object> scopeSummary() {
        return scopeSummary.get();
    }

    public RetrievalTraceCollector newAttemptCollector(
            String attemptKey,
            int maxRetrievalCalls,
            int maxToolRounds,
            int maxUniqueSources) {
        AttemptRecord attempt = new AttemptRecord(attemptKey);
        attempts.add(attempt);
        return new RetrievalTraceCollector(
                maxRetrievalCalls,
                maxToolRounds,
                maxUniqueSources,
                this,
                attemptKey);
    }

    public void markAttemptFinished(String attemptKey, boolean succeeded, String modelRef) {
        AttemptRecord attempt = findAttempt(attemptKey);
        if (attempt != null) {
            attempt.finished = true;
            attempt.succeeded = succeeded;
            attempt.modelRef = modelRef;
        }
    }

    public void recordRetrieval(String attemptKey, RetrievalOutcome outcome) {
        if (outcome == null) {
            return;
        }
        retrievals.add(outcome);
        AttemptRecord attempt = findAttempt(attemptKey);
        if (attempt != null) {
            attempt.retrievals.add(outcome);
        }
    }

    public synchronized void replaceRetrieval(
            String attemptKey,
            RetrievalOutcome previous,
            RetrievalOutcome replacement) {
        if (replacement == null) {
            return;
        }
        if (!replaceLast(retrievals, previous, replacement)) {
            retrievals.add(replacement);
        }
        AttemptRecord attempt = findAttempt(attemptKey);
        if (attempt != null
                && !replaceLast(attempt.retrievals, previous, replacement)) {
            attempt.retrievals.add(replacement);
        }
    }

    public void recordToolCall(
            String attemptKey,
            String tool,
            int resultCount,
            long elapsedMs,
            boolean exhausted) {
        AttemptRecord attempt = findAttempt(attemptKey);
        if (attempt == null) {
            return;
        }
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("tool", tool);
        toolCall.put("resultCount", resultCount);
        toolCall.put("elapsedMs", elapsedMs);
        if (exhausted) {
            toolCall.put("budgetExhausted", true);
            budgetExhausted.set(true);
        }
        attempt.toolCalls.add(Map.copyOf(toolCall));
    }

    public void recordBudgetExhausted(String attemptKey, String query) {
        budgetExhausted.set(true);
        AttemptRecord attempt = findAttempt(attemptKey);
        if (attempt != null) {
            attempt.budgetExhausted = true;
            attempt.lastBudgetQueryChars = query == null ? 0 : query.length();
        }
    }

    public void recordQueryExpansion(
            String attemptKey,
            Map<String, Object> summary) {
        if (summary == null) {
            return;
        }
        AttemptRecord attempt = findAttempt(attemptKey);
        if (attempt != null) {
            attempt.queryExpansion = Map.copyOf(summary);
        }
    }

    public void recordDocumentJoin(
            String attemptKey,
            Map<String, Object> summary) {
        if (summary == null) {
            return;
        }
        AttemptRecord attempt = findAttempt(attemptKey);
        if (attempt != null) {
            attempt.documentJoin = Map.copyOf(summary);
        }
    }

    public void setCitationValidation(Map<String, Object> validation) {
        citationValidation.set(validation == null ? null : Map.copyOf(validation));
    }

    public Map<String, Object> citationValidation() {
        return citationValidation.get();
    }

    public boolean budgetExhausted() {
        return budgetExhausted.get();
    }

    public List<RetrievalOutcome> retrievals() {
        return List.copyOf(retrievals);
    }

    public RetrievalOutcome latestOutcome() {
        return retrievals.isEmpty() ? null : retrievals.get(retrievals.size() - 1);
    }

    public Map<String, Object> toMetadata(boolean storeQueryText) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", 1);
        metadata.put("scope", scopeSummary.get());
        metadata.put("filter", RetrievalScopeSummary.filterSummary(filters.get()));
        List<Map<String, Object>> attemptMaps = new ArrayList<>();
        for (AttemptRecord attempt : attempts) {
            attemptMaps.add(attempt.toMap(storeQueryText));
        }
        metadata.put("attempts", attemptMaps);
        metadata.put("queryStats", queryStats());
        metadata.put("budgetExhausted", budgetExhausted.get());
        if (citationValidation.get() != null) {
            metadata.put("citationValidation", citationValidation.get());
        }
        return metadata;
    }

    private Map<String, Object> queryStats() {
        int count = 0;
        int chars = 0;
        for (RetrievalOutcome outcome : retrievals) {
            if (!outcome.effectiveQueries().isEmpty()) {
                count += outcome.effectiveQueries().size();
                for (RetrievalOutcome.QueryStat stat : outcome.effectiveQueries()) {
                    chars += stat.charCount();
                }
            } else if (outcome.originalQuery() != null) {
                count++;
                chars += outcome.originalQuery().length();
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("count", count);
        stats.put("charCount", chars);
        return stats;
    }

    private boolean replaceLast(
            List<RetrievalOutcome> outcomes,
            RetrievalOutcome previous,
            RetrievalOutcome replacement) {
        if (previous == null) {
            return false;
        }
        for (int i = outcomes.size() - 1; i >= 0; i--) {
            if (outcomes.get(i) == previous) {
                outcomes.set(i, replacement);
                return true;
            }
        }
        return false;
    }

    private AttemptRecord findAttempt(String attemptKey) {
        if (attemptKey == null) {
            return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
        }
        for (AttemptRecord attempt : attempts) {
            if (attemptKey.equals(attempt.key)) {
                return attempt;
            }
        }
        return null;
    }

    private static final class AttemptRecord {
        private final String key;
        private volatile boolean finished;
        private volatile boolean succeeded;
        private volatile String modelRef;
        private volatile boolean budgetExhausted;
        private volatile int lastBudgetQueryChars;
        private volatile Map<String, Object> queryExpansion;
        private volatile Map<String, Object> documentJoin;
        private final List<RetrievalOutcome> retrievals = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> toolCalls = new CopyOnWriteArrayList<>();

        private AttemptRecord(String key) {
            this.key = key != null ? key : "attempt";
        }

        private Map<String, Object> toMap(boolean storeQueryText) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", key);
            map.put("status", succeeded ? "SUCCEEDED" : (finished ? "FAILED" : "RUNNING"));
            if (modelRef != null) {
                map.put("modelRef", modelRef);
            }
            List<Map<String, Object>> retrievalMaps = new ArrayList<>();
            for (RetrievalOutcome outcome : retrievals) {
                Map<String, Object> item = new LinkedHashMap<>(outcome.toMetadataMap());
                item.put("outcomeCode", outcome.outcomeCode());
                item.put("emptyReasonCode", outcome.emptyReasonCode());
                item.put("resultCount", outcome.results().size());
                item.put("elapsedMs", outcome.elapsedMs());
                if (storeQueryText && outcome.originalQuery() != null) {
                    item.put("query", outcome.originalQuery());
                }
                retrievalMaps.add(item);
            }
            map.put("retrievals", retrievalMaps);
            map.put("toolCalls", List.copyOf(toolCalls));
            if (queryExpansion != null) {
                map.put("queryExpansion", queryExpansion);
            }
            if (documentJoin != null) {
                map.put("documentJoin", documentJoin);
            }
            if (budgetExhausted) {
                map.put("budgetExhausted", true);
                map.put("lastBudgetQueryChars", lastBudgetQueryChars);
            }
            return map;
        }
    }

}
