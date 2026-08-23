package com.springairag.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.RetrievalTraceDetailResponse;
import com.springairag.api.dto.RetrievalTracePageResponse;
import com.springairag.api.dto.RetrievalTraceSummaryResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagRetrievalDiagnosticsProperties;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagRetrievalLog;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagRetrievalLogRepository;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalOutcomeCodes;
import com.springairag.core.retrieval.RetrievalTraceHeaders;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.service.CollectionIdentityResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 检索诊断的创建、fail-open 持久化和按 principal 读取。
 */
@Service
public class RetrievalDiagnosticsService {

    private static final Logger log =
            LoggerFactory.getLogger(RetrievalDiagnosticsService.class);
    private static final int MAX_PAGE_SIZE = 100;
    private static final int CLEANUP_BATCH = 500;

    private final RagRetrievalLogRepository repository;
    private final RagRetrievalDiagnosticsProperties properties;
    private final ObjectMapper objectMapper;
    private final CollectionIdentityResolver identityResolver;

    public RetrievalDiagnosticsService(
            @Autowired(required = false) RagRetrievalLogRepository repository,
            RagProperties ragProperties,
            ObjectMapper objectMapper,
            @Autowired(required = false) CollectionIdentityResolver identityResolver) {
        this.repository = repository;
        this.properties = ragProperties.getRetrievalDiagnostics();
        this.objectMapper = objectMapper;
        this.identityResolver = identityResolver;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public RetrievalTraceSession createSession(
            ChatPrincipal principal,
            String operation,
            String sessionId) {
        return new RetrievalTraceSession(principal, operation, sessionId);
    }

    public void persistSearch(
            RetrievalTraceSession session,
            RetrievalOutcome outcome,
            Map<String, Object> scopeSummary,
            RetrievalFilters filters) {
        if (session == null || outcome == null) {
            return;
        }
        session.attachScope(scopeSummary, filters);
        session.recordRetrieval(null, outcome);
        persist(session);
    }

    public void persist(RetrievalTraceSession session) {
        if (!properties.isEnabled() || !properties.isPersist()
                || session == null || repository == null) {
            return;
        }
        try {
            RagRetrievalLog entry = new RagRetrievalLog();
            entry.setTraceId(session.traceId());
            entry.setOwnerPrincipalId(session.ownerPrincipalId());
            entry.setOperation(session.operation());
            entry.setSessionId(session.sessionId());
            RetrievalOutcome latest = session.latestOutcome();
            String query = properties.isStoreQueryText() && latest != null
                    ? nullToEmpty(latest.originalQuery())
                    : RetrievalTraceHeaders.REDACTED_QUERY;
            entry.setQuery(query == null || query.isBlank()
                    ? RetrievalTraceHeaders.REDACTED_QUERY
                    : query);
            entry.setRetrievalStrategy(strategyOf(latest));
            if (latest != null) {
                entry.setVectorSearchTimeMs(elapsed(latest.vectorStage()));
                entry.setFulltextSearchTimeMs(elapsed(latest.fulltextStage()));
                entry.setRerankTimeMs(elapsed(latest.rerankStage()));
                entry.setTotalTimeMs(latest.elapsedMs());
                entry.setResultCount(latest.results().size());
                entry.setResultScores(latest.positionalScores(20));
                entry.setOutcomeCode(resolveOutcome(session, latest));
                entry.setEmptyReasonCode(resolveEmptyReason(session, latest));
            } else if (session.budgetExhausted()) {
                entry.setResultCount(0);
                entry.setTotalTimeMs(0L);
                entry.setOutcomeCode(RetrievalOutcomeCodes.RETRIEVAL_BUDGET_EXHAUSTED);
                entry.setEmptyReasonCode(RetrievalOutcomeCodes.RETRIEVAL_BUDGET_EXHAUSTED);
                entry.setResultScores(Map.of());
            } else {
                entry.setResultCount(0);
                entry.setTotalTimeMs(0L);
                entry.setOutcomeCode(RetrievalOutcomeCodes.DIAGNOSTIC_UNKNOWN);
                entry.setEmptyReasonCode(RetrievalOutcomeCodes.DIAGNOSTIC_UNKNOWN);
                entry.setResultScores(Map.of());
            }
            entry.setMetadata(boundedMetadata(session.toMetadata(properties.isStoreQueryText())));
            repository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to persist retrieval trace {}: {}",
                    session.traceId(), e.getMessage());
        }
    }

