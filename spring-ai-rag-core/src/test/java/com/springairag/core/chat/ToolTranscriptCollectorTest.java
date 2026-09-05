package com.springairag.core.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖工具交换配对收集器：非法输入忽略、跨轮累积、有界投影与静态读取。
 */
class ToolTranscriptCollectorTest {

    private static final int UNBOUNDED = Integer.MAX_VALUE;

    @Test
    void ignoresNullResponseAndMissingChatResponse() {
        ToolTranscriptCollector collector = new ToolTranscriptCollector();

        collector.record(null, resultWithToolResponse("call-1", "searchKnowledge"));
        collector.record(responseWith(null), resultWithToolResponse("call-1", "searchKnowledge"));

        assertTrue(collector.transcript(10, UNBOUNDED).isEmpty());
    }

    @Test
    void ignoresNullResultAndNullConversationHistory() {
        ToolTranscriptCollector collector = new ToolTranscriptCollector();
        AssistantMessage assistant = toolCallAssistant("call-1", "searchKnowledge");

        collector.record(responseWith(assistant), null);
        collector.record(responseWith(assistant),
                ToolExecutionResult.builder().build());

        assertTrue(collector.transcript(10, UNBOUNDED).isEmpty());
    }

    @Test
    void ignoresAssistantWithoutToolCalls() {
        ToolTranscriptCollector collector = new ToolTranscriptCollector();
        AssistantMessage plain = AssistantMessage.builder()
                .content("plain answer")
                .build();

        collector.record(responseWith(plain), resultWithToolResponse("call-1", "searchKnowledge"));

        assertTrue(collector.transcript(10, UNBOUNDED).isEmpty());
    }

    @Test
    void ignoresHistoryWithoutToolResponseMessage() {
        ToolTranscriptCollector collector = new ToolTranscriptCollector();
        ToolExecutionResult result = ToolExecutionResult.builder()
                .conversationHistory(List.of(new UserMessage("question")))
                .build();

        collector.record(
                responseWith(toolCallAssistant("call-1", "searchKnowledge")),
                result);

        assertTrue(collector.transcript(10, UNBOUNDED).isEmpty());
    }

    @Test
    void pairsToolExchangeAndAccumulatesAcrossRounds() {
        ToolTranscriptCollector collector = new ToolTranscriptCollector();

        collector.record(
                responseWith(toolCallAssistant("call-1", "searchKnowledge")),
                resultWithToolResponse("call-1", "searchKnowledge"));
        collector.record(
                responseWith(toolCallAssistant("call-2", "fetchDocument")),
                resultWithToolResponse("call-2", "fetchDocument"));

        List<Map<String, Object>> transcript = collector.transcript(10, UNBOUNDED);
        assertEquals(2, transcript.size());
        assertEquals("call-1", transcript.get(0).get("id"));
        assertEquals("searchKnowledge", transcript.get(0).get("name"));
        assertEquals("call-2", transcript.get(1).get("id"));
        assertEquals("fetchDocument", transcript.get(1).get("name"));
        assertEquals("body", transcript.get(0).get("result"));
    }

    @Test
    void transcriptHonorsCallAndCharacterBudgets() {
        ToolTranscriptCollector collector = new ToolTranscriptCollector();
        collector.record(
                responseWith(toolCallAssistant("call-1", "searchKnowledge")),
                resultWithToolResponse("call-1", "searchKnowledge"));
        collector.record(
                responseWith(toolCallAssistant("call-2", "fetchDocument")),
                resultWithToolResponse("call-2", "fetchDocument"));

        assertEquals(1, collector.transcript(1, UNBOUNDED).size());
        assertTrue(collector.transcript(0, UNBOUNDED).isEmpty());
        assertTrue(collector.transcript(10, 0).isEmpty());
        List<Map<String, Object>> trimmed = collector.transcript(2, 20);
        assertEquals(1, trimmed.size());
        assertTrue(String.valueOf(trimmed.getFirst().get("body")).length() <= 20);
    }

    @Test
    void staticTranscriptFallsBackToEmptyWithoutCollector() {
        assertTrue(ToolTranscriptCollector.transcript(null, 10, UNBOUNDED).isEmpty());
        assertTrue(ToolTranscriptCollector.transcript(
                responseWith(toolCallAssistant("call-1", "searchKnowledge")),
                10, UNBOUNDED).isEmpty());
    }

    @Test
    void staticTranscriptReadsCollectorFromContext() {
        ToolTranscriptCollector collector = new ToolTranscriptCollector();
        collector.record(
                responseWith(toolCallAssistant("call-1", "searchKnowledge")),
                resultWithToolResponse("call-1", "searchKnowledge"));
        ChatClientResponse response = ChatClientResponse.builder()
                .chatResponse(new ChatResponse(
                        List.of(new Generation(
                                toolCallAssistant("call-1", "searchKnowledge"))),
                        ChatResponseMetadata.builder().build()))
                .context(ToolTranscriptCollector.CONTEXT_KEY, collector)
                .build();

        List<Map<String, Object>> transcript =
                ToolTranscriptCollector.transcript(response, 10, UNBOUNDED);

        assertEquals(1, transcript.size());
        assertEquals("searchKnowledge", transcript.getFirst().get("name"));
    }

    private AssistantMessage toolCallAssistant(String id, String name) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        id, "function", name, "{\"query\":\"q\"}")))
                .build();
    }

    private ChatClientResponse responseWith(AssistantMessage assistant) {
        ChatResponse chatResponse = assistant == null
                ? null
                : new ChatResponse(
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
