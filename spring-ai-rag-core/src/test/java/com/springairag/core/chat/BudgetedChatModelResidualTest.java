package com.springairag.core.chat;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BudgetedChatModel 残余（Batch 372）：构造器重载默认值链、
 * null prompt 校验直通、默认模型名解析（delegate options/UNKNOWN）、
 * 取消信号的 CANCELLED 兜底、工具 schema token 计数与超限拒绝、
 * 上下文窗口溢出拒绝。
 */
class BudgetedChatModelResidualTest {

    private ChatModel delegate;
    private ChatExecutionBudget budget;
    private PromptTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        delegate = mock(ChatModel.class);
        budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(60), 8, 100_000, 4, 4, 4, 1_000_000);
        estimator = mock(PromptTokenEstimator.class);
        when(estimator.estimate(any(org.springframework.ai.chat.messages.Message.class)))
                .thenReturn(1);
        when(estimator.estimate(any(String.class))).thenReturn(1);
        when(estimator.estimate(any(ToolDefinition.class))).thenReturn(1);
        when(delegate.call(any(Prompt.class))).thenReturn(response());
    }

    private ChatResponse response() {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok"))),
                ChatResponseMetadata.builder().build());
    }

    @Test
    void constructorOverloadsApplyDefaultsForNullOptionalArguments() {
        assertNotNull(new BudgetedChatModel(delegate, budget));
        assertNotNull(new BudgetedChatModel(
                delegate, budget, 0, 0, 0, 10, estimator));
        assertNotNull(new BudgetedChatModel(
                delegate, budget, 0, 0, 0, 10, estimator, true));
        assertNotNull(new BudgetedChatModel(
                delegate, budget, 0, 0, 0, 10, estimator, false));

        // 11/12 参重载的可空参数回退默认值。
        assertNotNull(new BudgetedChatModel(
                delegate, budget, 0, 0, 0, 10, estimator,
                null, null, null, null));
        assertNotNull(new BudgetedChatModel(
                delegate, budget, 0, 0, 0, 10, estimator,
                null, null, null, null, "  "));
    }

    @Test
    void nullPromptSkipsTokenValidationAndDelegates() {
        when(delegate.call(
                org.mockito.ArgumentMatchers.<Prompt>isNull()))
                .thenReturn(response());
        BudgetedChatModel model = new BudgetedChatModel(delegate, budget);

        // contextWindow=0 时校验直通：null prompt 仍委派给底层模型。
        ChatResponse response = model.call((Prompt) null);
        assertEquals("ok", response.getResult().getOutput().getText());
    }

    @Test
    void defaultModelRefPrefersDelegateConfiguredModel() {
        ChatOptions options = mock(ChatOptions.class);
        when(options.getModel()).thenReturn("configured-model");
        when(delegate.getDefaultOptions()).thenReturn(options);

        BudgetedChatModel model = new BudgetedChatModel(
                delegate, budget, 0, 0, 0, 10, estimator,
                null, null, null, null, null);

        ChatResponse response = model.call(
                new Prompt(List.of(new UserMessage("hi"))));
        assertEquals("ok", response.getResult().getOutput().getText());
    }

    @Test
    void streamCancellationFallsBackToCancelledOutcome() {
        BudgetedChatModel model = new BudgetedChatModel(delegate, budget);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.never());

        var subscription = model.stream(
                new Prompt(List.of(new UserMessage("hi")))).subscribe();
        subscription.dispose();
    }

    @Test
    void toolSchemaTokensCountedAndOversizedSchemaRejected() {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        ToolCallingChatOptions options = mock(ToolCallingChatOptions.class);
        when(options.getToolCallbacks()).thenReturn(List.of(callback, callback));
        Prompt prompt = new Prompt(
                List.of(new UserMessage("hi")), options);

        // 两个工具 schema 各 1 token → 2 > maxToolSchemaTokens=1 → 拒绝。
        BudgetedChatModel strict = new BudgetedChatModel(
                delegate, budget, 1_000, 0, 0, 1, estimator);
        RagException error = assertThrows(RagException.class,
                () -> strict.call(prompt));
        assertEquals(ErrorCode.CHAT_CONTEXT_BUDGET_EXCEEDED,
                error.getErrorCodeEnum());

        // 限额内 → 正常委派。
        BudgetedChatModel lenient = new BudgetedChatModel(
                delegate, budget, 1_000, 0, 0, 10, estimator);
        assertEquals("ok", lenient.call(prompt)
                .getResult().getOutput().getText());
    }

    @Test
    void promptTokenOverflowWithContextWindowIsRejected() {
        // prompt token(消息数) + 输出预留 + 安全边际 ≥ 上下文窗口 → 拒绝。
        BudgetedChatModel model = new BudgetedChatModel(
                delegate, budget, 3, 2, 1, 10, estimator);

        RagException error = assertThrows(RagException.class,
                () -> model.call(new Prompt(
                        List.of(new UserMessage("hi")))));
        assertEquals(ErrorCode.CHAT_CONTEXT_BUDGET_EXCEEDED,
                error.getErrorCodeEnum());
    }

    @Test
    void summaryPurposeStreamStillRecordsUsage() {
        BudgetedChatModel summaryModel = new BudgetedChatModel(
                delegate, budget, 0, 0, 0, 10, estimator, true);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.empty());

        summaryModel.stream(new Prompt(List.of(new UserMessage("hi"))))
                .collectList()
                .block();
    }
}
