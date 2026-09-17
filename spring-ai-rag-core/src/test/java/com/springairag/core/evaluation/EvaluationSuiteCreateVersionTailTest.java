package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.EvaluationSuiteVersionCreateRequest;
import com.springairag.api.dto.EvaluationSuiteVersionResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.RetrievalEvaluationService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.evaluation.EvaluationSuiteRepository.SuiteRow;
import com.springairag.core.evaluation.EvaluationSuiteRepository.VersionRow;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EvaluationSuiteService.createVersion 编排长尾（Batch 507，JaCoCo
 * 驱动）：套件缺失 NOT_FOUND、正常创建（canonical/哈希经 validator
 * 解析后透传 insertVersion）、唯一约束冲突转 DUPLICATE_RESOURCE，
 * 以及 authorizeDefinition 对定义内集合键的范围解析。
 */
class EvaluationSuiteCreateVersionTailTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-08T00:00:00Z");

    private EvaluationSuiteRepository repository;
    private EvaluationSuiteDefinitionValidator validator;
    private CollectionRetrievalScopeResolver scopeResolver;
    private RagProperties ragProperties;
    private EvaluationSuiteService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(EvaluationSuiteRepository.class);
        validator = mock(EvaluationSuiteDefinitionValidator.class);
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        ragProperties = new RagProperties();
        var caseExecutor = mock(EvaluationCaseExecutor.class);
        var metricsService = mock(RetrievalEvaluationService.class);
        var profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                1L, "profile-key", "provider", "model", "rev", 1024,
                "COSINE", "PROVIDER_DEFAULT", true));
        var apiKeyManagementService =
                mock(ApiKeyManagementService.class);
        when(apiKeyManagementService.findActivePrincipal("key-42"))
                .thenReturn(new AuthenticatedApiPrincipal(
                        "rag_p_owner", "rag_k_owner_v1", 1, "DATABASE_API_KEY",
                        ApiKeyRole.NORMAL, null,
                        NOW.toLocalDateTime().plusYears(1), 1L, null,
                        List.of("RAG_READ")));

        ragProperties.getEvaluation().setManagedSuitesEnabled(true);

        service = new EvaluationSuiteService(
                repository, validator, scopeResolver, caseExecutor,
                metricsService, profileProvider, new ObjectMapper(),
                ragProperties, apiKeyManagementService);

        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/evaluation/suites");
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                "key-42");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private SuiteRow suiteRow() {
        return new SuiteRow(UUID.randomUUID(), "suite-key", "Suite Name",
                "db:key-42", NOW);
    }

    private EvaluationSuiteVersionCreateRequest createRequest() {
        return new EvaluationSuiteVersionCreateRequest(
                new ObjectMapper().createObjectNode());
    }

    @Test
    void createVersionPersistsParsedDefinitionAndMapsResponse() {
        SuiteRow suite = suiteRow();
        when(repository.findSuite("db:key-42", "suite-key"))
                .thenReturn(Optional.of(suite));
        when(validator.parse(any(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(new EvaluationSuiteDefinition(
                        "{\"canonical\":true}", "sha-abc", List.of(), List.of()));
        var version = new VersionRow(UUID.randomUUID(), suite.id(), 4,
                new ObjectMapper().createObjectNode(), "sha-abc", NOW);
        when(repository.insertVersion(
                suite.id(), "{\"canonical\":true}", "sha-abc"))
                .thenReturn(version);

        EvaluationSuiteVersionResponse response = service.createVersion(
                "suite-key", createRequest());

        assertEquals(4, response.version());
        assertEquals("suite-key", response.suiteKey());
        assertEquals("sha-abc", response.definitionSha256());
        verify(repository).insertVersion(
                suite.id(), "{\"canonical\":true}", "sha-abc");
    }

    @Test
    void createVersionMissingSuiteIsNotFound() {
        when(repository.findSuite("db:key-42", "missing"))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.createVersion("missing",
                        createRequest()));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void createVersionDuplicateDefinitionBecomesDuplicateResource() {
        SuiteRow suite = suiteRow();
        when(repository.findSuite("db:key-42", "suite-key"))
                .thenReturn(Optional.of(suite));
        when(validator.parse(any(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(new EvaluationSuiteDefinition(
                        "{}", "sha-abc", List.of(), List.of()));
        when(repository.insertVersion(
                any(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("dup"));

        RagException error = assertThrows(RagException.class,
                () -> service.createVersion("suite-key",
                        createRequest()));
        assertEquals(ErrorCode.DUPLICATE_RESOURCE,
                error.getErrorCodeEnum());
    }

    @Test
    void createVersionAuthorizesDefinitionCollectionKeys() {
        SuiteRow suite = suiteRow();
        when(repository.findSuite("db:key-42", "suite-key"))
                .thenReturn(Optional.of(suite));
        // 定义含一个集合键 → authorizeDefinition 调用范围解析。
        when(validator.parse(any(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(new EvaluationSuiteDefinition(
                        "{}", "sha-1",
                        List.of(new EvaluationSuiteDefinition.CaseDef(
                                "c1", "query", List.of("kb"),
                                List.of(), null, null)),
                        List.of()));
        var version = new VersionRow(UUID.randomUUID(), suite.id(), 4,
                new ObjectMapper().createObjectNode(), "sha-1", NOW);
        when(repository.insertVersion(any(), anyString(), anyString()))
                .thenReturn(version);

        service.createVersion("suite-key", createRequest());

        verify(scopeResolver).resolve(
                any(), any(), anyList(), any(), any(), any());
    }
}
