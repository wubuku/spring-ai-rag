package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.RetrievalEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationKnowledgeQaTraceTest {
    private final ChatExecutionService chat = mock(ChatExecutionService.class);
    private final RetrievalDiagnosticsService diagnostics = mock(RetrievalDiagnosticsService.class);
    private final EvaluationCaseExecutor cases = mock(EvaluationCaseExecutor.class);
    private final AtomicReference<ChatCommand> captured = new AtomicReference<>();
    private EvaluationSuiteService service;

    @BeforeEach
    void setUp() {
        service = new EvaluationSuiteService(mock(EvaluationSuiteRepository.class),
                mock(EvaluationSuiteDefinitionValidator.class), mock(CollectionRetrievalScopeResolver.class),
                cases, mock(RetrievalEvaluationService.class), mock(EmbeddingProfileProvider.class),
                new ObjectMapper(), new RagProperties(), mock(ApiKeyManagementService.class), chat,
                mock(SemanticEvaluationService.class), new CitationValidator());
        service.setDiagnosticsService(diagnostics);
        var prepared = mock(ChatExecutionService.PreparedExecution.class);
        when(prepared.result()).thenReturn(new ChatExecutionResult("无法确认", "session", null, null,
                "route", ChatMode.KNOWLEDGE, List.of(), Map.of(), null, List.of(), Map.of()));
        when(chat.prepareForOperation(any(), isNull(), eq(false))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return prepared;
        });
        when(cases.lookupSources(anyList())).thenReturn(List.of());
    }

    private void enableTrace() {
        when(diagnostics.isEnabled()).thenReturn(true);
        when(diagnostics.createSession(any(), eq("CHAT"), anyString())).thenAnswer(invocation ->
                new RetrievalTraceSession(invocation.getArgument(0), "CHAT", invocation.getArgument(2)));
    }

    private Object execute() {
        var definition = new EvaluationSuiteDefinition.CaseDef("case", "资料不足？", List.of("kb"),
                List.of(), null, null, "KNOWLEDGE_QA", List.of(), List.of(), "INSUFFICIENT", null);
        var variant = new EvaluationSuiteDefinition.VariantDef("default", new RetrievalConfig(), null);
        return ReflectionTestUtils.invokeMethod(service, "executeKnowledgeQa", definition,
                variant, RetrievalScope.unscoped(), "db:qa-owner");
    }

    @Test
    void attachesOwnerScopedTraceAndReturnsRetrievalIdentifier() {
        enableTrace();
        Object result = execute();
        var trace = captured.get().retrievalTraceSession();
        assertNotNull(trace);
        assertEquals("db:qa-owner", trace.ownerPrincipalId());
        assertEquals(trace.traceId(), ReflectionTestUtils.invokeMethod(result, "traceId"));
        assertEquals("PASSED", ReflectionTestUtils.invokeMethod(result, "status"));
        verify(diagnostics).persist(trace);
    }

    @Test
    void respectsDisabledDiagnostics() {
        Object result = execute();
        assertNull(ReflectionTestUtils.invokeMethod(result, "traceId"));
        assertEquals("PASSED", ReflectionTestUtils.invokeMethod(result, "status"));
        verify(diagnostics, never()).createSession(any(), any(), any());
        verify(diagnostics, never()).persist(any());
    }

    @Test
    void creationFailureDoesNotMaskQualityResult() {
        enableTrace();
        when(diagnostics.createSession(any(), any(), any())).thenThrow(new IllegalStateException("offline"));
        Object result = execute();
        assertEquals("PASSED", ReflectionTestUtils.invokeMethod(result, "status"));
        assertNull(ReflectionTestUtils.invokeMethod(result, "traceId"));
    }

    @Test
    void persistenceFailureDoesNotMaskQualityResult() {
        enableTrace();
        doThrow(new IllegalStateException("offline")).when(diagnostics).persist(any());
        Object result = execute();
        assertEquals("PASSED", ReflectionTestUtils.invokeMethod(result, "status"));
        assertNotNull(ReflectionTestUtils.invokeMethod(result, "traceId"));
    }

    @Test
    void providerFailureStillHasRetrievalTrace() {
        enableTrace();
        when(chat.prepareForOperation(any(), isNull(), eq(false))).thenThrow(new IllegalStateException("offline"));
        Object result = execute();
        assertEquals("FAILED", ReflectionTestUtils.invokeMethod(result, "status"));
        assertNotNull(ReflectionTestUtils.invokeMethod(result, "traceId"));
        verify(diagnostics).persist(any());
    }

    @Test
    void preservesLegacyManualConstructionWithoutDiagnostics() {
        service.setDiagnosticsService(null);
        Object result = execute();
        assertEquals("PASSED", ReflectionTestUtils.invokeMethod(result, "status"));
        assertNull(ReflectionTestUtils.invokeMethod(result, "traceId"));
    }
}
