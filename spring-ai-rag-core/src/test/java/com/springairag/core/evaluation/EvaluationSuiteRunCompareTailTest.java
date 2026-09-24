package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.evaluation.EvaluationSuiteRepository.RunRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.SuiteRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.VersionRow;
import com.springairag.core.exception.RagException;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.RetrievalEvaluationService;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评测运行读取与对比守卫长尾（Batch 613，JaCoCo 驱动）：getRun 对
 * 缺失版本的 NOT_FOUND、createRun 首个空闲槽位命中的成功路径、
 * compare 对同版本无漂移运行的环境一致性投影。
 */
class EvaluationSuiteRunCompareTailTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now();
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
                repository, validator, scopeResolver,
                mock(EvaluationCaseExecutor.class),
                mock(RetrievalEvaluationService.class), profileProvider,
                objectMapper, ragProperties, apiKeyManagementService);

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

    private ObjectNode configurationSnapshot(
            String variantKey, String collectionPath) {
        var snapshot = objectMapper.createObjectNode();
        snapshot.putArray("variantKeys").add(variantKey);
        snapshot.putObject("collectionSnapshot")
                .put("path", collectionPath);
        return snapshot;
    }

    /** 构造一套 run+version+suite 并桩好 getRun 查询链。 */
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

    @Test
    void getRunThrowsNotFoundWhenVersionIsMissing() {
        UUID runId = UUID.randomUUID();
        RunRow run = new RunRow(
                runId, UUID.randomUUID(), OWNER, "PASSED",
                configurationSnapshot("default", "/corpus"), "rev-1",
                "profile-key",
                objectMapper.createObjectNode(), null, NOW, NOW, NOW);
        when(repository.findRun(runId, OWNER)).thenReturn(Optional.of(run));
        when(repository.findVersionById(run.suiteVersionId()))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.getRun(runId));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("Suite version not found"));
    }

    @Test
    void compareReportsNoDriftForIdenticalEnvironments() {
        UUID left = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"));
        UUID right = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"));

        var response = service.compare(left, right);

        assertFalse(response.environmentDrift());
        assertTrue(response.sameEmbeddingProfile());
        assertTrue(response.sameCodeRevision());
        assertTrue(response.sameCollectionSnapshot());
        assertTrue(response.sameSuiteVersion());
    }

    @Test
    void compareReportsDriftWhenCodeRevisionDiffers() {
        UUID left = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"));
        UUID right = stubRun(
                "suite-key", 3, "sha-abc", "profile-key", "rev-2",
                configurationSnapshot("default", "/corpus"));

        var response = service.compare(left, right);

        assertTrue(response.environmentDrift());
        assertFalse(response.sameCodeRevision());
        assertTrue(response.sameCollectionSnapshot());
    }

    @Test
    void compareRejectsRunsOfDifferentSuites() {
        UUID left = stubRun(
                "suite-a", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"));
        UUID right = stubRun(
                "suite-b", 3, "sha-abc", "profile-key", "rev-1",
                configurationSnapshot("default", "/corpus"));

        assertThrows(IllegalArgumentException.class,
                () -> service.compare(left, right));
    }

    @Test
    void createRunUsesFirstIdleSlotAndProjectsResponse() {
        authenticateAsDatabaseKey();
        var suite = new SuiteRow(
                UUID.randomUUID(), "suite-key", "Suite", OWNER, NOW);
        when(repository.findSuite(OWNER, "suite-key"))
                .thenReturn(Optional.of(suite));
        var version = new VersionRow(
                UUID.randomUUID(), suite.id(), 3,
                objectMapper.createObjectNode(), "sha", NOW);
        when(repository.findVersion(suite.id(), 3))
                .thenReturn(Optional.of(version));
        when(validator.parse(any())).thenReturn(definition());
        RunRow inserted = new RunRow(
                UUID.randomUUID(), version.id(), OWNER, "PENDING",
                configurationSnapshot("default", "/corpus"), "rev-1",
                "profile-key",
                objectMapper.createObjectNode(), null, NOW, null, NOW);
        when(repository.tryInsertRun(
                any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt()))
                .thenReturn(Optional.of(inserted));

        var response = service.createRun(new com.springairag.api.dto
                .EvaluationRunCreateRequest(
                "suite-key", 3, List.of("default")));

        assertEquals(inserted.id(), response.id());
        assertEquals("PENDING", response.status());
        assertEquals("suite-key", response.suiteKey());
    }
}
