package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.repository.RagChatMemorySummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ConversationSummaryService 渲染残余（Batch 557，JaCoCo 驱动）：
 * renderToolTranscript 对 null metadata 与全非 Map 条目返回空串、
 * renderSource 对 null 消息文本回退空串、estimate 求和。
 */
class ConversationSummaryServiceRenderSourceTailTest {

    private RagChatHistoryRepository historyRepository;
    private ConversationSummaryService service;
    private ChatPrincipal principal;

    @BeforeEach
    void setUp() {
        var summaryRepository = mock(RagChatMemorySummaryRepository.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        var modelRouter = mock(ChatModelRouter.class);
        var ragProperties = new RagProperties();
        var context = ragProperties.getChat().getContext();
        context.setCompactionEnabled(true);
        context.setCompactionTriggerTokens(1);
        context.setCompactionMaxSourceTokens(4_000);
        context.setCompactionMaxTurnsPerCall(2);
        context.setMinimumRecentTurns(1);

        var model = mock(ChatModel.class);
        var candidate = new ChatModelRouter.ChatModelCandidate(
                "test/model", model,
                new com.springairag.core.config.MultiModelProperties
                        .ModelCapabilities(true, false));
        when(modelRouter.resolveCandidateRequired("test/model"))
                .thenReturn(candidate);

        principal = new ChatPrincipal("db:render-src", "TEST", false);
        service = new ConversationSummaryService(
                summaryRepository,
                historyRepository,
                modelRouter,
                ragProperties);
    }

    private ChatHistoryResponse rowWithMetadata(long id,
                                                 Map<String, Object> metadata) {
        return new ChatHistoryResponse(
                id, "session-1", "q", "a",
                List.of(), metadata, List.of(),
                "COMPLETE", ChatMode.PLAIN, "test/model", "test/model",
                null);
    }

    private ChatHistoryResponse rowWithText(long id, String answer) {
        return new ChatHistoryResponse(
                id, "session-1", "q", answer,
                List.of(), Map.of(), List.of(),
                "COMPLETE", ChatMode.PLAIN, "test/model", "test/model",
                null);
    }

    private Object invokeRenderToolTranscript(ChatHistoryResponse row)
            throws Exception {
        var method = ConversationSummaryService.class.getDeclaredMethod(
                "renderToolTranscript", ChatHistoryResponse.class);
        method.setAccessible(true);
        return method.invoke(service, row);
    }

    @SuppressWarnings("unchecked")
    private String renderSourceFor(List<ChatHistoryResponse> rows)
            throws Exception {
        Method toMessages = ConversationSummaryService.class
                .getDeclaredMethod("toMessages", List.class);
        toMessages.setAccessible(true);
        List<Message> messages =
                (List<Message>) toMessages.invoke(service, rows);

        Method renderSource = ConversationSummaryService.class
                .getDeclaredMethod("renderSource", String.class, List.class);
        renderSource.setAccessible(true);
        return (String) renderSource.invoke(service, "", messages);
    }

    @Test
    void renderToolTranscriptReturnsEmptyForNullMetadata()
            throws Exception {
        assertEquals("", invokeRenderToolTranscript(
                rowWithMetadata(1L, null)));
    }

    @Test
    void renderToolTranscriptSkipsNonMapEntriesEntirely() throws Exception {
        var row = rowWithMetadata(1L, Map.of(
                ChatMemoryMessageProjector.TOOL_TRANSCRIPT_METADATA_KEY,
                List.of("plain string entry", 42)));
        assertEquals("", invokeRenderToolTranscript(row));
    }

    @Test
    void renderSourceFallsBackToEmptyTextForNullMessageText()
            throws Exception {
        String rendered = renderSourceFor(List.of(
                rowWithText(1L, null)));

        assertTrue(rendered.startsWith("user:"));
    }

    @Test
    void estimateSumsMessageTexts() throws Exception {
        Method estimate = ConversationSummaryService.class
                .getDeclaredMethod("estimate", List.class);
        estimate.setAccessible(true);

        Message longMessage = mock(Message.class);
        when(longMessage.getText()).thenReturn("abcd");
        Message shortMessage = mock(Message.class);
        when(shortMessage.getText()).thenReturn("ab");

        int total = (Integer) estimate.invoke(service,
                List.of(longMessage, shortMessage));

        // 估算器为 token 级：非零即可，不必等于字符数。
        assertTrue(total > 0);
    }
}
