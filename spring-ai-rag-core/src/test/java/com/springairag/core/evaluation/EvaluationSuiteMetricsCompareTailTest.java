package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.verify;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.evaluation.EvaluationSuiteRepository.RunRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.SuiteRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.VersionRow;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.RetrievalEvaluationService;
import com.springairag.core.retrieval.RetrievalScope;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评测指标阈值与对比守卫长尾（Batch 624，JaCoCo 驱动）：
 * definitionSha 差异拒绝对比、minMrr 下限触发 FAILED、SKIPPED
 * 运行的空聚合投影。
 */
class EvaluationSuiteMetricsCompareTailTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now();
    private static final String OWNER = "db:key-42";

    private EvaluationSuiteRepository repository;
    private EvaluationSuiteDefinitionValidator validator;
    private CollectionRetrievalScopeResolver scopeResolver;
    private EvaluationCaseExecutor caseExecutor;
    private RetrievalEvaluationService metricsService;
    private RagProperties properties;
    private EvaluationSuiteService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<SuiteRow> suites = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(EvaluationSuiteRepository.class);
        validator = mock(EvaluationSuiteDefinitionValidator.class);
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        caseExecutor = mock(EvaluationCaseExecutor.class);
        metricsService = mock(RetrievalEvaluationService.class);

        properties = new RagProperties();
        properties.getEvaluation().setManagedSuitesEnabled(true);

        var profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                1L, "profile-key", "provider", "model", "rev", 1024,
                "COSINE", "PROVIDER_DEFAULT", true));
        var apiKeyManagementService = mock(ApiKeyManagementService.class);
        when(apiKeyManagementService.findActivePrincipal("key-42"))
                .thenReturn(new AuthenticatedApiPrincipal(
                        OWNER, "rag_k_owner_v1", 1, "DATABASE_API_KEY",
                        ApiKeyRole.NORMAL, null, NOW.toLocalDateTime().plusYears(1),
                        1L, null, List.of("RAG_READ")));

        service = new EvaluationSuiteService(
                repository, validator, scopeResolver, caseExecutor,
                metricsService, profileProvider, objectMapper,
                properties, apiKeyManagementService);

        authenticateAsDatabaseKey();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void authenticateAsDatabaseKey() {
        var request = new MockHttpServletRequest("GET", "/evaluation/runs");
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_PRINCIPAL_TYPE,
                com.springairag.core.filter.ApiKeyAuthFilter
                        .PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_KEY_ATTRIBUTE,
                "key-42");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    private EvaluationSuiteDefinition definition() {
        return definition("default");
    }

    private EvaluationSuiteDefinition definition(String... variantKeys) {
        List<EvaluationSuiteDefinition.VariantDef> variants =
                java.util.Arrays.stream(variantKeys)
                        .map(key -> new EvaluationSuiteDefinition.VariantDef(
                                key, config(), null))
                        .toList();
        return new EvaluationSuiteDefinition(
                "{}",
                "sha-abc",
                List.of(new EvaluationSuiteDefinition.CaseDef(
                        "case-1", "query", List.of("kb"),
                        List.of(new EvaluationSuiteDefinition.Identity(
                                "kb", "d1")),
                        null, null)),
                variants);
    }

    private EvaluationSuiteDefinition definitionWithMinMrr(Double minMrr) {
        return new EvaluationSuiteDefinition(
                "{}",
                "sha-abc",
                List.of(new EvaluationSuiteDefinition.CaseDef(
                        "case-1", "query", List.of("kb"),
                        List.of(new EvaluationSuiteDefinition.Identity(
                                "kb", "d1")),
                        null, minMrr)),
                List.of(new EvaluationSuiteDefinition.VariantDef(
                        "default", config(), null)));
    }

    private RetrievalConfig config() {
        RetrievalConfig config = new RetrievalConfig();
        config.setMaxResults(10);
        return config;
    }

    private RunRow runWithVariants(String... keys) {
        var snapshot = objectMapper.createObjectNode();
        snapshot.putObject("collectionSnapshot").put("kb", 3L);
        var variantKeys = snapshot.putArray("variantKeys");
        for (String key : keys) {
            variantKeys.add(key);
        }
        return new RunRow(
                UUID.randomUUID(), UUID.randomUUID(), OWNER,
                "RUNNING", snapshot, "rev-1", "profile-key",
                objectMapper.createObjectNode(), null, NOW, null, NOW);
    }

    private void stubExecution(RunRow run,
                               EvaluationSuiteDefinition definition) {
        var version = new VersionRow(
                UUID.randomUUID(), UUID.randomUUID(), 1,
                objectMapper.createObjectNode(), "sha-run", NOW);
        when(repository.findVersionById(run.suiteVersionId()))
                .thenReturn(Optional.of(version));
        when(validator.parse(any())).thenReturn(definition);
        lenient().when(caseExecutor.identityExists(
                anyString(), anyString(), anyString())).thenReturn(true);
        lenient().when(scopeResolver.resolve(
                any(), any(), anyList(), any(), any(), any()))
                .thenReturn(RetrievalScope.noMatches());
        lenient().when(caseExecutor.search(
                anyString(), any(), any(), any()))
                .thenReturn(new EvaluationCaseExecutor.Executed(
                        List.of(new EvaluationSuiteDefinition.Identity(
                                "kb", "d1")),
                        UUID.randomUUID(), 5L));
        var metrics = new RetrievalEvaluationService.EvaluationMetrics();
        metrics.setHitRate(1.0);
        metrics.setMrr(1.0);
        lenient().when(caseExecutor.collectionSnapshot(anyList()))
                .thenReturn(Map.of("kb", 3L));
        lenient().when(metricsService.calculateMetrics(
                anyList(), anyList(), anyInt())).thenReturn(metrics);
        lenient().when(repository.insertCaseResult(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(1);
    }

    @Test
    void executeRunMarksFailedWhenMrrBelowMinimum() {
        RunRow run = runWithVariants("default");
        stubExecution(run, definitionWithMinMrr(2.0));

        service.executeRun(run, "worker-1");

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorCode = ArgumentCaptor.forClass(String.class);
        verify(repository).finishRun(
                eq(run.id()), eq("worker-1"), status.capture(),
                org.mockito.ArgumentMatchers.anyString(), errorCode.capture());
        System.out.println("DBG_MRR status=" + status.getValue()
                + " errorCode=" + errorCode.getValue());
        assertEquals("FAILED", status.getValue());
    }

    @Test
    void skippedRunProjectsZeroAggregates() {
        // 身份缺失 → SKIPPED：聚合列表为空 → 平均值投影 0.0。
        RunRow run = runWithVariants("default");
        stubExecution(run, definition("default"));
        when(caseExecutor.identityExists(
                anyString(), anyString(), anyString())).thenReturn(false);

        service.executeRun(run, "worker-1");

        ArgumentCaptor<String> aggregate =
                ArgumentCaptor.forClass(String.class);
        verify(repository).finishRun(
                eq(run.id()), eq("worker-1"), anyString(),
                aggregate.capture(), any());
        assertTrue(aggregate.getValue().contains("\"avgHitRate\":0.0"));
        assertTrue(aggregate.getValue().contains("\"caseCount\":1"));
    }

    @Test
    void compareRejectsRunsWhenDefinitionShaDiffers() {
        UUID left = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"));
        UUID right = stubRun(
                "suite-key", 3, "sha-xyz", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.compare(left, right));
        assertTrue(error.getMessage().contains("same suite version"));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode
    configurationSnapshot(String variantKey, String collectionPath) {
        var snapshot = objectMapper.createObjectNode();
        snapshot.putArray("variantKeys").add(variantKey);
        snapshot.putObject("collectionSnapshot")
                .put("path", collectionPath);
        return snapshot;
    }

    private UUID stubRun(
            String suiteKey,
            int version,
            String definitionSha,
            String embeddingProfileKey,
            String codeRevision,
            com.fasterxml.jackson.databind.node.ObjectNode snapshot) {
        UUID runId = UUID.randomUUID();
        SuiteRow suite = new SuiteRow(
                UUID.randomUUID(), suiteKey, "Suite", OWNER, NOW);
        VersionRow versionRow = new VersionRow(
                UUID.randomUUID(), suite.id(), version,
                objectMapper.createObjectNode(), definitionSha, NOW);
        RunRow run = new RunRow(
                runId, versionRow.id(), OWNER, "PASSED",
                snapshot, codeRevision, embeddingProfileKey,
                objectMapper.createObjectNode().put("avgHitRate", 0.5),
                null, NOW, NOW, NOW);
        when(repository.findRun(runId, OWNER)).thenReturn(Optional.of(run));
        when(repository.findVersionById(versionRow.id()))
                .thenReturn(Optional.of(versionRow));
        suites.add(suite);
        when(repository.listSuites(OWNER)).thenReturn(suites);
        when(repository.listCaseResults(runId)).thenReturn(List.of());
        org.mockito.Mockito.<EvaluationSuiteDefinition>when(
                validator.parse(any())).thenReturn(definition());
        return runId;
    }
}
