package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.EvaluationRunCreateRequest;
import com.springairag.api.dto.EvaluationSuiteCreateRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.evaluation.EvaluationSuiteRepository.RunRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.SuiteRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.VersionRow;
import com.springairag.core.exception.RagException;
import com.springairag.core.service.RetrievalEvaluationService;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.filter.ApiKeyAuthFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 套件/版本/运行编排：开关门禁、重复冲突、配额上限与槽位分配。 */
class EvaluationSuiteServiceOrchestrationTest {

    private EvaluationSuiteRepository repository;
    private EvaluationSuiteDefinitionValidator validator;
    private EvaluationCaseExecutor caseExecutor;
    private RagProperties ragProperties;
    private EvaluationSuiteService service;
    private EmbeddingProfileProvider profileProvider;

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-08T00:00:00Z");

    @BeforeEach
    void setUp() {
        repository = mock(EvaluationSuiteRepository.class);
        EvaluationSuiteDefinitionValidator validator =
                mock(EvaluationSuiteDefinitionValidator.class);
        CollectionRetrievalScopeResolver scopeResolver =
                mock(CollectionRetrievalScopeResolver.class);
        EvaluationCaseExecutor caseExecutor =
                mock(EvaluationCaseExecutor.class);
        RetrievalEvaluationService metricsService =
                mock(RetrievalEvaluationService.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(profile());

        ragProperties = new RagProperties();
        ragProperties.getEvaluation().setManagedSuitesEnabled(true);

        // owner 为 db:key-42，resolveExecutionKey 需要 api key 管理服务。
        com.springairag.core.service.ApiKeyManagementService apiKeyManagementService =
                mock(com.springairag.core.service.ApiKeyManagementService.class);
        when(apiKeyManagementService.findActivePrincipal("key-42"))
                .thenReturn(new AuthenticatedApiPrincipal(
                        "rag_p_owner", "rag_k_owner_v1", 1, "DATABASE_API_KEY",
                        ApiKeyRole.NORMAL, null, NOW.toLocalDateTime().plusYears(1),
                        1L, null, List.of("RAG_READ")));

        service = new EvaluationSuiteService(
                repository, validator, scopeResolver, caseExecutor,
                metricsService, profileProvider, new ObjectMapper(),
                ragProperties, apiKeyManagementService);
        this.validator = validator;
        this.caseExecutor = caseExecutor;
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                1L, "profile-key", "provider", "model", "rev", 1024,
                "COSINE", "PROVIDER_DEFAULT", true);
    }

    private void authenticateAsDatabaseKey() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/evaluation/suites");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                "key-42");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    private SuiteRow suiteRow() {
        return new SuiteRow(
                UUID.randomUUID(), "suite-key", "Suite Name",
                "db:key-42", NOW);
    }

    private VersionRow versionRow(UUID suiteId, JsonNodeHolder definition) {
        return new VersionRow(
                UUID.randomUUID(), suiteId, 3, definition.node(),
                "sha-abc", NOW);
    }

    /** JsonNode 持有器，便于构造 VersionRow。 */
    record JsonNodeHolder(com.fasterxml.jackson.databind.JsonNode node) {
    }

    @Test
    void requireEnabledThrowsWhenManagedSuitesAreDisabled() {
        ragProperties.getEvaluation().setManagedSuitesEnabled(false);

        RagException error = assertThrows(RagException.class,
                service::requireEnabled);
        assertEquals(ErrorCode.EVALUATION_SUITES_DISABLED,
                error.getErrorCodeEnum());
    }

    @Test
    void createSuiteTrimsInputsAndMapsTheOwnerFromTheRequest() {
        authenticateAsDatabaseKey();
        when(repository.insertSuite("suite-key", "Suite Name", "db:key-42"))
                .thenReturn(new SuiteRow(UUID.randomUUID(), "suite-key",
                        "Suite Name", "db:key-42", NOW));

        var response = service.createSuite(new EvaluationSuiteCreateRequest(
                "  suite-key  ", "  Suite Name  "));

        assertEquals("suite-key", response.suiteKey());
        assertEquals("db:key-42", response.ownerPrincipalId());
    }

    @Test
    void createSuiteTranslatesDuplicateKeysIntoDuplicateResource() {
        authenticateAsDatabaseKey();
        when(repository.insertSuite(anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("dup"));

        RagException error = assertThrows(RagException.class,
                () -> service.createSuite(new EvaluationSuiteCreateRequest(
                        "suite-key", "Suite Name")));
        assertEquals(ErrorCode.DUPLICATE_RESOURCE, error.getErrorCodeEnum());
    }

    @Test
    void listSuitesMapsEveryRowForTheCurrentOwner() {
        authenticateAsDatabaseKey();
        when(repository.listSuites("db:key-42")).thenReturn(List.of(
                new SuiteRow(UUID.randomUUID(), "a", "A", "db:key-42", NOW),
                new SuiteRow(UUID.randomUUID(), "b", "B", "db:key-42", NOW)));

        assertEquals(2, service.listSuites().size());
    }

    @Test
    void getSuiteThrowsNotFoundWhenTheSuiteIsMissing() {
        authenticateAsDatabaseKey();
        when(repository.findSuite("db:key-42", "missing"))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.getSuite("missing"));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void createRunThrowsNotFoundWhenTheSuiteVersionIsMissing() {
        authenticateAsDatabaseKey();
        SuiteRow suite = suiteRow();
        when(repository.findSuite("db:key-42", "suite-key"))
                .thenReturn(Optional.of(suite));
        when(repository.findVersion(suite.id(), 3)).thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.createRun(new EvaluationRunCreateRequest(
                        "suite-key", 3, List.of())));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void createRunThrowsConcurrentLimitWhenEverySlotIsTaken() {
        authenticateAsDatabaseKey();
        SuiteRow suite = suiteRow();
        when(repository.findSuite("db:key-42", "suite-key"))
                .thenReturn(Optional.of(suite));
        VersionRow version = new VersionRow(
                UUID.randomUUID(), suite.id(), 3,
                new ObjectMapper().createObjectNode(), "sha", NOW);
        when(repository.findVersion(suite.id(), 3))
                .thenReturn(Optional.of(version));
        when(validator.parse(any()))
                .thenReturn(new EvaluationSuiteDefinition(
                        "{}", "sha", List.of(), List.of()));
        when(repository.tryInsertRun(
                any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt()))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.createRun(new EvaluationRunCreateRequest(
                        "suite-key", 3, List.of())));
        assertEquals(ErrorCode.CONCURRENT_EVALUATION_LIMIT,
                error.getErrorCodeEnum());
    }
}
