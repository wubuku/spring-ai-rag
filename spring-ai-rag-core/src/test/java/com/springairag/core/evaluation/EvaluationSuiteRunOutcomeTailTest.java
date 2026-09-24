package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.evaluation.EvaluationSuiteRepository.RunRow;
import com.springairag.core.exception.RagException;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.service.RetrievalEvaluationService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.retrieval.RetrievalScope;
import java.util.Map;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.EmbeddingProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评测运行结果状态长尾（Batch 612，JaCoCo 驱动）：未选中变体跳过、
 * 指标低于下限判 FAILED、身份缺失判 SKIPPED、fencing 丢租约早退、
 * createRun 变体数超上限拒绝。
 */
class EvaluationSuiteRunOutcomeTailTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now();

    private EvaluationSuiteRepository repository;
    private EvaluationSuiteDefinitionValidator validator;
    private CollectionRetrievalScopeResolver scopeResolver;
    private EvaluationCaseExecutor caseExecutor;
    private RetrievalEvaluationService metricsService;
    private EmbeddingProfileProvider profileProvider;
    private ApiKeyManagementService apiKeyManagementService;
    private RagProperties properties;
    private EvaluationSuiteService service;

    @BeforeEach
    void setUp() {
        repository = mock(EvaluationSuiteRepository.class);
        validator = mock(EvaluationSuiteDefinitionValidator.class);
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        caseExecutor = mock(EvaluationCaseExecutor.class);
        metricsService = mock(RetrievalEvaluationService.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        apiKeyManagementService = mock(ApiKeyManagementService.class);
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
        lenient().when(scopeResolver.resolve(
                any(), any(), anyList(), any(), any(), any()))
                .thenReturn(RetrievalScope.noMatches());

        service = new EvaluationSuiteService(
                repository, validator, scopeResolver,
                caseExecutor, metricsService, profileProvider,
                new ObjectMapper(), properties, apiKeyManagementService);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                1L, "profile-key", "provider", "model", "rev", 1024,
                "COSINE", "NONE", true);
    }

    private void authenticateAsDatabaseKey() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/evaluation/suites");
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

    private EvaluationSuiteDefinition definitionWithMinHitRate(
            String... variantKeys) {
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
                        2.0, null)),
                variants);
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

    private void stubExecution(RunRow run,
                               EvaluationSuiteDefinition definition) {
        var version = new EvaluationSuiteRepository.VersionRow(
                UUID.randomUUID(), UUID.randomUUID(), 1,
                new ObjectMapper().createObjectNode(), "sha-run", NOW);
        when(repository.findVersionById(run.suiteVersionId()))
                .thenReturn(Optional.of(version));
        when(validator.parse(any())).thenReturn(definition);
        lenient().when(caseExecutor.identityExists(
                eq("kb"), eq("default"), eq("d1"))).thenReturn(true);
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
        lenient().when(repository.insertCaseResult(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(1);
    }

    @Test
    void executeRunSkipsVariantsNotSelectedInTheRun() {
        RunRow run = runWithVariants("default");
        stubExecution(run, definition("default", "extra"));

        service.executeRun(run, "worker-1");

        // extra 变体未入选 → 仅 default 的用例执行。
        verify(repository, times(1)).insertCaseResult(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
        verify(repository).finishRun(
                eq(run.id()), eq("worker-1"), eq("PASSED"),
                anyString(), eq((String) null));
    }

    @Test
    void executeRunMarksFailedWhenMetricsBelowMinimum() {
        RunRow run = runWithVariants("default");
        stubExecution(run, definitionWithMinHitRate("default"));

        service.executeRun(run, "worker-1");

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(repository).finishRun(
                eq(run.id()), eq("worker-1"), status.capture(),
                anyString(), any());
        assertEquals("FAILED", status.getValue());
    }

    @Test
    void executeRunMarksSkippedWhenIdentityMissing() {
        RunRow run = runWithVariants("default");
        stubExecution(run, definition("default"));
        when(caseExecutor.identityExists(
                eq("kb"), eq("default"), eq("d1"))).thenReturn(false);

        service.executeRun(run, "worker-1");

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(repository).finishRun(
                eq(run.id()), eq("worker-1"), status.capture(),
                anyString(), eq((String) null));
        assertEquals("SKIPPED", status.getValue());
    }

    @Test
    void executeRunStopsSilentlyWhenFencingLosesInsert() {
        RunRow run = runWithVariants("default");
        stubExecution(run, definition("default"));
        // fencing 失败：insertCaseResult 返回 0 → 立即早退，不再收尾。
        when(repository.insertCaseResult(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(0);

        service.executeRun(run, "worker-1");

        verify(repository, never()).finishRun(
                any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void createRunRejectsTooManySelectedVariants() {
        authenticateAsDatabaseKey();
        properties.getEvaluation().setMaxVariantsPerRun(1);
        var suite = new EvaluationSuiteRepository.SuiteRow(
                UUID.randomUUID(), "suite-key", "Suite",
                "db:key-42", NOW);
        when(repository.findSuite("db:key-42", "suite-key"))
                .thenReturn(Optional.of(suite));
        var version = new EvaluationSuiteRepository.VersionRow(
                UUID.randomUUID(), suite.id(), 3,
                new ObjectMapper().createObjectNode(), "sha", NOW);
        when(repository.findVersion(suite.id(), 3))
                .thenReturn(Optional.of(version));
        when(validator.parse(any())).thenReturn(
                definition("default", "extra"));
        lenient().when(caseExecutor.identityExists(
                eq("kb"), eq("default"), eq("d1"))).thenReturn(true);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.createRun(new com.springairag.api.dto
                        .EvaluationRunCreateRequest(
                        "suite-key", 3, List.of("default", "extra"))));
        assertTrue(error.getMessage().contains("A run may use at most 1 variants"));
    }
}
