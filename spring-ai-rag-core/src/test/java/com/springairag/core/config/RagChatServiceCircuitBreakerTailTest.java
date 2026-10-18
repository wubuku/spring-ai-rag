package com.springairag.core.config;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.exception.LlmCircuitOpenException;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.exception.LlmCircuitOpenException;
import com.springairag.core.resilience.LlmCircuitBreaker;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RerankAdvisor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagChatService 断路器模式感知链路长尾（Batch 552，JaCoCo 驱
 * 动）：断路器启用时 getCircuitBreaker 非空、chat 模式感知成功记
 * 录成功、失败记录失败并传播异常、熔断开启后后续请求被
 * LlmCircuitOpenException 拒绝。
 */
class RagChatServiceCircuitBreakerTailTest {

    private ChatExecutionService executionService;
    private ChatCommandMapper commandMapper;
    private RagChatService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var chatClient = mock(ChatClient.class);
        var builder = mock(ChatClient.Builder.class);
        when(builder.defaultAdvisors(anyList())).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);

        executionService = mock(ChatExecutionService.class);
        commandMapper = mock(ChatCommandMapper.class);

        RagProperties properties = new RagProperties();
        properties.getChat().getAgent().setEnabled(true);
        var breaker = properties.getCircuitBreaker();
        breaker.setEnabled(true);
        breaker.setFailureRateThreshold(1);
        breaker.setMinimumNumberOfCalls(1);
        breaker.setSlidingWindowSize(5);
        breaker.setWaitDurationInOpenStateSeconds(60);

        var queryRewriteAdvisor = mock(QueryRewriteAdvisor.class);
        var hybridSearchAdvisor = mock(HybridSearchAdvisor.class);
        var rerankAdvisor = mock(RerankAdvisor.class);

        service = new RagChatService(
                builder,
                mock(ChatModelRouter.class),
                queryRewriteAdvisor,
                hybridSearchAdvisor,
                rerankAdvisor,
                mock(JdbcChatMemoryRepository.class),
                mock(RagChatHistoryRepository.class),
                mock(DomainExtensionRegistry.class),
                mock(PromptCustomizerChain.class),
                properties,
                null, null, null, null);
        service.configureModeAwareExecution(executionService, commandMapper);

        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    private ChatRequest request() {
        return new ChatRequest("问题", "session-1");
    }

    private ChatCommand mappedCommand() {
        return new ChatCommand(
                "问题", "session-1", ChatPrincipal.local(),
                null, ChatMode.KNOWLEDGE, MemoryMode.STATELESS,
                null, null, RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, false, 0.5, 0.5),
                Map.of(), List.of(), List.of(), null, null, null);
    }

    private void stubSuccess() {
        when(commandMapper.map(any(ChatRequest.class), any(),
                any(ChatPrincipal.class))).thenReturn(mappedCommand());
        when(executionService.execute(any(ChatCommand.class)))
                .thenReturn(new ChatExecutionResult(
                        "答案", "session-1", null, null, null,
                        ChatMode.KNOWLEDGE, List.of(), Map.of(), "STOP",
                        List.of(), Map.of()));
    }

    private void stubFailure() {
        when(commandMapper.map(any(ChatRequest.class), any(),
                any(ChatPrincipal.class))).thenReturn(mappedCommand());
        when(executionService.execute(any(ChatCommand.class)))
                .thenThrow(new org.springframework.ai.retry.TransientAiException("model down"));
    }

    @Test
    void circuitBreakerEnabledExposesInstance() {
        assertNotNull(service.getCircuitBreaker());
        assertEquals(LlmCircuitBreaker.State.CLOSED,
                service.getCircuitBreaker().getState());
    }

    @Test
    void modeAwareSuccessRecordsBreakerSuccess() {
        stubSuccess();

        var response = service.chat(request(), null, null);

        assertEquals("答案", response.getAnswer());
        assertEquals(LlmCircuitBreaker.State.CLOSED,
                service.getCircuitBreaker().getState());
    }

    @Test
    void businessRejectionDoesNotOpenDependencyCircuit() {
        stubSuccess();
        when(executionService.execute(any(ChatCommand.class))).thenThrow(
                new com.springairag.core.exception.RagException(com.springairag.api.enums.ErrorCode.FORBIDDEN, "denied"));
        assertThrows(com.springairag.core.exception.RagException.class, () -> service.chat(request(), null, null));
        assertEquals(LlmCircuitBreaker.State.CLOSED, service.getCircuitBreaker().getState());
    }

    @Test
    void modeAwareFailurePropagatesAndRecordsFailure() {
        stubFailure();

        assertThrows(org.springframework.ai.retry.TransientAiException.class,
                () -> service.chat(request(), null, null));

        // failureRateThreshold=1 且 minCalls=1：一次失败即熔断。
        assertEquals(LlmCircuitBreaker.State.OPEN,
                service.getCircuitBreaker().getState());
    }

    @Test
    void streamingMappingRejectionReleasesHalfOpenProbe() {
        var breaker = service.getCircuitBreaker();
        org.springframework.test.util.ReflectionTestUtils.setField(breaker, "waitDurationInOpenStateMillis", 0L);
        breaker.recordFailure();
        when(commandMapper.map(any(ChatRequest.class), any(), any(ChatPrincipal.class)))
                .thenThrow(new com.springairag.core.exception.RagException(
                        com.springairag.api.enums.ErrorCode.FORBIDDEN, "denied"));
        assertThrows(com.springairag.core.exception.RagException.class,
                () -> service.chatEvents(request(), null, null));
        assertEquals(LlmCircuitBreaker.State.HALF_OPEN, breaker.getState());
        assertTrue(breaker.allowCall());
        assertFalse(breaker.allowCall());
    }

    @Test
    void streamingConstructionFailureReleasesHalfOpenProbe() {
        stubSuccess();
        var breaker = service.getCircuitBreaker();
        org.springframework.test.util.ReflectionTestUtils.setField(breaker, "waitDurationInOpenStateMillis", 0L);
        breaker.recordFailure();
        when(executionService.stream(any(ChatCommand.class)))
                .thenThrow(new IllegalStateException("invalid command"));
        assertThrows(IllegalStateException.class, () -> service.chatEvents(request(), null, null));
        assertTrue(breaker.allowCall());
        assertFalse(breaker.allowCall());
    }

    @Test
    void openCircuitRejectsSubsequentRequestsWithLlmCircuitOpen() {
        stubFailure();

        assertThrows(org.springframework.ai.retry.TransientAiException.class,
                () -> service.chat(request(), null, null));
        var rejected = assertThrows(LlmCircuitOpenException.class,
                () -> service.chat(request(), null, null));
        assertEquals(com.springairag.api.enums.ErrorCode.LLM_CIRCUIT_OPEN,
                rejected.getErrorCodeEnum());
    }
}
