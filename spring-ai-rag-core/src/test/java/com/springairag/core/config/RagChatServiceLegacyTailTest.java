package com.springairag.core.config;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse.StepMetricRecord;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RagPipelineMetrics;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.CollectionDocumentResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.retry.support.RetryTemplate;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagChatService 遗留路径长尾（Batch 406，JaCoCo 驱动）：
 * resolveLegacyRetrievalScope 的过滤/resolver 矩阵、
 * resolveLegacyModelCandidates + candidateForLegacyModel 的候选
 * 回退映射、invokeWithRetry 的重试耗尽传播、
 * extractPipelineMetrics 的空值/映射分支。
 */
class RagChatServiceLegacyTailTest {

    private ChatClient.Builder chatClientBuilder;
    private ChatModelRouter chatModelRouter;
    private CollectionDocumentResolver collectionDocumentResolver;
    private RetryTemplate retryTemplate;

    @BeforeEach
    void setUp() {
        chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.defaultAdvisors(anyList()))
                .thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(mock(ChatClient.class));
        chatModelRouter = mock(ChatModelRouter.class);
        collectionDocumentResolver = mock(CollectionDocumentResolver.class);
        retryTemplate = mock(RetryTemplate.class);
    }

    private RagChatService createService(
            ChatModelRouter router,
            CollectionDocumentResolver resolver,
            RetryTemplate template) {
        return new RagChatService(
                chatClientBuilder,
                router,
                mock(QueryRewriteAdvisor.class),
                mock(HybridSearchAdvisor.class),
                mock(RerankAdvisor.class),
                mock(JdbcChatMemoryRepository.class),
                mock(RagChatHistoryRepository.class),
                mock(DomainExtensionRegistry.class),
                mock(PromptCustomizerChain.class),
                new RagProperties(),
                null,
                null,
                template,
                resolver,
                null);
    }

    private Object invoke(RagChatService service, String name, Class<?>[] types,
                          Object... args) throws Exception {
        Method method = RagChatService.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(service, args);
    }

    private Throwable invokeFailing(RagChatService service, String name,
                                    Class<?>[] types, Object... args) {
        try {
            invoke(service, name, types, args);
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }
    }

    // ── resolveLegacyRetrievalScope ────────────────────────────────

    @Test
    void nullRequestYieldsUnscopedScope() throws Exception {
        RagChatService service = createService(
                chatModelRouter, collectionDocumentResolver, null);

        RetrievalScope scope = (RetrievalScope) invoke(service,
                "resolveLegacyRetrievalScope",
                new Class<?>[]{ChatRequest.class}, (Object) null);

        assertEquals(RetrievalScope.CollectionFilter.NONE,
                scope.collectionFilter());
        assertTrue(scope.documentIds().isEmpty());
        assertTrue(!scope.matchNone());
    }

    @Test
    void collectionFilterResolvesThroughResolver() throws Exception {
        RagChatService service = createService(
                chatModelRouter, collectionDocumentResolver, null);
        when(collectionDocumentResolver.resolveDocumentIds(null, List.of(7L)))
                .thenReturn(List.of(1L, 2L));
        ChatRequest request = new ChatRequest();
        request.setCollectionIds(List.of(7L));

        RetrievalScope scope = (RetrievalScope) invoke(service,
                "resolveLegacyRetrievalScope",
                new Class<?>[]{ChatRequest.class}, request);

        assertEquals(List.of(1L, 2L), scope.documentIds());
        assertTrue(!scope.matchNone());
        verify(collectionDocumentResolver).resolveDocumentIds(null, List.of(7L));
    }

    @Test
    void documentFilterWithResolverPrefersResolver() throws Exception {
        RagChatService service = createService(
                chatModelRouter, collectionDocumentResolver, null);
        when(collectionDocumentResolver.resolveDocumentIds(List.of(3L), null))
                .thenReturn(List.of(9L));
        ChatRequest request = new ChatRequest();
        request.setDocumentIds(List.of(3L));

        RetrievalScope scope = (RetrievalScope) invoke(service,
                "resolveLegacyRetrievalScope",
                new Class<?>[]{ChatRequest.class}, request);

        assertEquals(List.of(9L), scope.documentIds());
        verify(collectionDocumentResolver).resolveDocumentIds(List.of(3L), null);
    }

    @Test
    void documentFilterWithoutResolverPassesThrough() throws Exception {
        RagChatService service = createService(chatModelRouter, null, null);
        ChatRequest request = new ChatRequest();
        request.setDocumentIds(List.of(3L));

        RetrievalScope scope = (RetrievalScope) invoke(service,
                "resolveLegacyRetrievalScope",
                new Class<?>[]{ChatRequest.class}, request);

        assertEquals(List.of(3L), scope.documentIds());
    }

    @Test
    void emptyResolvedFilterYieldsNoMatches() throws Exception {
        RagChatService service = createService(
                chatModelRouter, collectionDocumentResolver, null);
        when(collectionDocumentResolver.resolveDocumentIds(null, List.of(7L)))
                .thenReturn(List.of());
        ChatRequest request = new ChatRequest();
        request.setCollectionIds(List.of(7L));

        RetrievalScope scope = (RetrievalScope) invoke(service,
                "resolveLegacyRetrievalScope",
                new Class<?>[]{ChatRequest.class}, request);

        assertTrue(scope.matchNone());
    }

    @Test
    void noFilterSkipsResolverEntirely() throws Exception {
        RagChatService service = createService(
                chatModelRouter, collectionDocumentResolver, null);
        ChatRequest request = new ChatRequest();

        RetrievalScope scope = (RetrievalScope) invoke(service,
                "resolveLegacyRetrievalScope",
                new Class<?>[]{ChatRequest.class}, request);

        assertTrue(scope.documentIds().isEmpty());
        verify(collectionDocumentResolver, never()).resolveDocumentIds(any(), any());
    }

    // ── resolveLegacyModelCandidates / candidateForLegacyModel ─────

    @Test
    void nullRouterYieldsNoCandidates() throws Exception {
        RagChatService service = createService(null, null, null);

        List<?> candidates = (List<?>) invoke(service,
                "resolveLegacyModelCandidates",
                new Class<?>[]{String.class}, "any-model");

        assertTrue(candidates.isEmpty());
    }

    @Test
    void descriptorCandidatesAreReturnedDirectly() throws Exception {
        RagChatService service = createService(chatModelRouter, null, null);
        ChatModelRouter.ChatModelCandidate descriptor =
                new ChatModelRouter.ChatModelCandidate(
                        "m1", mock(ChatModel.class),
                        MultiModelProperties.ModelCapabilities.defaults());
        when(chatModelRouter.orderedCandidateDescriptors("m"))
                .thenReturn(List.of(descriptor));

        List<?> candidates = (List<?>) invoke(service,
                "resolveLegacyModelCandidates",
                new Class<?>[]{String.class}, "m");

        assertEquals(1, candidates.size());
        assertEquals("m1",
                ((ChatModelRouter.ChatModelCandidate) candidates.getFirst()).ref());
        verify(chatModelRouter, never()).orderedCandidates(any());
    }

    @Test
    void legacyModelFallbackMapsModelRefs() throws Exception {
        RagChatService service = createService(chatModelRouter, null, null);
        ChatOptions optionsA = mock(ChatOptions.class);
        when(optionsA.getModel()).thenReturn("m1");
        ChatModel modelA = mock(ChatModel.class);
        when(modelA.getDefaultOptions()).thenReturn(optionsA);
        // modelB 无 options → ref 回退 UNKNOWN。
        ChatModel modelB = mock(ChatModel.class);
        when(modelB.getDefaultOptions()).thenReturn(null);
        when(chatModelRouter.orderedCandidateDescriptors("m")).thenReturn(null);
        when(chatModelRouter.orderedCandidates("m"))
                .thenReturn(List.of(modelA, modelB));

        List<?> candidates = (List<?>) invoke(service,
                "resolveLegacyModelCandidates",
                new Class<?>[]{String.class}, "m");

        assertEquals(2, candidates.size());
        ChatModelRouter.ChatModelCandidate first =
                (ChatModelRouter.ChatModelCandidate) candidates.get(0);
        ChatModelRouter.ChatModelCandidate second =
                (ChatModelRouter.ChatModelCandidate) candidates.get(1);
        assertEquals("m1", first.ref());
        assertEquals(modelA, first.model());
        assertEquals("UNKNOWN", second.ref());
    }

    @Test
    void blankModelRefBecomesUnknown() throws Exception {
        ChatOptions options = mock(ChatOptions.class);
        when(options.getModel()).thenReturn("  ");
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(options);

        Object candidate = invoke(null, "candidateForLegacyModel",
                new Class<?>[]{ChatModel.class}, model);

        assertEquals("UNKNOWN",
                ((ChatModelRouter.ChatModelCandidate) candidate).ref());
    }

    @Test
    void emptyLegacySourcesYieldNoCandidates() throws Exception {
        RagChatService service = createService(chatModelRouter, null, null);
        when(chatModelRouter.orderedCandidateDescriptors("m"))
                .thenReturn(List.of());
        when(chatModelRouter.orderedCandidates("m")).thenReturn(List.of());

        List<?> candidates = (List<?>) invoke(service,
                "resolveLegacyModelCandidates",
                new Class<?>[]{String.class}, "m");

        assertTrue(candidates.isEmpty());
    }

    // ── invokeWithRetry ────────────────────────────────────────────

    private static final Class<?>[] SUPPLIER_SIG = {Supplier.class};

    @Test
    void directCallWithoutRetryTemplate() throws Exception {
        RagChatService service = createService(chatModelRouter, null, null);
        int[] calls = {0};
        Object expected = llmResult("ok");

        Object result = invoke(service, "invokeWithRetry", SUPPLIER_SIG,
                (Supplier<Object>) () -> {
                    calls[0]++;
                    return expected;
                });

        assertEquals(expected, result);
        assertEquals(1, calls[0]);
    }

    @Test
    void retryTemplateSuccessReturnsResult() throws Exception {
        RagChatService service = createService(chatModelRouter, null, retryTemplate);
        Object expected = llmResult("ok");
        doReturn(expected).when(retryTemplate).execute(any());

        Object result = invoke(service, "invokeWithRetry", SUPPLIER_SIG,
                (Supplier<Object>) () -> "unused");

        assertEquals(expected, result);
    }

    /** 反射构造私有 record LlmCallResult，供 mock 返回值匹配真实类型。 */
    private Object llmResult(String answer) throws Exception {
        Class<?> clazz = Class.forName(
                "com.springairag.core.config.RagChatService$LlmCallResult");
        var constructor = clazz.getDeclaredConstructor(
                String.class, List.class, List.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(answer, List.of(), List.of(), 5L);
    }

    @Test
    void exhaustedRuntimeFailureIsRethrownAsIs() {
        RagChatService service = createService(chatModelRouter, null, retryTemplate);
        doThrow(new IllegalStateException("boom"))
                .when(retryTemplate).execute(any());

        Throwable failure = invokeFailing(service, "invokeWithRetry",
                SUPPLIER_SIG, (Supplier<Object>) () -> "unused");

        assertInstanceOf(IllegalStateException.class, failure);
        assertEquals("boom", failure.getMessage());
    }

    @Test
    void exhaustedCheckedFailureIsWrappedWithCause() {
        RagChatService service = createService(chatModelRouter, null, retryTemplate);
        doThrow(new Exception("checked"))
                .when(retryTemplate).execute(any());

        Throwable failure = invokeFailing(service, "invokeWithRetry",
                SUPPLIER_SIG, (Supplier<Object>) () -> "unused");

        assertInstanceOf(RuntimeException.class, failure);
        assertEquals("checked", failure.getCause().getMessage());
    }

    // ── extractPipelineMetrics ─────────────────────────────────────

    @Test
    void missingPipelineMetricsReturnNull() throws Exception {
        RagChatService service = createService(chatModelRouter, null, null);
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.context()).thenReturn(new HashMap<>());

        Object metrics = invoke(service, "extractPipelineMetrics",
                new Class<?>[]{ChatClientResponse.class}, response);

        assertNull(metrics);
    }

    @Test
    void stepsAreMappedToMetricRecords() throws Exception {
        RagChatService service = createService(chatModelRouter, null, null);
        Map<String, Object> context = new HashMap<>();
        RagPipelineMetrics.getOrCreate(context).recordStep("Rerank", 12, 3);
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.context()).thenReturn(context);

        @SuppressWarnings("unchecked")
        List<StepMetricRecord> metrics = (List<StepMetricRecord>) invoke(
                service, "extractPipelineMetrics",
                new Class<?>[]{ChatClientResponse.class}, response);

        assertEquals(1, metrics.size());
        assertEquals("Rerank", metrics.getFirst().getStepName());
        assertEquals(12, metrics.getFirst().getDurationMs());
        assertEquals(3, metrics.getFirst().getResultCount());
    }

    @Test
    void emptyStepsReturnNull() throws Exception {
        RagChatService service = createService(chatModelRouter, null, null);
        Map<String, Object> context = new HashMap<>();
        RagPipelineMetrics.getOrCreate(context);
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.context()).thenReturn(context);

        Object metrics = invoke(service, "extractPipelineMetrics",
                new Class<?>[]{ChatClientResponse.class}, response);

        assertNull(metrics);
    }
}
