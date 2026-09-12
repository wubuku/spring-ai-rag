package com.springairag.core.config;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * attachDiagnostics 会话附加与降级（Batch 344）：诊断关闭返回原
 * 命令、诊断开启 createSession 并 attachScope+withTraceSession、
 * attachScope 抛错降级返回原命令、命令自带过滤器透传。
 */
class RagChatServiceAttachDiagnosticsTest {

    private ChatClient.Builder chatClientBuilder;
    private ChatClient chatClient;
    private ChatExecutionService executionService;
    private ChatCommandMapper commandMapper;
    private RetrievalDiagnosticsService diagnosticsService;
    private RagChatService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ChatClient chatClient = mock(ChatClient.class);
        var builder = mock(ChatClient.Builder.class);
        when(builder.defaultAdvisors(anyList())).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        chatClientBuilder = builder;
        executionService = mock(ChatExecutionService.class);
        commandMapper = mock(ChatCommandMapper.class);
        diagnosticsService = mock(RetrievalDiagnosticsService.class);

        service = new RagChatService(
                chatClientBuilder,
                mock(com.springairag.core.config.ChatModelRouter.class),
                mock(QueryRewriteAdvisor.class),
                mock(HybridSearchAdvisor.class),
                mock(RerankAdvisor.class),
                mock(org.springframework.ai.chat.memory.repository.jdbc
                        .JdbcChatMemoryRepository.class),
                mock(com.springairag.core.repository.RagChatHistoryRepository.class),
                mock(com.springairag.core.extension.DomainExtensionRegistry.class),
                mock(com.springairag.core.extension.PromptCustomizerChain.class),
                new com.springairag.core.config.RagProperties(),
                null,
                null,
                null,
                null);
        service.configureModeAwareExecution(executionService, commandMapper);
        service.configureDiagnostics(diagnosticsService);

        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(chatClientBuilder.clone()).thenReturn(builder);
    }

    private ChatCommand baseCommand(RetrievalFilters filters) {
        return new ChatCommand(
                "message", "session-1", ChatPrincipal.local(),
                null, ChatMode.KNOWLEDGE, MemoryMode.STATELESS,
                null, null, null,
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, false, 0.5, 0.5),
                null, null, null, null, filters, null);
    }

    private ChatExecutionResult executionResult() {
        return new ChatExecutionResult(
                "答案", "session-1", null, null, null,
                ChatMode.KNOWLEDGE, List.of(), null, null, null, null);
    }

    private void stubMapperAndExecution(ChatCommand mapped) {
        when(commandMapper.map(any(ChatRequest.class), any(),
                any(ChatPrincipal.class))).thenReturn(mapped);
        when(executionService.execute(any(ChatCommand.class)))
                .thenReturn(new ChatExecutionResult(
                        "答案", "session-1", null, null, null,
                        ChatMode.KNOWLEDGE, List.of(), null, null,
                        null, null));
    }

    private ChatRequest request() {
        return new ChatRequest("问题", "session-1");
    }

    private MockHttpServletRequest httpRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/rag/chat/ask");
        return request;
    }

    @Test
    void disabledDiagnosticsLeavesCommandUnchanged() {
        when(diagnosticsService.isEnabled()).thenReturn(false);
        ChatCommand mapped = baseCommand(null);
        stubMapperAndExecution(mapped);

        ChatResponse response = service.chat(request(), null, null);

        assertEquals("答案", response.getAnswer());
        // 诊断关闭：执行收到的就是 mapper 原命令实例。
        verify(executionService).execute(same(mapped));
        verify(diagnosticsService, org.mockito.Mockito.never())
                .createSession(any(), anyString(), anyString());
    }

    @Test
    void enabledDiagnosticsCreatesSessionAndAttachesToCommand() {
        when(diagnosticsService.isEnabled()).thenReturn(true);
        RetrievalTraceSession session =
                new RetrievalTraceSession(ChatPrincipal.local(),
                        "chat", "session-1");
        when(diagnosticsService.createSession(
                any(ChatPrincipal.class), anyString(), anyString()))
                .thenReturn(session);
        ChatCommand mapped = baseCommand(null);
        stubMapperAndExecution(mapped);

        ChatResponse response = service.chat(request(), null, null);

        assertEquals("答案", response.getAnswer());
        // 会话贯通执行链：命令不再是最初实例（withTraceSession 派生）。
        ArgumentCaptor<ChatCommand> executed =
                ArgumentCaptor.forClass(ChatCommand.class);
        verify(executionService).execute(executed.capture());
        assertNotNull(executed.getValue().retrievalTraceSession());
        assertSame(session, executed.getValue().retrievalTraceSession());
        // attachScope 已被调用：会话的 scope 摘要非空（真实对象可观察）。
        assertNotNull(session.scopeSummary());
    }

    @Test
    void attachScopeFailureDegradesToOriginalCommand() {
        when(diagnosticsService.isEnabled()).thenReturn(true);
        RetrievalTraceSession session =
                mock(RetrievalTraceSession.class);
        when(diagnosticsService.createSession(
                any(ChatPrincipal.class), anyString(), anyString()))
                .thenReturn(session);
        org.mockito.Mockito.doThrow(new IllegalStateException("scope boom"))
                .when(session).attachScope(any(), any());
        ChatCommand mapped = baseCommand(null);
        stubMapperAndExecution(mapped);

        ChatResponse response = service.chat(request(), null, null);

        // 附加失败降级：执行收到的仍是原命令实例，主流程照常。
        assertEquals("答案", response.getAnswer());
        verify(executionService).execute(same(mapped));
    }

    @Test
    void commandFiltersOverrideNoneFallback() {
        when(diagnosticsService.isEnabled()).thenReturn(true);
        RetrievalTraceSession session =
                new RetrievalTraceSession(ChatPrincipal.local(),
                        "chat", "session-1");
        when(diagnosticsService.createSession(
                any(ChatPrincipal.class), anyString(), anyString()))
                .thenReturn(session);
        RetrievalFilters filters = RetrievalFilters.none();
        ChatCommand mapped = baseCommand(filters);
        stubMapperAndExecution(mapped);

        service.chat(request(), null, null);

        // 命令自带过滤器透传：执行链命令携带同一过滤器实例。
        ArgumentCaptor<ChatCommand> executed =
                ArgumentCaptor.forClass(ChatCommand.class);
        verify(executionService).execute(executed.capture());
        assertSame(filters, executed.getValue().retrievalFilters());
    }

}
