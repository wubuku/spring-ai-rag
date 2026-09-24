package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.evaluation.EvaluationSuiteRepository.RunRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.SuiteRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.VersionRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.VersionRow;
import com.springairag.core.exception.RagException;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.RetrievalEvaluationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评测运行守卫与对比长尾（Batch 619，JaCoCo 驱动）：getRun 对缺失
 * 版本/owner 套件缺失的 NOT_FOUND；compare 对同 suite 不同版本号
 * 的拒绝与同环境无漂移投影；executeRun 对未入选变体的空执行
 * （PASSED、caseCount=0）与并发用例失败（FAILED 状态）。
 */
class EvaluationSuiteRunGuardsCompareTailTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now();
    private static final String OWNER = "db:key-42";

    private EvaluationSuiteRepository repository;
    private EvaluationSuiteDefinitionValidator validator;
    private CollectionRetrievalScopeResolver scopeResolver;
    private EvaluationCaseExecutor caseExecutor;
    private RetrievalEvaluationService metricsService;
    private EmbeddingProfileProvider profileProvider;
    private ApiKeyManagementService apiKeyManagementService;
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
        var profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(profile());
        apiKeyManagementService = mock(ApiKeyManagementService.class);
        when(apiKeyManagementService.findActivePrincipal("key-42"))
                .thenReturn(new AuthenticatedApiPrincipal(
                        OWNER, "rag_k_owner_v1", 1, "DATABASE_API_KEY",
                        ApiKeyRole.NORMAL, null, NOW.toLocalDateTime().plusYears(1),
                        1L, null, List.of("RAG_READ")));

        properties = new RagProperties();
        properties.getEvaluation().setManagedSuitesEnabled(true);

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

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                1L, "profile-key", "provider", "model", "rev", 1024,
                "COSINE", "PROVIDER_DEFAULT", true);
    }

    private void authenticateAsDatabaseKey() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/evaluation/runs");
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

    private ObjectNode configurationSnapshot(
            String variantKey, String collectionPath) {
        var snapshot = objectMapper.createObjectNode();
        snapshot.putArray("variantKeys").add(variantKey);
        snapshot.putObject("collectionSnapshot")
                .put("path", collectionPath);
        return snapshot;
    }

    /** 构造 run+version+suite 并桩好 getRun 查询链（套件加入 owner 列表）。 */
    private UUID stubRun(
            String suiteKey,
            int version,
            String definitionSha,
            String embeddingProfileKey,
            String codeRevision,
            ObjectNode snapshot) {
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
        when(repository.listSuites(OWNER)).thenReturn(List.copyOf(suites));
        when(repository.listCaseResults(runId)).thenReturn(List.of());
        org.mockito.Mockito.<EvaluationSuiteDefinition>when(
                validator.parse(any())).thenReturn(definition());
        return runId;
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
        lenient().when(metricsService.calculateMetrics(
                anyList(), anyList(), anyInt())).thenReturn(metrics);
        lenient().when(repository.insertCaseResult(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(1);
    }

    @Test
    void getRunThrowsNotFoundWhenVersionIsMissing() {
        RunRow run = runWithVariants("default");
        when(repository.findRun(run.id(), OWNER))
                .thenReturn(Optional.of(run));
        when(repository.findVersionById(run.suiteVersionId()))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.getRun(run.id()));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("Suite version not found"));
    }

    @Test
    void getRunThrowsNotFoundWhenSuiteMissingFromOwnerList() {
        UUID runId = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"));
        // owner 套件列表清空 → requireSuiteById 找不到 → NOT_FOUND。
        suites.clear();
        when(repository.listSuites(OWNER)).thenReturn(List.of());

        RagException error = assertThrows(RagException.class,
                () -> service.getRun(runId));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("Suite not found"));
    }

    @Test
    void compareAcceptsIdenticalRunsFromSameSuite() {
        UUID left = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"));
        UUID right = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"));

        var response = service.compare(left, right);

        assertTrue(response.sameSuiteVersion());
        assertFalse(response.environmentDrift());
        assertTrue(response.sameEmbeddingProfile());
        assertTrue(response.sameCodeRevision());
        assertTrue(response.sameCollectionSnapshot());
    }

    @Test
    void compareRejectsSameSuiteButDifferentVersionNumbers() {
        UUID left = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"));
        UUID right = stubRun(
                "suite-key", 4, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.compare(left, right));
        assertTrue(error.getMessage().contains("same suite version"));
    }

    @Test
    void createRunUsesFirstIdleSlotAndProjectsResponse() {
        var suite = new SuiteRow(
                UUID.randomUUID(), "suite-key", "Suite", OWNER, NOW);
        when(repository.findSuite(OWNER, "suite-key"))
                .thenReturn(java.util.Optional.of(suite));
        var version = new VersionRow(
                UUID.randomUUID(), suite.id(), 3,
                objectMapper.createObjectNode(), "sha", NOW);
        when(repository.findVersion(suite.id(), 3))
                .thenReturn(Optional.of(version));
        when(validator.parse(any())).thenReturn(definition("default"));
        RunRow inserted = new RunRow(
                UUID.randomUUID(), version.id(), OWNER, "PENDING",
                configurationSnapshot("default", "/corpus"), "rev-1",
                "profile-key",
                objectMapper.createObjectNode(), null, NOW, null, NOW);
        when(repository.tryInsertRun(
                any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt()))
                .thenReturn(java.util.Optional.of(inserted));

        var response = service.createRun(new com.springairag.api.dto
                .EvaluationRunCreateRequest(
                "suite-key", 3, List.of("default")));

        assertEquals(inserted.id(), response.id());
        assertEquals("PENDING", response.status());
        assertEquals("suite-key", response.suiteKey());
    }

    @Test
    void executeRunWithNoMatchingVariantsFinishesEmptyPassed() throws Exception {
        RunRow run = runWithVariants("other");
        stubExecution(run, definition("default"));
        when(caseExecutor.collectionSnapshot(anyList()))
                .thenReturn(Map.of("kb", 3L));

        service.executeRun(run, "worker-1");

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> aggregate = ArgumentCaptor.forClass(String.class);
        verify(repository).finishRun(
                eq(run.id()), eq("worker-1"), status.capture(),
                aggregate.capture(), org.mockito.ArgumentMatchers.isNull());
        assertEquals("PASSED", status.getValue());
        assertTrue(aggregate.getValue().contains("\"caseCount\":1"),
                "aggregate=" + aggregate.getValue());
        verify(caseExecutor, never()).search(
                anyString(), any(), any(), any());
    }

    @Test
    void concurrentIdentityFailureWrapsAsIllegalState() {
        // 强制并发路径；identityExists 抛错发生在 executeCase 的 try
        // 之前 → future 异常 → ExecutionException 包装为 ISE。
        properties.getEvaluation().setRunConcurrency(4);
        RunRow run = runWithVariants("default", "hybrid");
        stubExecution(run, definition("default", "hybrid"));
        when(caseExecutor.identityExists(
                anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("identity lookup down"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.executeRun(run, "worker-1"));
        assertTrue(error.getMessage()
                .contains("Evaluation case execution failed"),
                "actual=" + error.getMessage());
    }
}
