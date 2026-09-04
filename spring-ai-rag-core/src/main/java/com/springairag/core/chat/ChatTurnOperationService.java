package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ChatTurnStatusResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.ChatTurnInProgressException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ChatTurnOperationRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates durable Chat operation lookup, claim, replay and terminal writes.
 *
 * <p>The service intentionally keeps HTTP transport projection outside this
 * class. The persisted payload is a native {@link ChatResponse} business
 * snapshot and can therefore be reused by native and compatibility adapters.</p>
 */
@Service
public class ChatTurnOperationService {

    private final ChatTurnOperationRepository repository;
    private final ObjectMapper objectMapper;
    private final RagChatProperties properties;
    private final RagProperties ragProperties;
    private final ChatAuthorizationService authorizationService;
    private final ChatObservabilityService observability;
    private final ChatExecutionService executionService;
    private final ScheduledExecutorService renewalExecutor =
            Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "rag-chat-operation-renew");
                thread.setDaemon(true);
                return thread;
            });
    private ChatSessionCoordinator sessionCoordinator;

    public ChatTurnOperationService(
            ChatTurnOperationRepository repository,
            ObjectMapper objectMapper,
            RagChatProperties properties,
            RagProperties ragProperties,
            ChatAuthorizationService authorizationService,
            ChatObservabilityService observability,
            ChatExecutionService executionService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.ragProperties = ragProperties;
        this.authorizationService = authorizationService;
        this.observability = observability;
        this.executionService = executionService;
    }

    @Autowired(required = false)
    void setSessionCoordinator(ChatSessionCoordinator sessionCoordinator) {
        this.sessionCoordinator = sessionCoordinator;
    }

    public Prepared prepare(
            ChatPrincipal principal,
            List<String> headerValues,
            ChatRequestFingerprint.Result fingerprint) {
        String rawKey = IdempotencyKeyValidator.normalize(headerValues);
        if (rawKey == null) {
            return Prepared.disabled(principal);
        }
        if (!properties.getIdempotency().isEnabled()) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_DISABLED,
                    "Chat idempotency is disabled by configuration");
        }
        if (fingerprint == null) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                    "Idempotency request fingerprint is missing");
        }
        String keyHash = IdempotencyKeyValidator.hash(rawKey);
        ChatTurnOperation existing = repository.find(principal.id(), keyHash);
        if (existing != null
                && !existing.requestFingerprintSha256()
                        .equals(fingerprint.sha256())) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used for another request");
        }
        return new Prepared(
                principal,
                keyHash,
                fingerprint.sha256(),
                fingerprint.canonical(),
                existing,
                true);
    }

    public Claim claim(
            Prepared prepared,
            String sessionId,
            ChatTurnOperation.Transport transport) {
        if (!prepared.keyed()) {
            return Claim.unkeyed();
        }
        ChatTurnOperation current = prepared.operation();
        if (current != null) {
            if (current.status() == ChatTurnOperation.Status.SUCCEEDED) {
                observability.replayed();
                return new Claim(current, true);
            }
            if (current.status() == ChatTurnOperation.Status.FAILED) {
                throw failedReplay(current);
            }
            if (current.leaseExpiresAt() != null
                    && current.leaseExpiresAt().isAfter(Instant.now())) {
                observability.inProgress();
                throw inProgress(current);
            }
            if (current.attemptCount()
                    >= properties.getIdempotency().getMaxAttempts()) {
                exhaustAttempts(current, null);
                throw failedReplay(repository.find(
                        prepared.principal().id(), prepared.keyHash()));
            }
            UUID token = UUID.randomUUID();
            ChatTurnOperation reclaimed = repository.reclaim(
                    current,
                    token,
                    leaseMs(transport),
                    properties.getIdempotency().getMaxAttempts());
            if (reclaimed != null) {
                Claim claim = new Claim(reclaimed, false);
                startRenewal(claim);
                return claim;
            }
            return claim(
                    prepared.withOperation(repository.find(
                            prepared.principal().id(),
                            prepared.keyHash())),
                    sessionId,
                    transport);
        }

        String effectiveSession = SessionIdValidator.isValid(sessionId)
                ? sessionId
                : UUID.randomUUID().toString();
        UUID turnId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        boolean inserted = repository.insert(
                prepared.principal().id(),
                prepared.keyHash(),
                prepared.fingerprintHash(),
                effectiveSession,
                turnId,
                transport,
                token,
                leaseMs(transport),
                "{\"authorizationSnapshotVersion\":1,\"scopeMode\":\"NOT_APPLICABLE\","
                        + "\"callerAccessMode\":\"NOT_APPLICABLE\","
                        + "\"effectiveSelectedCollectionIds\":[],"
                        + "\"callerAllowList\":[],"
                        + "\"unassignedDocumentsAllowed\":false,"
                        + "\"sourceDocumentCollectionSnapshot\":[],"
                        + "\"sourceCollectionIdsObserved\":[]}");
        if (inserted) {
            Claim claim = new Claim(
                    repository.find(
                            prepared.principal().id(),
                            prepared.keyHash()),
                    false);
            startRenewal(claim);
            observability.claimed();
            return claim;
        }
        return claim(
                prepared.withOperation(repository.find(
                        prepared.principal().id(),
                        prepared.keyHash())),
                effectiveSession,
                transport);
    }

    /**
     * Performs the read-only portion of a claim. It is used by HTTP adapters
     * before they resolve ACL-dependent scope or construct a provider command.
     */
    public Claim inspectExisting(Prepared prepared) {
        if (prepared == null || !prepared.keyed()
                || prepared.operation() == null) {
            return null;
        }
        ChatTurnOperation current = prepared.operation();
        if (current.status() == ChatTurnOperation.Status.SUCCEEDED) {
            observability.replayed();
            return new Claim(current, true, null);
        }
        if (current.status() == ChatTurnOperation.Status.FAILED) {
            throw failedReplay(current);
        }
        if (current.leaseExpiresAt() != null
                && current.leaseExpiresAt().isAfter(Instant.now())) {
            observability.inProgress();
            throw inProgress(current);
        }
        return null;
    }

    /**
     * Claims an operation together with the session lease used by the durable
     * execution. The lease is acquired before the unique operation insert; a
     * losing contender releases it before re-reading the operation.
     */
    public Claim claim(
            Prepared prepared,
            ChatCommand command,
            ChatTurnOperation.Transport transport,
            boolean streaming) {
        if (!prepared.keyed()) {
            return Claim.unkeyed();
        }
        ChatTurnOperation current = prepared.operation() != null
                ? prepared.operation()
                : repository.find(
                        prepared.principal().id(),
                        prepared.keyHash());
        if (current != null) {
            return claimExisting(prepared, command, transport, streaming, current);
        }
        return claimNew(prepared, command, transport, streaming);
    }

    /** 已有同 key operation 的幂等分派：重放、失败复用、续期或回收重跑。 */
    private Claim claimExisting(
            Prepared prepared,
            ChatCommand command,
            ChatTurnOperation.Transport transport,
            boolean streaming,
            ChatTurnOperation current) {
        if (!current.requestFingerprintSha256()
                .equals(prepared.fingerprintHash())) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used for another request");
        }
        if (current.status() == ChatTurnOperation.Status.SUCCEEDED) {
            observability.replayed();
            return new Claim(current, true, null);
        }
        if (current.status() == ChatTurnOperation.Status.FAILED) {
            throw failedReplay(current);
        }
        if (current.leaseExpiresAt() != null
                && current.leaseExpiresAt().isAfter(Instant.now())) {
            observability.inProgress();
            throw inProgress(current);
        }
        if (current.attemptCount()
                >= properties.getIdempotency().getMaxAttempts()) {
            ChatSessionCoordinator.LeaseHandle lease =
                    acquireSessionLease(command, current.sessionId(), streaming);
            try {
                exhaustAttempts(current, lease);
            } finally {
                releaseSessionLease(lease);
            }
            throw failedReplay(repository.find(
                    prepared.principal().id(), prepared.keyHash()));
        }
        return reclaimExisting(prepared, command, transport, streaming, current);
    }

    /** 校验重放授权后回收仍在进行中的 operation；回收失败则按最新状态重入分派。 */
    private Claim reclaimExisting(
            Prepared prepared,
            ChatCommand command,
            ChatTurnOperation.Transport transport,
            boolean streaming,
            ChatTurnOperation current) {
        authorizationService.verifyReplay(current, prepared.principal());
        String reclaimedExecutionSnapshot =
                current.executionSnapshot() == null
                        ? executionSnapshot(
                                command,
                                declaredModelIdentifier(prepared),
                                resolvedCandidateRefs(command, streaming))
                        : null;
        ChatSessionCoordinator.LeaseHandle lease =
                acquireSessionLease(command, current.sessionId(), streaming);
        UUID token = UUID.randomUUID();
        ChatTurnOperation reclaimed = repository.reclaim(
                current,
                token,
                leaseMs(transport),
                reclaimedExecutionSnapshot,
                properties.getIdempotency().getMaxAttempts());
        if (reclaimed != null) {
            Claim claim = new Claim(reclaimed, false, lease);
            startRenewal(claim);
            observability.claimed();
            return claim;
        }
        releaseSessionLease(lease);
        return claim(
                prepared.withOperation(repository.find(
                        prepared.principal().id(),
                        prepared.keyHash())),
                command,
                transport,
                streaming);
    }

    /** 新 key 的首次 claim：规范化会话、抢占会话租约并插入 operation。 */
    private Claim claimNew(
            Prepared prepared,
            ChatCommand command,
            ChatTurnOperation.Transport transport,
            boolean streaming) {
        ChatCommand effectiveCommand = withEffectiveSession(command);
        List<String> resolvedCandidates = resolvedCandidateRefs(
                effectiveCommand, streaming);
        ChatSessionCoordinator.LeaseHandle lease;
        try {
            lease = acquireSessionLease(
                    effectiveCommand, effectiveCommand.sessionId(), streaming);
        } catch (RagException error) {
            if (error.getErrorCodeEnum() != ErrorCode.SESSION_BUSY) {
                throw error;
            }
            ChatTurnOperation raced = repository.find(
                    prepared.principal().id(), prepared.keyHash());
            if (raced == null) {
                throw error;
            }
            return claim(
                    prepared.withOperation(raced),
                    effectiveCommand,
                    transport,
                    streaming);
        }
        return insertNewOperation(
                prepared, effectiveCommand, transport, streaming, resolvedCandidates, lease);
    }

    /** 会话 id 非法时生成新会话并派生带新会话的等价 ChatCommand。 */
    private ChatCommand withEffectiveSession(ChatCommand command) {
        String effectiveSession = SessionIdValidator.isValid(command.sessionId())
                ? command.sessionId()
                : UUID.randomUUID().toString();
        if (effectiveSession.equals(command.sessionId())) {
            return command;
        }
        return new ChatCommand(
                command.message(),
                effectiveSession,
                command.principal(),
                command.principal().memoryConversationId(effectiveSession),
                command.mode(),
                command.memoryMode(),
                command.modelRef(),
                command.domainId(),
                command.retrievalScope(),
                command.retrievalOptions(),
                command.clientMetadata(),
                command.inputMessages(),
                command.modelCandidates(),
                command.retrievalTraceSession(),
                command.retrievalFilters(),
                command.executionBudget());
    }

    /** 插入新 operation 行；插入成功则领取，竞争失败则按最新状态重入分派。 */
    private Claim insertNewOperation(
            Prepared prepared,
            ChatCommand effectiveCommand,
            ChatTurnOperation.Transport transport,
            boolean streaming,
            List<String> resolvedCandidates,
            ChatSessionCoordinator.LeaseHandle lease) {
        UUID turnId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        boolean inserted;
        try {
            inserted = repository.insert(
                    prepared.principal().id(),
                    prepared.keyHash(),
                    prepared.fingerprintHash(),
                    effectiveCommand.sessionId(),
                    turnId,
                    transport,
                    token,
                    leaseMs(transport),
                    executionSnapshot(
                            effectiveCommand,
                            declaredModelIdentifier(prepared),
                            resolvedCandidates),
                    authorizationService.initialSnapshot(effectiveCommand));
        } catch (RuntimeException error) {
            releaseSessionLease(lease);
            throw error;
        }
        if (inserted) {
            Claim claim = new Claim(
                    repository.find(
                            prepared.principal().id(),
                            prepared.keyHash()),
                    false,
                    lease);
            startRenewal(claim);
            observability.claimed();
            return claim;
        }
        releaseSessionLease(lease);
        return claim(
                prepared.withOperation(repository.find(
                        prepared.principal().id(),
                        prepared.keyHash())),
                effectiveCommand,
                transport,
                streaming);
    }

    public ChatResponse replay(Claim claim) {
        if (claim == null || !claim.replay()) {
            throw new IllegalArgumentException("claim is not a replay");
        }
        try {
            authorizationService.verifyReplay(
                    claim.operation(),
                    principalFor(claim.operation()));
            ChatResponse response = objectMapper.readValue(
                    claim.operation().responsePayload(),
                    ChatResponse.class);
            response.setTurnId(claim.operation().turnId().toString());
            return response;
        } catch (RagException e) {
            throw e;
        } catch (Exception e) {
            throw new RagException(
                    ErrorCode.INTERNAL_ERROR,
                    "Stored Chat response snapshot is invalid",
                    e);
        }
    }

    /**
     * Applies the immutable first-claim execution context to the adapter
     * command. The provider candidate chain must come from the durable
     * operation snapshot, not from a registry that may have changed after the
     * operation was claimed.
     */
    public ChatCommand commandForClaim(
            ChatCommand command,
            Claim claim) {
        if (command == null || claim == null || !claim.keyed()) {
            return command;
        }
        ChatTurnOperation operation = claim.operation();
        ChatCommand effective = command.withSessionId(operation.sessionId());
        List<String> candidates = snapshotCandidates(operation);
        if (!candidates.isEmpty()) {
            effective = effective.withModelCandidates(candidates);
        }
        return effective;
    }

    public ChatResponse complete(
            Claim claim,
            ChatResponse response,
            com.springairag.api.dto.ChatRequest request) {
        return completeWithDescriptor(
                claim,
                response,
                request.getMode().name(),
                "SERVER",
                request.getModel(),
                request.getDomainId());
    }

    public ChatResponse completeOpenAi(
            Claim claim,
            ChatResponse response,
            String mode,
            String memoryMode,
            String model,
            String domainId) {
        return completeWithDescriptor(
                claim, response, mode, memoryMode, model, domainId);
    }

    public ChatResponse completePrepared(
            Claim claim,
            ChatExecutionService.PreparedExecution prepared) {
        if (claim == null || !claim.keyed()) {
            return toChatResponse(prepared.result());
        }
        stopRenewal(claim);
        ChatResponse response = toChatResponse(prepared.result());
        String payload = responsePayload(response, claim.operation().turnId());
        String execution = claim.operation().executionSnapshot();
        if (execution == null) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                    "Chat operation execution snapshot is missing");
        }
        String authorization = authorizationService.snapshot(
                prepared.command(), response);
        if (sessionCoordinator != null && claim.sessionLease() != null) {
            sessionCoordinator.commitOperation(
                    claim.sessionLease(),
                    claim.operation(),
                    prepared.command(),
                    prepared.result(),
                    prepared.committedMessages(),
                    prepared.relatedDocumentIds(),
                    execution,
                    payload,
                    authorization);
        } else {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_DISABLED,
                    "Chat idempotency requires the coordinated PostgreSQL session service");
        }
        return stableSnapshot(response, claim.operation().turnId());
    }

    private ChatResponse completeWithDescriptor(
            Claim claim,
            ChatResponse response,
            String mode,
            String memoryMode,
            String model,
            String domainId) {
        if (claim == null || !claim.keyed()) {
            return response;
        }
        ChatResponse stable = stableSnapshot(response, claim.operation().turnId());
        try {
            String payload = objectMapper.writeValueAsString(stable);
            byte[] bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > properties.getIdempotency()
                    .getResponseSnapshotMaxBytes()) {
                throw new RagException(
                        ErrorCode.IDEMPOTENCY_RESPONSE_TOO_LARGE,
                        "Chat response snapshot exceeds configured size");
            }
            String execution = objectMapper.writeValueAsString(Map.of(
                    "executionSnapshotVersion", 1,
                    "mode", mode,
                    "memoryMode", memoryMode,
                    "declaredModelIdentifier",
                    model == null ? "DEFAULT" : model,
                    "publicModelAlias",
                    model == null ? "DEFAULT" : model,
                    "domainId", domainId == null ? "" : domainId));
            if (execution.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > properties.getIdempotency()
                    .getExecutionSnapshotMaxBytes()) {
                throw new RagException(
                        ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                        "Chat execution snapshot exceeds configured size");
            }
            if (!repository.completeSuccess(
                    claim.operation(), execution, payload)) {
                throw new RagException(
                        ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                        "Chat operation lease was lost before completion");
            }
            return stable;
        } catch (RagException e) {
            throw e;
        } catch (Exception e) {
            throw new RagException(
                    ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                    "Failed to persist Chat response snapshot",
                    e);
        }
    }

    public void fail(Claim claim, Throwable error) {
        if (claim == null || !claim.keyed()
                || claim.operation().status() != ChatTurnOperation.Status.IN_PROGRESS) {
            return;
        }
        observability.failed();
        stopRenewal(claim);
        ErrorCode code = error instanceof RagException rag
                ? rag.getErrorCodeEnum()
                : ErrorCode.INTERNAL_ERROR;
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "errorSnapshotVersion", 1,
                    "httpStatus", code.getHttpStatus(),
                    "errorCode", code.getCode(),
                    "retryable", false));
        } catch (Exception e) {
            payload = "{\"errorSnapshotVersion\":1,\"httpStatus\":500,"
                    + "\"errorCode\":\"INTERNAL_ERROR\",\"retryable\":false}";
            code = ErrorCode.INTERNAL_ERROR;
        }
        if (sessionCoordinator != null && claim.sessionLease() != null) {
            sessionCoordinator.failOperation(
                    claim.sessionLease(),
                    claim.operation(),
                    code.getCode(),
                    payload);
        } else {
            repository.completeFailure(claim.operation(), code.getCode(), payload);
        }
    }

    public void release(Claim claim) {
        stopRenewal(claim);
        if (claim != null && claim.sessionLease() != null
                && sessionCoordinator != null) {
            sessionCoordinator.release(claim.sessionLease());
        }
    }

    public ChatTurnStatusResponse status(
            ChatPrincipal principal,
            UUID turnId,
            boolean includeResponse) {
        ChatTurnOperation operation = repository.findByTurn(
                principal.id(), turnId);
        if (operation == null) {
            throw new RagException(
                    ErrorCode.CHAT_TURN_NOT_FOUND,
                    "Chat turn was not found");
        }
        ChatResponse response = null;
        boolean replayAvailable = false;
        if (includeResponse && operation.status()
                == ChatTurnOperation.Status.SUCCEEDED) {
            authorizationService.verifyReplay(operation, principal);
            replayAvailable = true;
            response = deserializeResponse(operation);
        } else if (operation.status() == ChatTurnOperation.Status.SUCCEEDED) {
            try {
                authorizationService.verifyReplay(operation, principal);
                replayAvailable = true;
            } catch (RagException error) {
                if (error.getErrorCodeEnum() != ErrorCode.FORBIDDEN) {
                    throw error;
                }
            }
        }
        return new ChatTurnStatusResponse(
                operation.turnId().toString(),
                operation.sessionId(),
                operation.status().name(),
                operation.transport().name(),
                operation.createdAt(),
                operation.updatedAt(),
                operation.completedAt(),
                replayAvailable,
                operation.errorCode(),
                response);
    }

    private ChatResponse deserializeResponse(ChatTurnOperation operation) {
        try {
            ChatResponse response = objectMapper.readValue(
                    operation.responsePayload(),
                    ChatResponse.class);
            response.setTurnId(operation.turnId().toString());
            return response;
        } catch (Exception error) {
            throw new RagException(
                    ErrorCode.INTERNAL_ERROR,
                    "Stored Chat response snapshot is invalid",
                    error);
        }
    }

    private ChatResponse stableSnapshot(
            ChatResponse response,
            UUID turnId) {
        try {
            if (response == null) {
                throw new IllegalArgumentException(
                        "Chat response must not be null");
            }
            return ChatResponse.builder()
                    .answer(response.getAnswer())
                    .sources(stableSources(response.getSources()))
                    .sessionId(response.getSessionId())
                    .turnId(turnId.toString())
                    .mode(response.getMode())
                    .requestedModel(response.getRequestedModel())
                    .resolvedModel(response.getResolvedModel())
                    .usage(stableMap(response.getUsage()))
                    .finishReason(response.getFinishReason())
                    .metadata(stableMetadata(response.getMetadata(), turnId))
                    .stepMetrics(stableStepMetrics(response.getStepMetrics()))
                    .build();
        } catch (Exception e) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_RESPONSE_TOO_LARGE,
                    "Chat response cannot be serialized for replay",
                    e);
        }
    }

    private List<com.springairag.api.dto.ChatSource> stableSources(
            List<? extends com.springairag.api.dto.ChatSource> sources) {
        if (sources == null) {
            return null;
        }
        return sources.stream()
                .map(this::stableSource)
                .toList();
    }

    private com.springairag.api.dto.ChatSource stableSource(
            com.springairag.api.dto.ChatSource source) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "Chat response source must not be null");
        }
        com.springairag.api.dto.ChatSource copy =
                new com.springairag.api.dto.ChatSource();
        copy.setCitationId(source.getCitationId());
        copy.setDocumentId(source.getDocumentId());
        copy.setChunkIndex(source.getChunkIndex());
        copy.setTitle(source.getTitle());
        copy.setChunkText(source.getChunkText());
        copy.setScore(source.getScore());
        copy.setVectorScore(source.getVectorScore());
        copy.setFulltextScore(source.getFulltextScore());
        copy.setOriginalFilename(source.getOriginalFilename());
        copy.setDocumentType(source.getDocumentType());
        copy.setCollectionKey(source.getCollectionKey());
        copy.setSourceType(source.getSourceType());
        copy.setMetadata(stableMap(source.getMetadata()));
        return copy;
    }

    private List<com.springairag.api.dto.ChatResponse.StepMetricRecord>
            stableStepMetrics(
                    List<com.springairag.api.dto.ChatResponse.StepMetricRecord>
                            metrics) {
        if (metrics == null) {
            return null;
        }
        return metrics.stream()
                .map(metric -> {
                    if (metric == null) {
                        throw new IllegalArgumentException(
                                "Chat response step metric must not be null");
                    }
                    return new com.springairag.api.dto.ChatResponse.StepMetricRecord(
                            metric.getStepName(),
                            metric.getDurationMs(),
                            metric.getResultCount());
                })
                .toList();
    }

    private Map<String, Object> stableMetadata(
            Map<String, Object> metadata,
            UUID turnId) {
        Map<String, Object> stable = new LinkedHashMap<>();
        if (metadata != null) {
            List<String> allowed = List.of(
                    "sessionId",
                    "retrieval",
                    "retrievalExecuted",
                    "retrievalTraceId",
                    "citationValidation",
                    "mode",
                    "memoryMode",
                    "requestedModel",
                    "resolvedModel",
                    "finishReason",
                    "usage",
                    "stepMetrics",
                    "executionBudget",
                    "summary");
            for (String key : allowed) {
                if (metadata.containsKey(key)) {
                    stable.put(key, stableValue(metadata.get(key)));
                }
            }
        }
        stable.put("turnId", turnId.toString());
        return stable;
    }

    private Map<String, Object> stableMap(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, entry) -> copy.put(key, stableValue(entry)));
        return copy;
    }

    private Object stableValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(
                    objectMapper.valueToTree(value), Object.class);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "Chat response snapshot contains a non-JSON value",
                    error);
        }
    }

    private String responsePayload(
            ChatResponse response,
            UUID turnId) {
        try {
            ChatResponse stable = stableSnapshot(response, turnId);
            String payload = objectMapper.writeValueAsString(stable);
            if (payload.getBytes(StandardCharsets.UTF_8).length
                    > properties.getIdempotency().getResponseSnapshotMaxBytes()) {
                throw new RagException(
                        ErrorCode.IDEMPOTENCY_RESPONSE_TOO_LARGE,
                        "Chat response snapshot exceeds configured size");
            }
            return payload;
        } catch (RagException e) {
            throw e;
        } catch (Exception e) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_RESPONSE_TOO_LARGE,
                    "Chat response cannot be serialized for replay",
                    e);
        }
    }

    private ChatResponse responseWithTurnId(
            ChatResponse response,
            UUID turnId) {
        response.setTurnId(turnId.toString());
        var metadata = response.getMetadata() == null
                ? new java.util.LinkedHashMap<String, Object>()
                : new java.util.LinkedHashMap<>(response.getMetadata());
        metadata.put("turnId", turnId.toString());
        response.setMetadata(metadata);
        return response;
    }

    private String executionSnapshot(
            ChatCommand command,
            String declaredModelIdentifier,
            List<String> resolvedCandidates) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("executionSnapshotVersion", 1);
            snapshot.put("mode", command.mode().name());
            snapshot.put("memoryMode", command.memoryMode().name());
            snapshot.put(
                    "declaredModelIdentifier",
                    declaredModelIdentifier == null
                            || declaredModelIdentifier.isBlank()
                            ? "DEFAULT" : declaredModelIdentifier);
            snapshot.put(
                    "publicModelAlias",
                    declaredModelIdentifier == null
                            || declaredModelIdentifier.isBlank()
                            ? "DEFAULT" : declaredModelIdentifier);
            snapshot.put(
                    "resolvedCandidates",
                    resolvedCandidates == null
                            ? List.of() : List.copyOf(resolvedCandidates));
            snapshot.put("domainId", command.domainId());
            Map<String, Object> retrieval = new LinkedHashMap<>();
            retrieval.put("maxResults",
                    command.retrievalOptions().maxResults());
            retrieval.put("minScore",
                    command.retrievalOptions().minScore());
            retrieval.put("useHybridSearch",
                    command.retrievalOptions().useHybridSearch());
            retrieval.put("useRerank",
                    command.retrievalOptions().useRerank());
            retrieval.put("vectorWeight",
                    command.retrievalOptions().vectorWeight());
            retrieval.put("fulltextWeight",
                    command.retrievalOptions().fulltextWeight());
            snapshot.put("retrievalOptions", retrieval);
            snapshot.put("effectiveScope", Map.of(
                    "collectionFilter",
                    command.retrievalScope().collectionFilter().name(),
                    "collectionIds",
                    command.retrievalScope().collectionIds(),
                    "documentIds",
                    command.retrievalScope().documentIds(),
                    "documentType",
                    command.retrievalScope().documentType() == null
                            ? "" : command.retrievalScope().documentType(),
                    "matchNone",
                    command.retrievalScope().matchNone()));
            String value = objectMapper.writeValueAsString(snapshot);
            if (value.getBytes(StandardCharsets.UTF_8).length
                    > properties.getIdempotency()
                            .getExecutionSnapshotMaxBytes()) {
                throw new RagException(
                        ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                        "Chat execution snapshot exceeds configured size");
            }
            return value;
        } catch (RagException e) {
            throw e;
        } catch (Exception e) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                    "Chat execution snapshot is invalid",
                    e);
        }
    }

    private List<String> snapshotCandidates(ChatTurnOperation operation) {
        if (operation == null || operation.executionSnapshot() == null) {
            return List.of();
        }
        try {
            var snapshot = objectMapper.readTree(
                    operation.executionSnapshot());
            if (snapshot == null
                    || snapshot.path("executionSnapshotVersion").asInt() != 1) {
                throw new RagException(
                        ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                        "Chat execution snapshot is invalid");
            }
            var candidates = snapshot.path("resolvedCandidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                throw new RagException(
                        ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                        "Chat execution candidate chain is missing");
            }
            List<String> result = new ArrayList<>();
            for (var candidate : candidates) {
                if (!candidate.isTextual() || candidate.asText().isBlank()) {
                    throw new RagException(
                            ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                            "Chat execution candidate chain is invalid");
                }
                result.add(candidate.asText());
            }
            return List.copyOf(result);
        } catch (RagException error) {
            throw error;
        } catch (Exception error) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                    "Chat execution snapshot is invalid",
                    error);
        }
    }

    private List<String> resolvedCandidateRefs(
            ChatCommand command,
            boolean streaming) {
        if (executionService == null) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                    "Chat execution resolver is not configured");
        }
        List<String> candidates = executionService.resolveCandidateRefs(
                command, streaming);
        if (candidates == null || candidates.isEmpty()) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                    "Chat execution candidate chain is empty");
        }
        return List.copyOf(candidates);
    }

    private String declaredModelIdentifier(Prepared prepared) {
        if (prepared == null || prepared.canonical() == null) {
            return "DEFAULT";
        }
        String value = prepared.canonical()
                .path("declaredModelIdentifier")
                .asText(null);
        return value == null || value.isBlank() ? "DEFAULT" : value;
    }

    private ChatResponse toChatResponse(ChatExecutionResult result) {
        return ChatResponse.builder()
                .answer(result.answer())
                .sessionId(result.sessionId())
                .traceId(result.traceId())
                .mode(result.mode())
                .requestedModel(result.requestedModel())
                .resolvedModel(result.resolvedModel())
                .sources(result.sources())
                .usage(result.usage())
                .finishReason(result.finishReason())
                .metadata(result.metadata())
                .stepMetrics(result.stepMetrics())
                .build();
    }

    private ChatSessionCoordinator.LeaseHandle acquireSessionLease(
            ChatCommand command,
            String sessionId,
            boolean streaming) {
        if (sessionCoordinator == null) {
            return null;
        }
        if (sessionId.equals(command.sessionId())) {
            return sessionCoordinator.acquire(command, streaming);
        }
        ChatCommand adjusted = new ChatCommand(
                command.message(),
                sessionId,
                command.principal(),
                command.principal().memoryConversationId(sessionId),
                command.mode(),
                command.memoryMode(),
                command.modelRef(),
                command.domainId(),
                command.retrievalScope(),
                command.retrievalOptions(),
                command.clientMetadata(),
                command.inputMessages(),
                command.modelCandidates(),
                command.retrievalTraceSession(),
                command.retrievalFilters(),
                command.executionBudget());
        return sessionCoordinator.acquire(adjusted, streaming);
    }

    private void releaseSessionLease(
            ChatSessionCoordinator.LeaseHandle lease) {
        if (sessionCoordinator != null && lease != null) {
            sessionCoordinator.release(lease);
        }
    }

    private RagException failedReplay(ChatTurnOperation operation) {
        ErrorCode code;
        try {
            code = ErrorCode.valueOf(operation.errorCode());
        } catch (Exception e) {
            code = ErrorCode.INTERNAL_ERROR;
        }
        return new RagException(code, "The Chat turn previously failed");
    }

    private ChatPrincipal principalFor(ChatTurnOperation operation) {
        String owner = operation.ownerPrincipalId();
        if (owner != null && owner.startsWith("db:")) {
            return new ChatPrincipal(owner, "DATABASE_API_KEY", false);
        }
        if ("root:environment-root".equals(owner)) {
            return new ChatPrincipal(owner, "ENVIRONMENT_ROOT", true);
        }
        if ("legacy:static".equals(owner)) {
            return new ChatPrincipal(owner, "LEGACY_STATIC", false);
        }
        return ChatPrincipal.local();
    }

    private ChatTurnInProgressException inProgress(ChatTurnOperation operation) {
        long remaining = operation.leaseExpiresAt() == null
                ? 1
                : Duration.between(Instant.now(),
                        operation.leaseExpiresAt()).toSeconds();
        return new ChatTurnInProgressException(
                (int) Math.max(1, Math.min(60, remaining + 1)));
    }

    private void exhaustAttempts(
            ChatTurnOperation operation,
            ChatSessionCoordinator.LeaseHandle lease) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "errorSnapshotVersion", 1,
                    "httpStatus", ErrorCode.IDEMPOTENCY_ATTEMPTS_EXHAUSTED.getHttpStatus(),
                    "errorCode", ErrorCode.IDEMPOTENCY_ATTEMPTS_EXHAUSTED.getCode(),
                    "retryable", false,
                    "attempt", operation.attemptCount()));
        } catch (Exception e) {
            payload = "{\"errorSnapshotVersion\":1,\"httpStatus\":503,"
                    + "\"errorCode\":\"IDEMPOTENCY_ATTEMPTS_EXHAUSTED\","
                    + "\"retryable\":false}";
        }
        if (sessionCoordinator != null && lease != null) {
            sessionCoordinator.failExpiredOperation(
                    lease,
                    operation,
                    ErrorCode.IDEMPOTENCY_ATTEMPTS_EXHAUSTED.getCode(),
                    payload);
        } else if (!repository.exhaustAttempts(
                operation,
                ErrorCode.IDEMPOTENCY_ATTEMPTS_EXHAUSTED.getCode(),
                payload)) {
            throw new RagException(
                    ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                    "Chat operation reclaim state changed concurrently");
        }
    }

    /**
     * Starts bounded background renewal for the durable operation lease.
     * The mutable Claim carries the latest row version so terminal CAS writes
     * cannot be invalidated by an earlier renewal result.
     */
    public void startRenewal(Claim claim) {
        if (claim == null || !claim.keyed() || claim.replay()) {
            return;
        }
        synchronized (claim.monitor()) {
            if (claim.renewal() != null) {
                return;
            }
            long period = Math.max(
                    1_000L,
                    leaseMs(claim.operation().transport()) / 3L);
            claim.setRenewal(renewalExecutor.scheduleAtFixedRate(() -> {
                synchronized (claim.monitor()) {
                    if (claim.renewalStopped()) {
                        return;
                    }
                    ChatTurnOperation renewed = repository.renew(
                            claim.operation(),
                            leaseMs(claim.operation().transport()));
                    if (renewed == null) {
                        claim.markRenewalLost();
                    } else {
                        claim.updateOperation(renewed);
                    }
                }
            }, period, period, TimeUnit.MILLISECONDS));
        }
    }

    private void stopRenewal(Claim claim) {
        if (claim == null) {
            return;
        }
        synchronized (claim.monitor()) {
            claim.stopRenewal();
        }
    }

    private int leaseMs(ChatTurnOperation.Transport transport) {
        boolean streaming = transport == ChatTurnOperation.Transport.NATIVE_SSE
                || transport == ChatTurnOperation.Transport.OPENAI_SSE;
        int endpointDeadline = streaming
                ? ragProperties.getTimeout().getChatStreamMs()
                : ragProperties.getTimeout().getChatAskMs();
        long requested = (long) Math.max(1_000, endpointDeadline)
                + properties.getIdempotency().getLeaseGraceMs();
        return (int) Math.min(
                Integer.MAX_VALUE,
                Math.max(30_000L, requested));
    }

    @PreDestroy
    void shutdown() {
        renewalExecutor.shutdownNow();
    }

    public record Prepared(
            ChatPrincipal principal,
            String keyHash,
            String fingerprintHash,
            com.fasterxml.jackson.databind.JsonNode canonical,
            ChatTurnOperation operation,
            boolean keyed) {
        static Prepared disabled(ChatPrincipal principal) {
            return new Prepared(
                    principal, null, null, null, null, false);
        }

        Prepared withOperation(ChatTurnOperation value) {
            return new Prepared(
                    principal, keyHash, fingerprintHash, canonical, value, keyed);
        }
    }

    public static final class Claim {
        private final AtomicReference<ChatTurnOperation> operationRef;
        private final boolean replay;
        private final ChatSessionCoordinator.LeaseHandle sessionLease;
        private final Object monitor = new Object();
        private volatile ScheduledFuture<?> renewal;
        private volatile boolean renewalStopped;
        private volatile boolean renewalLost;

        private Claim(
                ChatTurnOperation operation,
                boolean replay,
                ChatSessionCoordinator.LeaseHandle sessionLease) {
            this.operationRef = new AtomicReference<>(operation);
            this.replay = replay;
            this.sessionLease = sessionLease;
        }

        public Claim(ChatTurnOperation operation, boolean replay) {
            this(operation, replay, null);
        }

        static Claim unkeyed() {
            return new Claim(null, false, null);
        }

        public ChatTurnOperation operation() {
            return operationRef.get();
        }

        public boolean replay() {
            return replay;
        }

        public ChatSessionCoordinator.LeaseHandle sessionLease() {
            return sessionLease;
        }

        public boolean keyed() {
            return operationRef.get() != null;
        }

        Object monitor() {
            return monitor;
        }

        ScheduledFuture<?> renewal() {
            return renewal;
        }

        void setRenewal(ScheduledFuture<?> value) {
            renewal = value;
        }

        boolean renewalStopped() {
            return renewalStopped;
        }

        void stopRenewal() {
            renewalStopped = true;
            if (renewal != null) {
                renewal.cancel(false);
                renewal = null;
            }
        }

        void markRenewalLost() {
            renewalLost = true;
            renewalStopped = true;
        }

        void updateOperation(ChatTurnOperation value) {
            operationRef.set(value);
        }

        boolean renewalLost() {
            return renewalLost;
        }
    }
}
