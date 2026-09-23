package com.springairag.core.chat;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatMemoryMessageProjector 长尾（Batch 594，JaCoCo 驱动）：
 * forPersistence 空入参与合成摘要丢弃、toolTranscript 守卫与
 * 调用数/字符预算截断、配对尺寸不一致排除、空 id 按名配对、
 * 单侧空 id 不配对。
 */
class ChatMemoryMessageProjectorTailTest {

    @Test
    void forPersistenceHandlesNullAndEmptyInputs() {
        assertEquals(List.of(), ChatMemoryMessageProjector.forPersistence(null));
        assertEquals(List.of(),
                ChatMemoryMessageProjector.forPersistence(List.of()));
    }

    @Test
    void syntheticSummaryMessagesAreDroppedFromPersistence() {
        org.springframework.ai.chat.messages.AbstractMessage synthetic =
                mock(org.springframework.ai.chat.messages.AbstractMessage.class);
        when(synthetic.getMetadata()).thenReturn(Map.of(
                ConversationSummaryService
                        .SYNTHETIC_SUMMARY_MESSAGE_METADATA_KEY,
                Boolean.TRUE));
        Message normal = new SystemMessage("system prompt");

        List<Message> projected = ChatMemoryMessageProjector.forPersistence(
                List.of(synthetic, normal));

        assertEquals(List.of(normal), projected);
    }

    @Test
    void assistantWithToolCallsDroppedButPlainAssistantKept() {
        AssistantMessage withCalls = AssistantMessage.builder()
                .content("calling")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "c1", "function", "lookup", "{}")))
                .build();
        AssistantMessage plain = new AssistantMessage("final answer");
        ToolResponseMessage response = result("c1", "lookup", "body");

        List<Message> projected = ChatMemoryMessageProjector.forPersistence(
                List.of(withCalls, response, plain));

        assertEquals(List.of(plain), projected);
    }

    @Test
    void toolTranscriptGuardsReturnEmptyProjection() {
        AssistantMessage call = call("c1", "lookup", "{}");
        ToolResponseMessage response = result("c1", "lookup", "body");

        assertEquals(List.of(), ChatMemoryMessageProjector.toolTranscript(
                null, 5, 100));
        assertEquals(List.of(), ChatMemoryMessageProjector.toolTranscript(
                List.of(), 5, 100));
        assertEquals(List.of(), ChatMemoryMessageProjector.toolTranscript(
                List.of(call, response), 0, 100));
        assertEquals(List.of(), ChatMemoryMessageProjector.toolTranscript(
                List.of(call, response), 5, 0));
    }

    @Test
    void toolTranscriptStopsAtConfiguredCallCap() {
        AssistantMessage first = call("c1", "lookup", "{}");
        AssistantMessage second = call("c2", "search", "{}");
        ToolResponseMessage firstResponse = result("c1", "lookup", "r1");
        ToolResponseMessage secondResponse = result("c2", "search", "r2");

        List<Map<String, Object>> transcript =
                ChatMemoryMessageProjector.toolTranscript(
                        List.of(first, firstResponse,
                                second, secondResponse),
                        1, 500);

        assertEquals(1, transcript.size());
        assertEquals("c1", transcript.getFirst().get("id"));
    }

    @Test
    void toolTranscriptStopsWhenCharacterBudgetExhausted() {
        AssistantMessage first = call("c1", "lookup", "{}");
        AssistantMessage second = call("c2", "search", "{}");
        ToolResponseMessage firstResponse = result("c1", "lookup", "r1");
        ToolResponseMessage secondResponse = result("c2", "search", "r2");

        // 每项至少计 1 字符，预算 2 只够第一条调用。
        List<Map<String, Object>> transcript =
                ChatMemoryMessageProjector.toolTranscript(
                        List.of(first, firstResponse,
                                second, secondResponse),
                        5, 2);

        assertEquals(1, transcript.size());
    }

    @Test
    void mismatchedCallResponseCountsAreExcluded() {
        AssistantMessage twoCalls = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall(
                                "c1", "function", "lookup", "{}"),
                        new AssistantMessage.ToolCall(
                                "c2", "function", "lookup", "{}")))
                .build();
        ToolResponseMessage single = result("c1", "lookup", "body");

        assertTrue(ChatMemoryMessageProjector.toolTranscript(
                List.of(twoCalls, single), 5, 500).isEmpty());
    }

    @Test
    void blankIdOnOneSideOnlyPreventsPairing() {
        AssistantMessage call = call(null, "lookup", "{}");
        ToolResponseMessage response = result("c1", "lookup", "body");

        assertTrue(ChatMemoryMessageProjector.toolTranscript(
                List.of(call, response), 5, 500).isEmpty());
    }

    @Test
    void nullIdsPairByNameAndNullArgumentsAreBounded() {
        AssistantMessage call = call(null, "lookup", null);
        ToolResponseMessage response = result(null, "lookup", "body");

        List<Map<String, Object>> transcript =
                ChatMemoryMessageProjector.toolTranscript(
                        List.of(call, response), 5, 100);

        assertEquals(1, transcript.size());
        assertEquals("", transcript.getFirst().get("id"));
    }

    private AssistantMessage call(
            String id, String name, String arguments) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        id, "function", name, arguments)))
                .build();
    }

    private ToolResponseMessage result(
            String id, String name, String body) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        id, name, body)))
                .build();
    }
}
