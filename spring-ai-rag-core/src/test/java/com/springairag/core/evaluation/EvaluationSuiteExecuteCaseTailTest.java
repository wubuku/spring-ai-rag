package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.evaluation.EvaluationSuiteRepository.RunRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.VersionRow;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.RetrievalEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EvaluationSuiteService 单用例执行长尾（Batch 421）：MISSING_
 * FIXTURE 跳过、授权变更/提供方或数据库异常的分类落库、
 * minHitRate/minMrr 阈值不达标的 FAILED 判定。
 */
@ExtendWith(MockitoExtension.class)
class EvaluationSuiteExecuteCaseTailTest {

    @Mock EvaluationSuiteRepository repository;
    @Mock EvaluationSuiteDefinitionValidator validator;
    @Mock CollectionRetrievalScopeResolver scopeResolver;
    @Mock EvaluationCaseExecutor caseExecutor;
    @Mock RetrievalEvaluationService metricsService;
    @Mock EmbeddingProfileProvider profileProvider;
    @Mock ApiKeyManagementService apiKeyManagementService;

    private EvaluationSuiteService service;

    private static final LocalDateTime NOW =
            LocalDateTime.parse("2026-09-01T00:00:00");

    @BeforeEach
    void setUp() {
        lenient().when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                1L, "profile-key", "provider", "model", "rev", 1024,
                "COSINE", "PROVIDER_DEFAULT", true));
        RagProperties ragProperties = new RagProperties();
        ragProperties.getEvaluation().setManagedSuitesEnabled(true);
        lenient().when(apiKeyManagementService.findActivePrincipal("key-42"))
                .thenReturn(new AuthenticatedApiPrincipal(
                        "rag_p_owner", "rag_k_owner_v1", 1, "DATABASE_API_KEY",
                        ApiKeyRole.NORMAL, null, NOW.plusYears(1), 1L, null,
                        List.of("RAG_READ")));
        lenient().when(repository.insertCaseResult(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(1);
        service = new EvaluationSuiteService(
                repository, validator, scopeResolver, caseExecutor,
                metricsService, profileProvider, new ObjectMapper(),
                ragProperties, apiKeyManagementService);
    }

    private EvaluationSuiteDefinition definition(
            Double minHitRate, Double minMrr) {
        return new EvaluationSuiteDefinition(
                "{}",
                "sha-run",
                List.of(new EvaluationSuiteDefinition.CaseDef(
                        "case-1", "query", List.of("kb"),
                        List.of(new EvaluationSuiteDefinition.Identity(
                                "kb", "d1")),
                        minHitRate, minMrr)),
                List.of(new EvaluationSuiteDefinition.VariantDef(
                        "default", config(), null)));
    }

    private RetrievalConfig config() {
        RetrievalConfig config = new RetrievalConfig();
        config.setMaxResults(10);
        return config;
    }

    private RunRow run() {
        var factory = new com.fasterxml.jackson.databind.node.JsonNodeFactory(
                false);
        var snapshot = factory.objectNode();
        snapshot.putObject("collectionSnapshot").put("kb", 3L);
        snapshot.set("variantKeys", factory.arrayNode().add("default"));
        return new RunRow(
                UUID.randomUUID(), UUID.randomUUID(), "db:key-42",
                "RUNNING", snapshot, "rev-1", "profile-key",
                factory.nullNode(), null, OffsetDateTime.now(), null,
                OffsetDateTime.now());
    }

    private void stubHappyDefaults(RunRow run,
                                   EvaluationSuiteDefinition definition) {
        var version = new EvaluationSuiteRepository.VersionRow(
                UUID.randomUUID(), UUID.randomUUID(), 1,
                new ObjectMapper().createObjectNode(), "sha-run", OffsetDateTime.now());
        lenient().when(repository.findVersionById(run.suiteVersionId()))
                .thenReturn(Optional.of(version));
        lenient().when(validator.parse(any())).thenReturn(definition);
        lenient().when(caseExecutor.identityExists(eq("kb"), eq("default"), eq("d1")))
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
        lenient().when(metricsService.calculateMetrics(
                anyList(), anyList(), anyInt())).thenReturn(metrics);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<String> capturedStatus() {
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(repository, timeout(2000)).insertCaseResult(
                any(), any(), any(), any(), status.capture(),
                any(), any(), any(), any(), any());
        return status;
    }

    private ArgumentCaptor<String> capturedReason() {
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(repository, timeout(2000)).insertCaseResult(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), reason.capture());
        return reason;
    }

    @Test
    void missingFixtureSkipsCase() {
        RunRow run = run();
        EvaluationSuiteDefinition definition = definition(null, null);
        stubHappyDefaults(run, definition);
        when(caseExecutor.identityExists(eq("kb"), eq("default"), eq("d1")))
                .thenReturn(false);

        service.executeRun(run, "worker-1");

        assertEquals("SKIPPED", capturedStatus().getValue());
        assertEquals("MISSING_FIXTURE", capturedReason().getValue());
    }

    @Test
    void securityExceptionYieldsAuthorizationChanged() {
        RunRow run = run();
        EvaluationSuiteDefinition definition = definition(null, null);
        stubHappyDefaults(run, definition);
        // authorizeDefinition 先用单键列表调用一次（放行），
        // executeCase 的第二次调用抛 SecurityException。
        when(scopeResolver.resolve(
                any(), any(), anyList(), any(), any(), any()))
                .thenReturn(RetrievalScope.noMatches())
                .thenThrow(new SecurityException("acl changed"));

        service.executeRun(run, "worker-1");

        assertEquals("FAILED", capturedStatus().getValue());
        assertEquals("AUTHORIZATION_CHANGED", capturedReason().getValue());
    }

    @Test
    void runtimeExceptionYieldsProviderOrDatabase() {
        RunRow run = run();
        EvaluationSuiteDefinition definition = definition(null, null);
        stubHappyDefaults(run, definition);
        when(caseExecutor.search(
                anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("provider down"));

        service.executeRun(run, "worker-1");

        assertEquals("FAILED", capturedStatus().getValue());
        assertEquals("PROVIDER_OR_DATABASE", capturedReason().getValue());
    }

    @Test
    void belowMinimumHitRateOrMrrFailsCase() {
        RunRow run = run();
        EvaluationSuiteDefinition definition = definition(0.9, null);
        stubHappyDefaults(run, definition);
        RetrievalEvaluationService.EvaluationMetrics metrics =
                new RetrievalEvaluationService.EvaluationMetrics();
        metrics.setHitRate(0.5);
        metrics.setMrr(1.0);
        when(metricsService.calculateMetrics(
                anyList(), anyList(), anyInt())).thenReturn(metrics);

        service.executeRun(run, "worker-1");

        assertEquals("FAILED", capturedStatus().getValue());
        assertEquals("BELOW_MINIMUM", capturedReason().getValue());

        // minMrr 同样独立判定。
        RunRow run2 = run();
        stubHappyDefaults(run2, definition(null, 0.9));
        RetrievalEvaluationService.EvaluationMetrics mrrLow =
                new RetrievalEvaluationService.EvaluationMetrics();
        mrrLow.setHitRate(1.0);
        mrrLow.setMrr(0.2);
        when(metricsService.calculateMetrics(
                anyList(), anyList(), anyInt())).thenReturn(mrrLow);

        service.executeRun(run2, "worker-2");

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(repository, timeout(2000).times(2)).insertCaseResult(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), reason.capture());
        assertEquals("BELOW_MINIMUM", reason.getAllValues().get(1));
    }
}