    public RetrievalTracePageResponse list(
            ChatPrincipal principal,
            String operation,
            String outcomeCode,
            String emptyReasonCode,
            String sessionId,
            String citationStatus,
            int page,
            int size) {
        String owner = requirePrincipal(principal);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        int safePage = Math.max(0, page);
        if (repository == null) {
            return new RetrievalTracePageResponse(List.of(), safePage, safeSize, 0, 0);
        }
        Page<RagRetrievalLog> result = repository.searchTraces(
                owner,
                blankToNull(operation),
                blankToNull(outcomeCode),
                blankToNull(emptyReasonCode),
                blankToNull(sessionId),
                blankToNull(citationStatus),
                PageRequest.of(safePage, safeSize));
        List<RetrievalTraceSummaryResponse> items = result.getContent().stream()
                .map(this::toSummary)
                .toList();
        return new RetrievalTracePageResponse(
                items,
                safePage,
                safeSize,
                result.getTotalElements(),
                result.getTotalPages());
    }

    public RetrievalTraceDetailResponse get(ChatPrincipal principal, UUID traceId) {
        String owner = requirePrincipal(principal);
        if (repository == null) {
            throw new RagException(ErrorCode.NOT_FOUND, "Retrieval trace not found");
        }
        RagRetrievalLog logEntry = repository.findByTraceId(traceId)
                .filter(item -> owner.equals(item.getOwnerPrincipalId()))
                .orElseThrow(() -> new RagException(
                        ErrorCode.NOT_FOUND, "Retrieval trace not found"));
        return toDetail(logEntry, ApiKeyCollectionAccess.currentPolicy());
    }

    @Transactional
    public int cleanupExpired() {
        if (repository == null || properties.getRetentionDays() <= 0) {
            return 0;
        }
        ZonedDateTime cutoff = ZonedDateTime.now()
                .minusDays(properties.getRetentionDays());
        int deleted = 0;
        int batch;
        do {
            batch = repository.deleteExpiredTraces(cutoff, CLEANUP_BATCH);
            deleted += batch;
        } while (batch >= CLEANUP_BATCH);
        return deleted;
    }

    private RetrievalTraceSummaryResponse toSummary(RagRetrievalLog logEntry) {
        String citationStatus = null;
        Object citation = nested(logEntry.getMetadata(), "citationValidation");
        if (citation instanceof Map<?, ?> map && map.get("status") != null) {
            citationStatus = String.valueOf(map.get("status"));
        }
        return new RetrievalTraceSummaryResponse(
                logEntry.getTraceId(),
                logEntry.getOperation(),
                logEntry.getOutcomeCode(),
                logEntry.getEmptyReasonCode(),
                logEntry.getSessionId(),
                toOffset(logEntry.getCreatedAt()),
                logEntry.getResultCount(),
                logEntry.getTotalTimeMs(),
                citationStatus);
    }

    private RetrievalTraceDetailResponse toDetail(RagRetrievalLog logEntry, ApiAccessPolicy caller) {
        Map<String, Object> metadata = visibleMetadata(logEntry.getMetadata(), caller);
        Map<String, Object> scores = positionalOnly(logEntry.getResultScores());
        return new RetrievalTraceDetailResponse(
                logEntry.getTraceId(),
                logEntry.getOperation(),
                logEntry.getOutcomeCode(),
                logEntry.getEmptyReasonCode(),
                logEntry.getSessionId(),
                toOffset(logEntry.getCreatedAt()),
                logEntry.getResultCount(),
                logEntry.getTotalTimeMs(),
                scores,
                metadata);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> visibleMetadata(
            Map<String, Object> metadata,
            ApiAccessPolicy caller) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        Object scope = copy.get("scope");
        if (scope instanceof Map<?, ?> scopeMap) {
            Map<String, Object> visibleScope = new LinkedHashMap<>((Map<String, Object>) scopeMap);
            Object keys = visibleScope.get("collectionKeys");
            if (keys instanceof List<?> keyList) {
                visibleScope.put("collectionKeys", visibleCollectionKeys(keyList, caller));
            }
            copy.put("scope", visibleScope);
        }
        return copy;
    }

