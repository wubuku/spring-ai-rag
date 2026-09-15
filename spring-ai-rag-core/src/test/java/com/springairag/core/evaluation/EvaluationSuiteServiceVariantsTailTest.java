package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.RetrievalEvaluationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EvaluationSuiteService 变体选择与执行密钥长尾（Batch 418）：
 * selectVariants 的 null/全部/未知/子集语义、resolveExecutionKey
 * 的 db:/local 前缀与失效密钥 fail-closed。
 */
class EvaluationSuiteServiceVariantsTailTest {

    private EvaluationSuiteService service;
    private ApiKeyManagementService apiKeyManagementService;

    @BeforeEach
    void setUp() {
        var repository = mock(EvaluationSuiteRepository.class);
        var validator = mock(EvaluationSuiteDefinitionValidator.class);
        var scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        var caseExecutor = mock(EvaluationCaseExecutor.class);
        var metricsService = mock(RetrievalEvaluationService.class);
        var profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                1L, "profile-key", "provider", "model", "rev", 1024,
                "COSINE", "PROVIDER_DEFAULT", true));
        apiKeyManagementService = mock(ApiKeyManagementService.class);

        RagProperties ragProperties = new RagProperties();
        ragProperties.getEvaluation().setManagedSuitesEnabled(true);

        service = new EvaluationSuiteService(
                repository, validator, scopeResolver, caseExecutor,
                metricsService, profileProvider, new ObjectMapper(),
                ragProperties, apiKeyManagementService);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private EvaluationSuiteDefinition definition(String... variantKeys) {
        List<EvaluationSuiteDefinition.VariantDef> variants =
                java.util.Arrays.stream(variantKeys)
                        .map(key -> new EvaluationSuiteDefinition.VariantDef(
                                key, null, null))
                        .toList();
        return new EvaluationSuiteDefinition(
                "{}", "digest", List.of(), variants);
    }

    private List<String> selectVariants(
            EvaluationSuiteDefinition definition,
            List<String> requested) throws Exception {
        Method method = EvaluationSuiteService.class.getDeclaredMethod(
                "selectVariants", EvaluationSuiteDefinition.class, List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(service, definition, requested);
        return result;
    }

    private ApiAccessPolicy resolveExecutionKey(String ownerPrincipalId)
            throws Exception {
        Method method = EvaluationSuiteService.class.getDeclaredMethod(
                "resolveExecutionKey", String.class);
        method.setAccessible(true);
        return (ApiAccessPolicy) method.invoke(service, ownerPrincipalId);
    }

    @Test
    void selectVariantsDefaultsToAllAvailableWhenRequestMissingOrEmpty()
            throws Exception {
        EvaluationSuiteDefinition definition =
                definition("default", "aggressive");

        assertEquals(List.of("default", "aggressive"),
                selectVariants(definition, null));
        assertEquals(List.of("default", "aggressive"),
                selectVariants(definition, List.of()));
    }

    @Test
    void selectVariantsRejectsUnknownKeysAndKeepsRequestedSubset()
            throws Exception {
        EvaluationSuiteDefinition definition = definition("default", "aggressive");

        Exception error = assertThrows(Exception.class,
                () -> selectVariants(definition, List.of("aggressive", "nope")));
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        assertEquals(IllegalArgumentException.class, cause.getClass());
        assertTrue(cause.getMessage().contains("Unknown variant: nope"));

        assertEquals(List.of("aggressive"),
                selectVariants(definition, List.of("aggressive")));
    }

    @Test
    void resolveExecutionKeyReauthorizesDatabaseOwnerEachRun() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/evaluation");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
        // db: 前缀 + 密钥已停用 → fail-closed。
        when(apiKeyManagementService.findActivePrincipal("key-42"))
                .thenReturn(null);
        assertThrowsSecurity(() -> resolveExecutionKey("db:key-42"));

        // 密钥有效 → 返回当前策略。
        AuthenticatedApiPrincipal owner = new AuthenticatedApiPrincipal(
                "rag_p_owner", "rag_k_owner_v1", 1, "DATABASE_API_KEY",
                ApiKeyRole.NORMAL, "7", LocalDateTime.now().plusYears(1),
                1L, null, List.of("RAG_READ"));
        when(apiKeyManagementService.findActivePrincipal("key-42"))
                .thenReturn(owner);
        assertEquals(owner, resolveExecutionKey("db:key-42"));

        // 空 principalId → fail-closed。
        assertThrowsSecurity(() -> resolveExecutionKey("db:  "));
    }

    private void assertThrowsSecurity(ThrowingCall call) {
        try {
            call.invoke();
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertEquals(SecurityException.class, e.getCause().getClass());
            return;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("expected SecurityException");
    }

    private interface ThrowingCall {
        Object invoke() throws Exception;
    }

    @Test
    void resolveExecutionKeyFallsBackToCurrentPolicyForNonDbOwner() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/evaluation");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
        AuthenticatedApiPrincipal caller = new AuthenticatedApiPrincipal(
                "rag_p_caller", "rag_k_caller_v1", 1, "DATABASE_API_KEY",
                ApiKeyRole.NORMAL, null, LocalDateTime.now().plusYears(1),
                1L, null, List.of("RAG_READ"));
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                caller);

        assertEquals(caller, resolveExecutionKey("local:worker-1"));
    }

}
