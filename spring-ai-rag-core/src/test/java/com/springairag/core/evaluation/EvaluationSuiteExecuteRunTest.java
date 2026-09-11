package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.evaluation.EvaluationSuiteRepository.RunRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.VersionRow;
import com.springairag.core.exception.RagException;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.RetrievalEvaluationService;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * executeRun 生命周期：owner 凭证失效 fail-closed、定义越权
 * fail-closed、语料快照漂移 CORPUS_CHANGED、通过路径的聚合指标。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvaluationSuiteExecuteRunTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now();

    @Mock EvaluationSuiteRepository repository;
    @Mock EvaluationSuiteDefinitionValidator validator;
    @Mock CollectionRetrievalScopeResolver scopeResolver;
    @Mock EvaluationCaseExecutor caseExecutor;
    @Mock RetrievalEvaluationService metricsService;
    @Mock EmbeddingProfileProvider profileProvider;
    @Mock ApiKeyManagementService apiKeyManagementService;

    private EvaluationSuiteService service;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getEvaluation().setManagedSuitesEnabled(true);
        lenient().when(profileProvider.getActiveProfile()).thenReturn(profile());
        lenient().when(apiKeyManagementService.findActivePrincipal("key-42"))
                .thenReturn(new AuthenticatedApiPrincipal(
                        "db:key-42", "rag_k_v1", 1, "DATABASE_API_KEY",
                        ApiKeyRole.NORMAL, null, NOW.toLocalDateTime().plusYears(1),
                        1L, null, List.of("RAG_READ")));
        lenient().when(caseExecutor.collectionSnapshot(anyList()))
                .thenReturn(Map.of("kb", 3L));

        service = new EvaluationSuiteService(
                repository, validator, scopeResolver,
                caseExecutor, metricsService, profileProvider,
                new ObjectMapper(), properties, apiKeyManagementService);
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                1L, "profile-key", "provider", "model", "rev", 1024,
                "COSINE", "NONE", true);
    }

    private EvaluationSuiteDefinition definition(String... variantKeys) {
        List<EvaluationSuiteDefinition.VariantDef> variants =
                java.util.Arrays.stream(variantKeys)
                        .map(key -> new EvaluationSuiteDefinition.VariantDef(
                                key, config(), null))
                        .toList();
        return new EvaluationSuiteDefinition(
                "{}",
                "sha-run",
                List.of(new EvaluationSuiteDefinition.CaseDef(
                        "case-1", "query", List.of("kb"),
                        List.of(new EvaluationSuiteDefinition.Identity(
                                "kb", "d1")),
                        null, null)),
                variants);
    }

    private RetrievalConfig config() {
        RetrievalConfig config = new RetrievalConfig();
        config.setMaxResults(10);
        return config;
    }

    private RunRow run(String variantKeysJson) {
        var factory = new com.fasterxml.jackson.databind.node.JsonNodeFactory(
                false);
        var snapshot = factory.objectNode();
        snapshot.putObject("collectionSnapshot").put("kb", 3L);
        snapshot.set("variantKeys",
                factory.arrayNode().add(variantKeysJson));
        return new RunRow(
                UUID.randomUUID(), UUID.randomUUID(), "db:key-42",
                "RUNNING", snapshot, "rev-1", "profile-key",
                factory.nullNode(), null, NOW, null, NOW);
    }

    private void stubVersionAndDefinition(
            RunRow run, EvaluationSuiteDefinition definition) {
        VersionRow version = new VersionRow(
                UUID.randomUUID(), UUID.randomUUID(), 1,
                new ObjectMapper().createObjectNode(), "sha-run", NOW);
        when(repository.findVersionById(run.suiteVersionId()))
                .thenReturn(Optional.of(version));
        when(validator.parse(any())).thenReturn(definition);
        when(caseExecutor.identityExists(eq("kb"), eq("default"), eq("d1")))
                .thenReturn(true);
        lenient().when(scopeResolver.resolve(
                any(), any(), anyList(), any(), any(), any()))
                .thenReturn(RetrievalScope.noMatches());
        lenient().when(caseExecutor.search(
                anyString(), any(), any(), any()))
                .thenReturn(new EvaluationCaseExecutor.Executed(
                        List.of(new EvaluationSuiteDefinition.Identity(
                                "kb", "d1")),
                        UUID.randomUUID(), 5L));
        RetrievalEvaluationService.EvaluationMetrics metrics =
                new RetrievalEvaluationService.EvaluationMetrics();
        metrics.setHitRate(1.0);
        metrics.setMrr(1.0);
        when(metricsService.calculateMetrics(
                anyList(), anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(metrics);
        // 桩 1 表示租约仍持有（0 会触发 fencing 早退）。
        when(repository.insertCaseResult(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(1);
    }

    @Test
    void executeRunFailsClosedWhenOwnerKeyNoLongerExists() {
        RunRow run = run("default");
        when(apiKeyManagementService.findActivePrincipal("key-42"))
                .thenReturn(null);

        service.executeRun(run, "worker-1");

        verify(repository).finishRun(
                run.id(), "worker-1", "FAILED", "{}", "AUTHORIZATION_CHANGED");
    }

    @Test
    void executeRunPropagatesNotFoundWhenVersionIsMissing() {
        RunRow run = run("default");
        when(repository.findVersionById(run.suiteVersionId()))
                .thenReturn(Optional.empty());

        assertThrows(RagException.class, () -> service.executeRun(run, "w"));
    }

    @Test
    void executeRunFailsClosedWhenDefinitionNoLongerAuthorized() {
        RunRow run = run("default");
        when(repository.findVersionById(run.suiteVersionId()))
                .thenReturn(Optional.of(new VersionRow(
                        UUID.randomUUID(), UUID.randomUUID(), 1,
                        new ObjectMapper().createObjectNode(), "sha-run", NOW)));
        when(validator.parse(any())).thenReturn(definition("default"));
        when(scopeResolver.resolve(
                any(), any(), anyList(), any(), any(), any()))
                .thenThrow(new SecurityException("not authorized"));

        service.executeRun(run, "worker-1");

        verify(repository).finishRun(
                run.id(), "worker-1", "FAILED", "{}", "AUTHORIZATION_CHANGED");
    }

    @Test
    void executeRunReportsCorpusChangedWhenSnapshotDrifts() {
        RunRow run = run("default");
        stubVersionAndDefinition(run, definition("default"));
        // 运行结束后语料快照发生变化（文档数量 3 → 4）。
        when(caseExecutor.collectionSnapshot(anyList()))
                .thenReturn(Map.of("kb", 4L));

        service.executeRun(run, "worker-1");

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(repository).finishRun(
                eq(run.id()), eq("worker-1"), status.capture(),
                anyString(), error.capture());
        assertEquals("CORPUS_CHANGED", status.getValue());
        assertEquals("CORPUS_CHANGED", error.getValue());
    }

    @Test
    void executeRunAggregatesHitRateAndMrrOnPassedRun() {
        RunRow run = run("default");
        stubVersionAndDefinition(run, definition("default"));

        service.executeRun(run, "worker-1");

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> aggregate = ArgumentCaptor.forClass(String.class);
        verify(repository).finishRun(
                eq(run.id()), eq("worker-1"), status.capture(),
                aggregate.capture(), eq((String) null));
        assertEquals("PASSED", status.getValue());
        assertTrue(aggregate.getValue().contains("\"avgHitRate\":1.0"));
        assertTrue(aggregate.getValue().contains("\"avgMrr\":1.0"));
        assertTrue(aggregate.getValue().contains("\"caseCount\":1"));
    }

    // ==================== Batch 289：并发执行路径 ====================

    private RunRow runWithVariants(String... keys) {
        var factory = new com.fasterxml.jackson.databind.node.JsonNodeFactory(
                false);
        var snapshot = factory.objectNode();
        snapshot.putObject("collectionSnapshot").put("kb", 3L);
        var variantKeys = factory.arrayNode();
        for (String key : keys) {
            variantKeys.add(key);
        }
        snapshot.set("variantKeys", variantKeys);
        return new RunRow(
                UUID.randomUUID(), UUID.randomUUID(), "db:key-42",
                "RUNNING", snapshot, "rev-1", "profile-key",
                factory.nullNode(), null, NOW, null, NOW);
    }

    @Test
    void concurrentExecutionRunsAllVariantsAndAggregates() {
        RunRow run = runWithVariants("default", "hybrid");
        stubVersionAndDefinition(run, definition("default", "hybrid"));
        when(caseExecutor.identityExists(
                eq("kb"), eq("hybrid"), eq("d1"))).thenReturn(true);

        service.executeRun(run, "worker-1");

        // 并发度 min(4, 2) = 2：走线程池路径，两个变体全部执行。
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> aggregate = ArgumentCaptor.forClass(String.class);
        verify(repository).finishRun(
                eq(run.id()), eq("worker-1"), status.capture(),
                aggregate.capture(), eq((String) null));
        assertEquals("PASSED", status.getValue());
        assertTrue(aggregate.getValue().contains("\"caseCount\":2"));
        verify(repository, times(2)).insertCaseResult(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void executorFailureWrapsAsIllegalStateBeforeFinishingRun() {
        // 自包含桩：宽匹配连续桩——首次 true（default 变体），
        // 第二次抛异常（hybrid 变体），异常在 executeCase 的
        // fixture 预检（try 之外）发生，经 Future 包装为 ISE。
        RunRow run = runWithVariants("default", "hybrid");
        VersionRow version = new VersionRow(
                UUID.randomUUID(), UUID.randomUUID(), 1,
                new ObjectMapper().createObjectNode(), "sha-run", NOW);
        when(repository.findVersionById(run.suiteVersionId()))
                .thenReturn(Optional.of(version));
        when(validator.parse(any())).thenReturn(definition("default", "hybrid"));
        lenient().when(scopeResolver.resolve(
                any(), any(), anyList(), any(), any(), any()))
                .thenReturn(RetrievalScope.noMatches());
        lenient().when(caseExecutor.search(
                anyString(), any(), any(), any()))
                .thenReturn(new EvaluationCaseExecutor.Executed(
                        List.of(new EvaluationSuiteDefinition.Identity(
                                "kb", "d1")),
                        UUID.randomUUID(), 5L));
        lenient().when(caseExecutor.collectionSnapshot(anyList()))
                .thenReturn(Map.of("kb", 3L));
        when(repository.insertCaseResult(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(1);
        when(caseExecutor.identityExists(
                anyString(), anyString(), anyString()))
                .thenReturn(true)
                .thenThrow(new IllegalStateException("fixture vanished"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.executeRun(run, "worker-1"));

        assertTrue(error.getMessage()
                .contains("Evaluation case execution failed"));
        verify(repository, never()).finishRun(
                any(), anyString(), any(), any(), any());
    }
}
