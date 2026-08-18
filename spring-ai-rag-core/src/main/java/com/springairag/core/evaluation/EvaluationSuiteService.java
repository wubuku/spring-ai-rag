package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.springairag.api.dto.EvaluationCaseResultResponse;
import com.springairag.api.dto.EvaluationCompareResponse;
import com.springairag.api.dto.EvaluationRunCreateRequest;
import com.springairag.api.dto.EvaluationRunResponse;
import com.springairag.api.dto.EvaluationSuiteCreateRequest;
import com.springairag.api.dto.EvaluationSuiteResponse;
import com.springairag.api.dto.EvaluationSuiteVersionCreateRequest;
import com.springairag.api.dto.EvaluationSuiteVersionResponse;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagEvaluationProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.RetrievalEvaluationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class EvaluationSuiteService {

    private final EvaluationSuiteRepository repository;
    private final EvaluationSuiteDefinitionValidator validator;
    private final CollectionRetrievalScopeResolver scopeResolver;
    private final EvaluationCaseExecutor caseExecutor;
    private final RetrievalEvaluationService metricsService;
    private final EmbeddingProfileProvider profileProvider;
    private final ObjectMapper objectMapper;
    private final RagEvaluationProperties properties;
    private final RagApiKeyRepository apiKeyRepository;

    public EvaluationSuiteService(
            EvaluationSuiteRepository repository,
            EvaluationSuiteDefinitionValidator validator,
            CollectionRetrievalScopeResolver scopeResolver,
            EvaluationCaseExecutor caseExecutor,
            RetrievalEvaluationService metricsService,
            EmbeddingProfileProvider profileProvider,
            ObjectMapper objectMapper,
            RagProperties ragProperties,
            @Autowired(required = false) RagApiKeyRepository apiKeyRepository) {
        this.repository = repository;
        this.validator = validator;
        this.scopeResolver = scopeResolver;
        this.caseExecutor = caseExecutor;
        this.metricsService = metricsService;
        this.profileProvider = profileProvider;
        this.objectMapper = objectMapper;
        this.properties = ragProperties.getEvaluation();
        this.apiKeyRepository = apiKeyRepository;
    }

    public void requireEnabled() {
        if (!properties.isManagedSuitesEnabled()) {
            throw new RagException(
                    ErrorCode.EVALUATION_SUITES_DISABLED,
                    "Managed evaluation suites are disabled");
        }
    }

    @Transactional
    public EvaluationSuiteResponse createSuite(EvaluationSuiteCreateRequest request) {
        requireEnabled();
        try {
            var row = repository.insertSuite(
                    request.suiteKey().trim(),
                    request.name().trim(),
                    ChatPrincipal.fromCurrentRequest().id());
            return toSuite(row);
        } catch (DuplicateKeyException e) {
            throw new RagException(
                    ErrorCode.DUPLICATE_RESOURCE,
                    "suiteKey already exists for this principal");
        }
    }

    public List<EvaluationSuiteResponse> listSuites() {
        requireEnabled();
        return repository.listSuites(ChatPrincipal.fromCurrentRequest().id())
                .stream().map(this::toSuite).toList();
    }

    public EvaluationSuiteResponse getSuite(String suiteKey) {
        requireEnabled();
        return toSuite(requireSuite(suiteKey));
    }

    @Transactional
    public EvaluationSuiteVersionResponse createVersion(
            String suiteKey, EvaluationSuiteVersionCreateRequest request) {
        requireEnabled();
        var suite = requireSuite(suiteKey);
        EvaluationSuiteDefinition definition = validator.parse(request.definition());
        authorizeDefinition(definition, currentExecutionKey());
        try {
            var version = repository.insertVersion(
                    suite.id(), definition.canonicalJson(), definition.sha256());
            return toVersion(suite, version);
        } catch (DuplicateKeyException e) {
            throw new RagException(
                    ErrorCode.DUPLICATE_RESOURCE,
                    "An identical definition already exists for this suite");
        }
    }

    @Transactional
    public EvaluationRunResponse createRun(EvaluationRunCreateRequest request) {
        requireEnabled();
        String owner = ChatPrincipal.fromCurrentRequest().id();
        var suite = requireSuite(request.suiteKey());
        var version = repository.findVersion(suite.id(), request.version())
                .orElseThrow(() -> new RagException(
                        ErrorCode.NOT_FOUND, "Suite version not found"));
        EvaluationSuiteDefinition definition = validator.parse(version.definition());
        authorizeDefinition(definition, currentExecutionKey());
        List<String> selectedVariants = selectVariants(definition, request.variantKeys());
        if (selectedVariants.size() > properties.getMaxVariantsPerRun()) {
            throw new IllegalArgumentException(
                    "A run may use at most " + properties.getMaxVariantsPerRun() + " variants");
        }
        Map<String, Object> snapshot = caseExecutor.collectionSnapshot(allCollectionKeys(definition));
        ObjectNode configuration = objectMapper.createObjectNode();
        configuration.set("collectionSnapshot", objectMapper.valueToTree(snapshot));
        configuration.set("variantKeys", objectMapper.valueToTree(selectedVariants));
        for (int slot = 0; slot < Math.max(1, properties.getMaxConcurrentRuns()); slot++) {
            var run = repository.tryInsertRun(
                    version.id(),
                    owner,
                    "PENDING",
                    configuration.toString(),
                    currentRevision(),
                    profileProvider.getActiveProfile().profileKey(),
                    slot);
            if (run.isPresent()) {
                return toRun(suite, version, run.get(), List.of());
            }
        }
        throw new RagException(
                ErrorCode.CONCURRENT_EVALUATION_LIMIT,
                "A managed evaluation run is already active");
    }

    public EvaluationRunResponse getRun(UUID runId) {
        requireEnabled();
        String owner = ChatPrincipal.fromCurrentRequest().id();
        var run = repository.findRun(runId, owner)
                .orElseThrow(() -> new RagException(ErrorCode.NOT_FOUND, "Run not found"));
        var version = repository.findVersionById(run.suiteVersionId())
                .orElseThrow(() -> new RagException(ErrorCode.NOT_FOUND, "Suite version not found"));
        var suite = requireSuiteById(version.suiteId(), owner);
        authorizeDefinition(validator.parse(version.definition()), currentExecutionKey());
        return toRun(suite, version, run, repository.listCaseResults(runId));
    }

    public EvaluationCompareResponse compare(UUID leftRunId, UUID rightRunId) {
        requireEnabled();
        EvaluationRunResponse left = getRun(leftRunId);
        EvaluationRunResponse right = getRun(rightRunId);
        boolean sameVersion = Objects.equals(left.suiteKey(), right.suiteKey())
                && left.version() == right.version()
                && Objects.equals(left.definitionSha256(), right.definitionSha256());
        if (!sameVersion) {
            throw new IllegalArgumentException(
                    "compare only accepts two runs of the same suite version");
        }
        Set<String> leftVariants = variantKeys(left);
        Set<String> rightVariants = variantKeys(right);
        if (!leftVariants.equals(rightVariants)) {
            throw new IllegalArgumentException("compare requires identical variant keys");
        }
        boolean sameProfile = Objects.equals(left.embeddingProfileKey(), right.embeddingProfileKey());
        boolean sameRevision = Objects.equals(left.codeRevision(), right.codeRevision());
        JsonNode leftSnapshot = left.configurationSnapshot().path("collectionSnapshot");
        JsonNode rightSnapshot = right.configurationSnapshot().path("collectionSnapshot");
        boolean sameCorpus = leftSnapshot.equals(rightSnapshot);
        boolean drift = !sameProfile || !sameRevision || !sameCorpus;
        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("environmentDrift", drift);
        return new EvaluationCompareResponse(
                left.id(), right.id(), true, drift, sameProfile, sameRevision, sameCorpus,
                left.aggregateMetrics(), right.aggregateMetrics(), delta);
    }

    public void executeRun(EvaluationSuiteRepository.RunRow run, String workerId) {
        UUID runId = run.id();
        final RagApiKey executionKey;
        try {
            executionKey = resolveExecutionKey(run.ownerPrincipalId());
        } catch (SecurityException e) {
            repository.finishRun(
                    runId, workerId, "FAILED", "{}", "AUTHORIZATION_CHANGED");
            return;
        }
        var version = repository.findVersionById(run.suiteVersionId())
                .orElseThrow(() -> new RagException(ErrorCode.NOT_FOUND, "Suite version not found"));
        EvaluationSuiteDefinition definition = validator.parse(version.definition());
        try {
            authorizeDefinition(definition, executionKey);
        } catch (SecurityException e) {
            repository.finishRun(
                    runId, workerId, "FAILED", "{}", "AUTHORIZATION_CHANGED");
            return;
        }
        List<String> variantKeys = readVariantKeys(run.configurationSnapshot());
        JsonNode before = run.configurationSnapshot().path("collectionSnapshot");
        boolean failed = false;
        boolean skipped = false;
        boolean corpusChanged = false;
        List<Double> hitRates = new ArrayList<>();
        List<Double> mrrs = new ArrayList<>();
        List<PendingCase> pendingCases = new ArrayList<>();
        for (EvaluationSuiteDefinition.VariantDef variant : definition.variants()) {
            if (!variantKeys.contains(variant.key())) {
                continue;
            }
            for (EvaluationSuiteDefinition.CaseDef caseDef : definition.cases()) {
                pendingCases.add(new PendingCase(caseDef, variant));
            }
        }
        for (CompletedCase completed : executeCases(pendingCases, executionKey)) {
            CaseOutcome outcome = completed.outcome();
            if (repository.insertCaseResult(
                        runId, workerId, completed.variantKey(), completed.caseId(), outcome.status(),
                        writeJson(outcome.identities()), writeJson(outcome.metrics()),
                        outcome.latencyMs(), outcome.traceId(), outcome.errorCode()) == 0) {
                return;
            }
            if ("FAILED".equals(outcome.status()) || "MISSING_FIXTURE".equals(outcome.status())) {
                failed = true;
            } else if ("SKIPPED".equals(outcome.status())) {
                skipped = true;
            } else if (outcome.metrics() != null && outcome.metrics().get("hitRate") != null) {
                hitRates.add(((Number) outcome.metrics().get("hitRate")).doubleValue());
                mrrs.add(((Number) outcome.metrics().get("mrr")).doubleValue());
            }
        }
        JsonNode after = objectMapper.valueToTree(
                caseExecutor.collectionSnapshot(allCollectionKeys(definition)));
        if (!before.equals(after)) {
            corpusChanged = true;
        }
        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("avgHitRate", average(hitRates));
        aggregate.put("avgMrr", average(mrrs));
        aggregate.put("caseCount", definition.cases().size() * variantKeys.size());
        String status = corpusChanged ? "CORPUS_CHANGED"
                : failed ? "FAILED"
                : skipped ? "SKIPPED"
                : "PASSED";
        repository.finishRun(runId, workerId, status, writeJson(aggregate),
                corpusChanged ? "CORPUS_CHANGED" : failed ? "FAILED" : null);
    }

    private List<CompletedCase> executeCases(
            List<PendingCase> pendingCases,
            RagApiKey executionKey) {
        if (pendingCases.isEmpty()) {
            return List.of();
        }
        int concurrency = Math.min(
                properties.getRunConcurrency(), pendingCases.size());
        if (concurrency <= 1) {
            return pendingCases.stream()
                    .map(item -> new CompletedCase(
                            item.variant().key(),
                            item.caseDef().id(),
                            executeCase(item.caseDef(), item.variant(), executionKey)))
                    .toList();
        }
        ExecutorService executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "evaluation-suite-case");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<CaseOutcome>> futures = new ArrayList<>(pendingCases.size());
            for (PendingCase item : pendingCases) {
                futures.add(executor.submit(
                        () -> executeCase(item.caseDef(), item.variant(), executionKey)));
            }
            List<CompletedCase> completed = new ArrayList<>(pendingCases.size());
            for (int i = 0; i < pendingCases.size(); i++) {
                PendingCase item = pendingCases.get(i);
                completed.add(new CompletedCase(
                        item.variant().key(),
                        item.caseDef().id(),
                        futures.get(i).get()));
            }
            return List.copyOf(completed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Evaluation run interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Evaluation case execution failed", e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private CaseOutcome executeCase(
            EvaluationSuiteDefinition.CaseDef caseDef,
            EvaluationSuiteDefinition.VariantDef variant,
            RagApiKey executionKey) {
        for (EvaluationSuiteDefinition.Identity identity : caseDef.relevant()) {
            if (!caseExecutor.identityExists(identity.collectionKey(), identity.externalId())) {
                return new CaseOutcome("SKIPPED", List.of(), Map.of(), 0, null, "MISSING_FIXTURE");
            }
        }
        try {
            var scope = scopeResolver.resolve(
                    CollectionScopeMode.SELECTED_COLLECTIONS,
                    null,
                    caseDef.collectionKeys(),
                    null,
                    null,
                    executionKey);
            EvaluationCaseExecutor.Executed executed = caseExecutor.search(
                    caseDef.query(), scope, variant.config(), variant.filters());
            List<Long> retrievedRanks = new ArrayList<>();
            List<Long> relevantRanks = new ArrayList<>();
            Map<String, Long> relevantIndex = new LinkedHashMap<>();
            long next = 1;
            for (EvaluationSuiteDefinition.Identity identity : caseDef.relevant()) {
                relevantIndex.put(identity.collectionKey() + "\0" + identity.externalId(), next);
                relevantRanks.add(next);
                next++;
            }
            for (EvaluationSuiteDefinition.Identity identity : executed.identities()) {
                Long mapped = relevantIndex.get(identity.collectionKey() + "\0" + identity.externalId());
                retrievedRanks.add(mapped == null ? next++ : mapped);
            }
            var metrics = metricsService.calculateMetrics(
                    retrievedRanks, relevantRanks, Math.max(1, variant.config().getMaxResults()));
            Map<String, Object> metricMap = new LinkedHashMap<>();
            metricMap.put("hitRate", metrics.getHitRate());
            metricMap.put("mrr", metrics.getMrr());
            metricMap.put("ndcg", metrics.getNdcg());
            metricMap.put("precisionAtK", metrics.getPrecisionAtK());
            metricMap.put("recallAtK", metrics.getRecallAtK());
            boolean belowMinimum =
                    (caseDef.minHitRate() != null && metrics.getHitRate() < caseDef.minHitRate())
                            || (caseDef.minMrr() != null && metrics.getMrr() < caseDef.minMrr());
            return new CaseOutcome(
                    belowMinimum ? "FAILED" : "PASSED",
                    executed.identities(),
                    metricMap,
                    (int) executed.latencyMs(),
                    executed.traceId(),
                    belowMinimum ? "BELOW_MINIMUM" : null);
        } catch (SecurityException e) {
            return new CaseOutcome("FAILED", List.of(), Map.of(), 0, null, "AUTHORIZATION_CHANGED");
        } catch (RuntimeException e) {
            return new CaseOutcome("FAILED", List.of(), Map.of(), 0, null, "PROVIDER_OR_DATABASE");
        }
    }

    private void authorizeDefinition(
            EvaluationSuiteDefinition definition, RagApiKey executionKey) {
        for (String key : allCollectionKeys(definition)) {
            scopeResolver.resolve(
                    CollectionScopeMode.SELECTED_COLLECTIONS,
                    null,
                    List.of(key),
                    null,
                    null,
                    executionKey);
        }
    }

    private RagApiKey currentExecutionKey() {
        return resolveExecutionKey(ChatPrincipal.fromCurrentRequest().id());
    }

    /**
     * Worker 没有 HTTP 请求，不能用 currentKey()（null 会被当成 unrestricted）。
     * {@code db:{keyId}} 按当前数据库 Key 的 ACL 重新授权；缺失或停用则失败关闭。
     * {@code local:}/{@code root:}/{@code legacy:} 与 HTTP 无实体 Key 行为一致。
     */
    RagApiKey resolveExecutionKey(String ownerPrincipalId) {
        if (ownerPrincipalId != null && ownerPrincipalId.startsWith("db:")) {
            String keyId = ownerPrincipalId.substring(3).trim();
            if (keyId.isEmpty() || apiKeyRepository == null) {
                throw new SecurityException("Owner API key is no longer authorized");
            }
            RagApiKey ownerKey = apiKeyRepository.findByKeyId(keyId).orElse(null);
            if (ownerKey == null || !ownerKey.isEnabled()) {
                throw new SecurityException("Owner API key is no longer authorized");
            }
            if (ownerKey.getExpiresAt() != null
                    && ownerKey.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
                throw new SecurityException("Owner API key is no longer authorized");
            }
            return ownerKey;
        }
        return ApiKeyCollectionAccess.currentKey();
    }

    private List<String> allCollectionKeys(EvaluationSuiteDefinition definition) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (EvaluationSuiteDefinition.CaseDef caseDef : definition.cases()) {
            keys.addAll(caseDef.collectionKeys());
            caseDef.relevant().forEach(identity -> keys.add(identity.collectionKey()));
        }
        return List.copyOf(keys);
    }

    private List<String> selectVariants(
            EvaluationSuiteDefinition definition, List<String> requested) {
        List<String> available = definition.variants().stream()
                .map(EvaluationSuiteDefinition.VariantDef::key).toList();
        if (requested == null || requested.isEmpty()) {
            return available;
        }
        for (String key : requested) {
            if (!available.contains(key)) {
                throw new IllegalArgumentException("Unknown variant: " + key);
            }
        }
        return List.copyOf(requested);
    }

    private List<String> readVariantKeys(JsonNode configuration) {
        List<String> keys = new ArrayList<>();
        configuration.path("variantKeys").forEach(node -> keys.add(node.asText()));
        return keys.isEmpty() ? List.of("default") : keys;
    }

    private Set<String> variantKeys(EvaluationRunResponse run) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        run.cases().forEach(item -> keys.add(item.variantKey()));
        run.configurationSnapshot().path("variantKeys")
                .forEach(node -> keys.add(node.asText()));
        return keys;
    }

    private EvaluationSuiteRepository.SuiteRow requireSuite(String suiteKey) {
        return repository.findSuite(ChatPrincipal.fromCurrentRequest().id(), suiteKey)
                .orElseThrow(() -> new RagException(ErrorCode.NOT_FOUND, "Suite not found"));
    }

    private EvaluationSuiteRepository.SuiteRow requireSuiteById(UUID suiteId, String owner) {
        return repository.listSuites(owner).stream()
                .filter(row -> row.id().equals(suiteId))
                .findFirst()
                .orElseThrow(() -> new RagException(ErrorCode.NOT_FOUND, "Suite not found"));
    }

    private EvaluationSuiteResponse toSuite(EvaluationSuiteRepository.SuiteRow row) {
        return new EvaluationSuiteResponse(
                row.id(), row.suiteKey(), row.name(),
                row.ownerPrincipalId(), row.createdAt());
    }

    private EvaluationSuiteVersionResponse toVersion(
            EvaluationSuiteRepository.SuiteRow suite,
            EvaluationSuiteRepository.VersionRow version) {
        return new EvaluationSuiteVersionResponse(
                version.id(), suite.id(), suite.suiteKey(), version.version(),
                version.definitionSha256(), version.definition(), version.createdAt());
    }

    private EvaluationRunResponse toRun(
            EvaluationSuiteRepository.SuiteRow suite,
            EvaluationSuiteRepository.VersionRow version,
            EvaluationSuiteRepository.RunRow run,
            List<EvaluationSuiteRepository.CaseRow> cases) {
        return new EvaluationRunResponse(
                run.id(),
                suite.suiteKey(),
                version.version(),
                version.definitionSha256(),
                run.status(),
                run.embeddingProfileKey(),
                run.codeRevision(),
                run.configurationSnapshot(),
                run.aggregateMetrics(),
                cases.stream().map(this::toCase).toList(),
                run.error(),
                run.startedAt(),
                run.finishedAt());
    }

    private EvaluationCaseResultResponse toCase(EvaluationSuiteRepository.CaseRow row) {
        return new EvaluationCaseResultResponse(
                row.variantKey(), row.caseId(), row.status(),
                row.retrievedIdentities(), row.metrics(),
                row.latencyMs(), row.traceId(), row.errorCode());
    }

    private String currentRevision() {
        String revision = System.getenv("GIT_COMMIT");
        return revision == null || revision.isBlank() ? "unknown" : revision;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private double average(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private record CaseOutcome(
            String status,
            List<EvaluationSuiteDefinition.Identity> identities,
            Map<String, Object> metrics,
            int latencyMs,
            UUID traceId,
            String errorCode) {
    }

    private record PendingCase(
            EvaluationSuiteDefinition.CaseDef caseDef,
            EvaluationSuiteDefinition.VariantDef variant) {
    }

    private record CompletedCase(
            String variantKey,
            String caseId,
            CaseOutcome outcome) {
    }
}