    private List<String> visibleCollectionKeys(List<?> keys, ApiAccessPolicy caller) {
        List<String> requested = new ArrayList<>();
        for (Object key : keys) {
            if (key != null && !String.valueOf(key).isBlank()) {
                requested.add(String.valueOf(key));
            }
        }
        if (identityResolver == null) {
            return List.of();
        }
        if (ApiKeyCollectionAccess.isUnrestricted(caller)) {
            return List.copyOf(requested);
        }
        Set<Long> allowed = ApiKeyCollectionAccess.restrictedCollectionIds(caller)
                .orElse(Set.of());
        List<String> visible = new ArrayList<>();
        for (String key : requested) {
            try {
                Optional<RagCollection> collection = identityResolver.findActive(null, key);
                if (collection.isPresent() && allowed.contains(collection.get().getId())) {
                    visible.add(key);
                }
            } catch (RuntimeException ignored) {
                // 权限收窄或未知 key 不得通过错误差异探测。
            }
        }
        return List.copyOf(visible);
    }

    private Map<String, Object> positionalOnly(Map<String, Object> scores) {
        if (scores == null || scores.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> visible = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : scores.entrySet()) {
            if (entry.getKey() != null && entry.getKey().startsWith("rank_")) {
                visible.put(entry.getKey(), entry.getValue());
            }
        }
        return visible;
    }

    private Map<String, Object> boundedMetadata(Map<String, Object> metadata) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(metadata);
            if (bytes.length <= properties.getMaxDetailBytes()) {
                return metadata;
            }
            Map<String, Object> trimmed = new LinkedHashMap<>(metadata);
            trimmed.put("attempts", List.of());
            trimmed.put("truncated", true);
            return trimmed;
        } catch (Exception e) {
            return Map.of("schemaVersion", 1, "truncated", true);
        }
    }

    private static String resolveOutcome(
            RetrievalTraceSession session,
            RetrievalOutcome latest) {
        if (session.budgetExhausted()
                && (latest == null || latest.results().isEmpty())) {
            return RetrievalOutcomeCodes.RETRIEVAL_BUDGET_EXHAUSTED;
        }
        return latest.outcomeCode();
    }

    private static String resolveEmptyReason(
            RetrievalTraceSession session,
            RetrievalOutcome latest) {
        if (session.budgetExhausted()
                && (latest == null || latest.results().isEmpty())) {
            return RetrievalOutcomeCodes.RETRIEVAL_BUDGET_EXHAUSTED;
        }
        return latest.emptyReasonCode();
    }

    private static String strategyOf(RetrievalOutcome outcome) {
        if (outcome == null) {
            return "unknown";
        }
        boolean vector = outcome.vectorStage() != null && outcome.vectorStage().attempted();
        boolean fulltext = outcome.fulltextStage() != null && outcome.fulltextStage().attempted();
        if (vector && fulltext) {
            return "hybrid";
        }
        if (fulltext) {
            return "fulltext";
        }
        return "vector";
    }

    private static Long elapsed(com.springairag.core.retrieval.RetrievalBranchStage stage) {
        return stage == null ? 0L : stage.elapsedMs();
    }

    private static String requirePrincipal(ChatPrincipal principal) {
        ChatPrincipal effective = principal != null ? principal : ChatPrincipal.local();
        return effective.id();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static OffsetDateTime toOffset(ZonedDateTime value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static Object nested(Map<String, Object> metadata, String key) {
        return metadata == null ? null : metadata.get(key);
    }
}
