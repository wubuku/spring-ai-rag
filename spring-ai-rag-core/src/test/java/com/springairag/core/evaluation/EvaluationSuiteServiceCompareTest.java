package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.EvaluationCompareResponse;
import com.springairag.api.dto.EvaluationRunResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.evaluation.EvaluationSuiteRepository.CaseRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.RunRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.SuiteRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.VersionRow;
import com.springairag.core.exception.RagException;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.RetrievalEvaluationService;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.service.ApiKeyManagementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行查询映射与 compare 对比语义：同套件版本约束、variant 一致性、
 * 环境漂移（embedding profile / 代码修订 / 语料快照）识别。
 */
class EvaluationSuiteServiceCompareTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-08T00:00:00Z");
    private static final String OWNER = "db:key-42";

    private EvaluationSuiteRepository repository;
    private EvaluationSuiteDefinitionValidator validator;
    private CollectionRetrievalScopeResolver scopeResolver;
    private RagProperties ragProperties;
    private EvaluationSuiteService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<SuiteRow> suites = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(EvaluationSuiteRepository.class);
        validator = mock(EvaluationSuiteDefinitionValidator.class);
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        EvaluationCaseExecutor caseExecutor = mock(EvaluationCaseExecutor.class);
        RetrievalEvaluationService metricsService =
                mock(RetrievalEvaluationService.class);
        EmbeddingProfileProvider profileProvider =
                mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(profile());

        ragProperties = new RagProperties();
        ragProperties.getEvaluation().setManagedSuitesEnabled(true);

        ApiKeyManagementService apiKeyManagementService =
                mock(ApiKeyManagementService.class);
        when(apiKeyManagementService.findActivePrincipal("key-42"))
                .thenReturn(new AuthenticatedApiPrincipal(
                        OWNER, "rag_k_owner_v1", 1, "DATABASE_API_KEY",
                        ApiKeyRole.NORMAL, null, NOW.toLocalDateTime().plusYears(1),
                        1L, null, List.of("RAG_READ")));

        service = new EvaluationSuiteService(
                repository, validator, scopeResolver, caseExecutor,
                metricsService, profileProvider, objectMapper,
                ragProperties, apiKeyManagementService);

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
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                "key-42");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    private EvaluationSuiteDefinition definition() {
        return new EvaluationSuiteDefinition(
                "{}",
                "sha-abc",
                List.of(new EvaluationSuiteDefinition.CaseDef(
                        "case-1", "query", List.of("kb"), List.of(),
                        null, null)),
                List.of(new EvaluationSuiteDefinition.VariantDef(
                        "default", null, null)));
    }

    private ObjectNode configurationSnapshot(
            String variantKey, String collectionPath) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.putArray("variantKeys").add(variantKey);
        snapshot.putObject("collectionSnapshot")
                .put("path", collectionPath);
        return snapshot;
    }

    /** 构造一套 run+version+suite+case，并桩好 getRun 的查询链。 */
    private UUID stubRun(
            String suiteKey,
            int version,
            String definitionSha,
            String embeddingProfileKey,
            String codeRevision,
            ObjectNode snapshot,
            String variantKey) {
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
        // compare 会连续查两个 run，suite 列表须累积而非覆盖。
        suites.add(suite);
        when(repository.listSuites(OWNER)).thenReturn(suites);
        when(repository.listCaseResults(runId)).thenReturn(List.of(
                new CaseRow(runId, variantKey, "case-1", "PASSED",
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode().put("hitRate", 0.5),
                        12, null, null)));
        Mockito.<EvaluationSuiteDefinition>when(validator.parse(any()))
                .thenReturn(definition());
        return runId;
    }

    @Test
    void getRunMapsSuiteVersionAndCaseResults() {
        UUID runId = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"), "default");

        EvaluationRunResponse run = service.getRun(runId);

        assertEquals("suite-key", run.suiteKey());
        assertEquals(3, run.version());
        assertEquals("sha-abc", run.definitionSha256());
        assertEquals("PASSED", run.status());
        assertEquals("profile-key", run.embeddingProfileKey());
        assertEquals(1, run.cases().size());
        assertEquals("default", run.cases().get(0).variantKey());
        assertEquals("case-1", run.cases().get(0).caseId());
    }

    @Test
    void getRunFailsWithNotFoundWhenRunIsMissing() {
        when(repository.findRun(any(UUID.class), eq(OWNER)))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.getRun(UUID.randomUUID()));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void compareRejectsRunsFromDifferentSuiteVersions() {
        UUID left = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"), "default");
        UUID right = stubRun(
                "suite-key", 4, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"), "default");

        assertThrows(IllegalArgumentException.class,
                () -> service.compare(left, right));
    }

    @Test
    void compareRejectsMismatchedVariantKeys() {
        UUID left = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"), "default");
        UUID right = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("strict", "/corpus"), "strict");

        assertThrows(IllegalArgumentException.class,
                () -> service.compare(left, right));
    }

    @Test
    void compareReportsEnvironmentDriftWhenEmbeddingProfileDiffers() {
        UUID left = stubRun(
                "suite-key", 3, "sha-abc", "profile-a", "rev-1",
                configurationSnapshot("default", "/corpus"), "default");
        UUID right = stubRun(
                "suite-key", 3, "sha-abc", "profile-b", "rev-1",
                configurationSnapshot("default", "/corpus"), "default");

        EvaluationCompareResponse response = service.compare(left, right);

        assertTrue(response.sameSuiteVersion());
        assertTrue(response.environmentDrift());
        assertFalse(response.sameEmbeddingProfile());
        assertTrue(response.sameCodeRevision());
        assertTrue(response.sameCollectionSnapshot());
    }

    @Test
    void compareReportsDriftWhenCollectionSnapshotDiffers() {
        UUID left = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus-a"), "default");
        UUID right = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus-b"), "default");

        EvaluationCompareResponse response = service.compare(left, right);

        assertTrue(response.environmentDrift());
        assertTrue(response.sameEmbeddingProfile());
        assertFalse(response.sameCollectionSnapshot());
        assertEquals(left, response.leftRunId());
        assertEquals(right, response.rightRunId());
    }

    @Test
    void compareReportsNoDriftForIdenticalEnvironments() {
        UUID left = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"), "default");
        UUID right = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"), "default");

        EvaluationCompareResponse response = service.compare(left, right);

        assertFalse(response.environmentDrift());
        assertTrue(response.sameEmbeddingProfile());
        assertTrue(response.sameCodeRevision());
        assertTrue(response.sameCollectionSnapshot());
        assertTrue((response.leftMetrics() instanceof JsonNode));
    }
}
