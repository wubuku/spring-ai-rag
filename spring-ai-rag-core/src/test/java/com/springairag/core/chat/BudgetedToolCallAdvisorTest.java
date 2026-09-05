package com.springairag.core.chat;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.rag.ProjectDocumentRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖服务端工具轮数预算顾问：collector 注入、工具交换记录、预算耗尽拒绝。
 */
class BudgetedToolCallAdvisorTest {

    private final ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);

    private final BudgetedToolCallAdvisor advisor =
            new BudgetedToolCallAdvisor(toolCallingManager, 0);

    @Test
    void requiresToolCallingChatOptions() {
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt("question"))
                .context(Map.of())
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> advisor.adviseCall(request, callChain(textResponse("answer"))));
    }

    @Test
    void seedsCollectorAndPassesThroughFinalAnswer() {
        ChatClientResponse result = advisor.adviseCall(
                request(new HashMap<>()), callChain(textResponse("answer")));

        assertInstanceOf(ToolTranscriptCollector.class,
                result.context().get(ToolTranscriptCollector.CONTEXT_KEY));
        assertEquals("answer",
                result.chatResponse().getResult().getOutput().getText());
        assertTrue(ToolTranscriptCollector
                .transcript(result, 10, 1_000).isEmpty());
    }

    @Test
    void reusesCollectorAlreadyPresentInContext() {
        ToolTranscriptCollector existing = new ToolTranscriptCollector();
        Map<String, Object> context = new HashMap<>();
        context.put(ToolTranscriptCollector.CONTEXT_KEY, existing);

        ChatClientResponse result = advisor.adviseCall(
                request(context), callChain(textResponse("answer")));

        assertSame(existing,
                result.context().get(ToolTranscriptCollector.CONTEXT_KEY));
    }

    @Test
    void executesToolRoundAndRecordsPairedTranscript() {
        CallAdvisorChain chain = callChain(
                responseWith(toolCallAssistant("call-1", "searchKnowledge")),
                textResponse("final answer"));
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(resultWithToolResponse("call-1", "searchKnowledge"));

        ChatClientResponse result = advisor.adviseCall(
                request(new HashMap<>()), chain);

        assertEquals("final answer",
                result.chatResponse().getResult().getOutput().getText());
        verify(toolCallingManager, times(1))
                .executeToolCalls(any(Prompt.class), any(ChatResponse.class));
        List<Map<String, Object>> transcript =
                ToolTranscriptCollector.transcript(result, 10, 1_000);
        assertEquals(1, transcript.size());
        assertEquals("searchKnowledge", transcript.getFirst().get("name"));
    }

    @Test
    void throwsWhenToolRoundBudgetExhausted() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector(5, 1, 20);
        assertTrue(trace.tryBeginToolRound());
        ChatClientRequest request = request(contextWithTrace(trace));

        RagException exception = assertThrows(RagException.class,
                () -> advisor.adviseCall(request,
                        callChain(responseWith(
                                toolCallAssistant("call-1", "searchKnowledge")))));

        assertEquals(ErrorCode.RETRIEVAL_FAILED, exception.getErrorCodeEnum());
        assertEquals("Agent tool-call round budget exhausted",
                exception.getMessage());
    }

    @Test
    void doesNotConsumeBudgetForFinalAnswer() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector(5, 1, 20);
        assertTrue(trace.tryBeginToolRound());
        ChatClientRequest request = request(contextWithTrace(trace));

        ChatClientResponse result = advisor.adviseCall(
                request, callChain(textResponse("answer")));

        assertEquals("answer",
                result.chatResponse().getResult().getOutput().getText());
    }

    @Test
    void streamPassesThroughFinalAnswer() {
        StreamAdvisorChain chain = streamChain(textResponse("stream answer"));

        List<ChatClientResponse> responses = advisor
                .adviseStream(request(new HashMap<>()), chain)
                .collectList()
                .block();

        assertTrue(responses != null && !responses.isEmpty());
        assertTrue(responses.stream().allMatch(response -> response.chatResponse() != null));
    }

    @Test
    void streamRejectsToolCallWhenBudgetExhausted() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector(5, 1, 20);
        assertTrue(trace.tryBeginToolRound());
        StreamAdvisorChain chain = streamChain(
                responseWith(toolCallAssistant("call-1", "searchKnowledge")));

        assertThrows(RagException.class, () -> advisor
                .adviseStream(request(contextWithTrace(trace)), chain)
                .collectList()
                .block());
    }

    /**
     * 模拟真实链路：下游 advisor 会把请求上下文带回响应，
     * collector 注入与预算读取都依赖响应上下文。
     */
    private CallAdvisorChain callChain(ChatClientResponse... responses) {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.copy(any())).thenReturn(chain);
        java.util.Iterator<ChatClientResponse> iterator =
                List.of(responses).iterator();
        when(chain.nextCall(any())).thenAnswer(invocation -> {
            ChatClientRequest received = invocation.getArgument(0);
            return iterator.next()
                    .mutate()
                    .context(received.context())
                    .build();
        });
        return chain;
    }

    private StreamAdvisorChain streamChain(ChatClientResponse... responses) {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.copy(any())).thenReturn(chain);
        java.util.Iterator<ChatClientResponse> iterator =
                List.of(responses).iterator();
        when(chain.nextStream(any())).thenAnswer(invocation -> {
            ChatClientRequest received = invocation.getArgument(0);
            return Flux.just(iterator.next()
                    .mutate()
                    .context(received.context())
                    .build());
        });
        return chain;
    }

    private ChatClientRequest request(Map<String, Object> context) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(
                        List.of(new UserMessage("question")),
                        ToolCallingChatOptions.builder().build()))
                .context(context)
                .build();
    }

    private Map<String, Object> contextWithTrace(RetrievalTraceCollector trace) {
        Map<String, Object> context = new HashMap<>();
        context.put(ProjectDocumentRetriever.CONTEXT_KEY,
                new AuthorizedRetrievalContext(
                        null, null, trace, "session-budget", ChatPrincipal.local()));
        return context;
    }

    private AssistantMessage toolCallAssistant(String id, String name) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        id, "function", name, "{\"query\":\"q\"}")))
                .build();
    }

    private ChatClientResponse textResponse(String text) {
        return responseWith(AssistantMessage.builder().content(text).build());
    }

    private ChatClientResponse responseWith(AssistantMessage assistant) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(assistant)),
                ChatResponseMetadata.builder().build());
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(Map.of())
                .build();
    }

    private ToolExecutionResult resultWithToolResponse(String id, String name) {
        return ToolExecutionResult.builder()
                .conversationHistory(List.of(
                        new UserMessage("question"),
                        ToolResponseMessage.builder()
                                .responses(List.of(
                                        new ToolResponseMessage.ToolResponse(
                                                id, name, "body")))
                                .build()))
                .build();
    }
}
