package com.springairag.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.retrieval.RetrievalBranchStage;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RetrievalDiagnosticsService 长尾（Batch 427）：检索策略命名、
 * 预算耗尽的结果/空原因归类、仅位置分数过滤、scope 元数据中
 * 集合键的可见性收窄。
 */
class RetrievalDiagnosticsServiceTailTest {

    private RetrievalDiagnosticsService newService(
            CollectionIdentityResolver resolver) {
        return new RetrievalDiagnosticsService(
                null, new RagProperties(), new ObjectMapper(), resolver);
    }

    private Object invoke(Object target, String name, Class<?>[] types,
                          Object... args) throws Exception {
        Method method = RetrievalDiagnosticsService.class
                .getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private RetrievalBranchStage stage(boolean attempted) {
        return mock(RetrievalBranchStage.class);
    }

    private void stubStage(RetrievalBranchStage stage, boolean attempted) {
        when(stage.attempted()).thenReturn(attempted);
    }

    private RetrievalOutcome outcomeWith(boolean vectorAttempted,
                                         boolean fulltextAttempted) {
        RetrievalOutcome outcome = mock(RetrievalOutcome.class);
        RetrievalBranchStage vector = mock(RetrievalBranchStage.class);
        RetrievalBranchStage fulltext = mock(RetrievalBranchStage.class);
        stubStage(vector, vectorAttempted);
        stubStage(fulltext, fulltextAttempted);
        when(outcome.vectorStage()).thenReturn(vector);
        when(outcome.fulltextStage()).thenReturn(fulltext);
        return outcome;
    }

    @Test
    void strategyOfNamesRetrievalStages() throws Exception {
        Class<?>[] sig = {RetrievalOutcome.class};

        assertEquals("unknown", invoke(null, "strategyOf", sig,
                (Object) null));

        assertEquals("hybrid", invoke(null, "strategyOf", sig,
                outcomeWith(true, true)));
        assertEquals("fulltext", invoke(null, "strategyOf", sig,
                outcomeWith(false, true)));
        assertEquals("vector", invoke(null, "strategyOf", sig,
                outcomeWith(true, false)));
    }

    @Test
    void positionalOnlyKeepsRankKeysAndDropsTheRest() throws Exception {
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("rank_0", 0.9);
        scores.put("vectorScore", 0.8);
        scores.put("rank_1", 0.5);

        Map<String, Object> visible = (Map<String, Object>) invoke(
                newService(null), "positionalOnly",
                new Class<?>[]{Map.class}, scores);

        assertEquals(2, visible.size());
        assertTrue(visible.containsKey("rank_0"));
        assertTrue(visible.containsKey("rank_1"));

        assertEquals(Map.of(), invoke(newService(null), "positionalOnly",
                new Class<?>[]{Map.class}, (Object) null));
    }

    @Test
    void visibleMetadataFiltersScopeCollectionKeysForRestrictedCaller()
            throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/chat");
        AuthenticatedApiPrincipal caller = new AuthenticatedApiPrincipal(
                "rag_p", "rag_k", 1, "DATABASE_API_KEY",
                ApiKeyRole.NORMAL, "7", null, 1L, null);
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                caller);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));

        CollectionIdentityResolver resolver =
                mock(CollectionIdentityResolver.class);
        RagCollection collection = new RagCollection();
        collection.setId(7L);
        when(resolver.findActive(null, "kb-7"))
                .thenReturn(java.util.Optional.of(collection));
        when(resolver.findActive(null, "kb-other"))
                .thenReturn(java.util.Optional.empty());

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("collectionKeys", List.of("kb-7", "kb-other"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", scope);
        metadata.put("query", "spring");

        Map<String, Object> visible = (Map<String, Object>) invoke(
                newService(resolver), "visibleMetadata",
                new Class<?>[]{Map.class, ApiAccessPolicy.class},
                metadata, caller);

        @SuppressWarnings("unchecked")
        Map<String, Object> visibleScope =
                (Map<String, Object>) visible.get("scope");
        // 受限调用方仅可见其被授权集合的键。
        assertEquals(List.of("kb-7"), visibleScope.get("collectionKeys"));
        assertEquals("spring", visible.get("query"));

        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void resolveOutcomeAndEmptyReasonReportBudgetExhaustion() throws Exception {
        RetrievalTraceSession session = mock(RetrievalTraceSession.class);
        when(session.budgetExhausted()).thenReturn(true);
        RetrievalOutcome emptyOutcome = mock(RetrievalOutcome.class);
        when(emptyOutcome.results()).thenReturn(List.of());

        assertEquals("RETRIEVAL_BUDGET_EXHAUSTED",
                invoke(null, "resolveOutcome",
                        new Class<?>[]{RetrievalTraceSession.class,
                                RetrievalOutcome.class},
                        session, emptyOutcome));
        assertEquals("RETRIEVAL_BUDGET_EXHAUSTED",
                invoke(null, "resolveEmptyReason",
                        new Class<?>[]{RetrievalTraceSession.class,
                                RetrievalOutcome.class},
                        session, emptyOutcome));

        // 未耗尽预算 → 透传 outcome 自身的编码。
        RetrievalTraceSession healthy = mock(RetrievalTraceSession.class);
        when(healthy.budgetExhausted()).thenReturn(false);
        when(emptyOutcome.outcomeCode()).thenReturn("OK");
        when(emptyOutcome.emptyReasonCode()).thenReturn("NO_MATCH");
        assertEquals("OK", invoke(null, "resolveOutcome",
                new Class<?>[]{RetrievalTraceSession.class,
                        RetrievalOutcome.class}, healthy, emptyOutcome));
        assertEquals("NO_MATCH", invoke(null, "resolveEmptyReason",
                new Class<?>[]{RetrievalTraceSession.class,
                        RetrievalOutcome.class}, healthy, emptyOutcome));
    }
}
