package com.springairag.core.chat;

import com.springairag.core.rag.ProjectDocumentRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

/**
 * 顾问边界分支（同包直调 protected 方法）：缺失 collector 时工具轮
 * 记录抛 IllegalStateException、无工具调用的响应跳过预算强制、上下
 * 文缺少 AuthorizedRetrievalContext 时不强制轮数预算、流式初始化同
 * 样播种 collector。
 */
class BudgetedToolCallAdvisorEdgeTest {

    private ToolCallingManager toolCallingManager;
    private BudgetedToolCallAdvisor advisor;

    @BeforeEach
    void setUp() {
        toolCallingManager = mock(ToolCallingManager.class);
        advisor = new BudgetedToolCallAdvisor(toolCallingManager, 0);
    }

    private ChatClientRequest request(Map<String, Object> context) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(
                        List.of(new UserMessage("question")),
                        ToolCallingChatOptions.builder().build()))
                .context(context)
                .build();
    }

    private ChatClientResponse responseWith(
            AssistantMessage assistant, Map<String, Object> context) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(assistant)),
                ChatResponseMetadata.builder().build());
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(context)
                .build();
    }

    @Test
    void doInitializeLoopStreamAlsoSeedsCollector() {
        ChatClientRequest request = request(new HashMap<>());

        ChatClientRequest initialized =
                advisor.doInitializeLoopStream(request, null);

        assertInstanceOf(ToolTranscriptCollector.class,
                initialized.context().get(ToolTranscriptCollector.CONTEXT_KEY));
    }

    @Test
    void missingCollectorFailsFastOnToolRoundRecording() {
        ChatClientRequest request = request(new HashMap<>());
        ChatClientResponse response = responseWith(
                new AssistantMessage("answer"), Map.of());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> advisor.doGetNextInstructionsForToolCall(
                        request, response,
                        ToolExecutionResult.builder().build()));
        assertEquals("Tool transcript collector is missing from advisor context",
                error.getMessage());
    }

    @Test
    void budgetEnforcementSkipsWhenContextLacksAuthorizedRetrievalContext() {
        // 工具调用响应但上下文无 AuthorizedRetrievalContext：不强制轮数预算。
        AssistantMessage toolCallAssistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "search", "{}")))
                .build();
        ChatClientResponse response = responseWith(toolCallAssistant, Map.of());
        ChatClientRequest request = request(new HashMap<>());

        ChatClientResponse result = advisor.doAfterCall(
                response, mock(org.springframework.ai.chat.client.advisor.api.CallAdvisorChain.class));

        assertSame(response, result);
        assertNotNull(result);
    }

    @Test
    void budgetEnforcementSkipsWhenResponseHasNoToolCalls() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector(5, 1, 20);
        assertTrue(trace.tryBeginToolRound());
        // 耗尽的 trace 存在，但最终答案不带工具调用：不抛预算异常。
        ChatClientResponse response = responseWith(
                new AssistantMessage("final"),
                Map.of(ProjectDocumentRetriever.CONTEXT_KEY,
                        new com.springairag.core.chat.AuthorizedRetrievalContext(
                                null, null, trace, "session-edge",
                                ChatPrincipal.local())));

        ChatClientResponse result = advisor.doAfterCall(
                response, mock(org.springframework.ai.chat.client.advisor.api.CallAdvisorChain.class));

        assertEquals("final",
                result.chatResponse().getResult().getOutput().getText());
    }

}
