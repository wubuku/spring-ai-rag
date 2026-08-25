package com.springairag.core.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMemoryMessageProjectorTest {

    @Test
    void dropsToolMessagesFromJdbcMemoryProjection() {
        AssistantMessage validCall = call(
                "call-1", "getWeather", "{\"city\":\"Shanghai\"}");
        ToolResponseMessage validResult = result(
                "call-1", "getWeather", "{\"temperature\":21}");
        AssistantMessage orphanCall = call(
                "call-2", "getWeather", "{\"city\":\"Beijing\"}");
        ToolResponseMessage orphanResult = result(
                "call-3", "getWeather", "{\"temperature\":8}");

        List<Message> projected = ChatMemoryMessageProjector.forPersistence(
                List.of(
                        new UserMessage("weather"),
                        validCall,
                        validResult,
                        orphanCall,
                        orphanResult,
                        new AssistantMessage("final answer")));

        assertEquals(2, projected.size());
        assertEquals(UserMessage.class, projected.get(0).getClass());
        assertEquals("final answer", projected.get(1).getText());
    }

    @Test
    void pairsCallsWithoutIdsByToolNameAndBoundsTranscript() {
        AssistantMessage call = call(null, "lookup", "a".repeat(200));
        ToolResponseMessage result = result(null, "lookup", "b".repeat(300));

        List<Map<String, Object>> transcript =
                ChatMemoryMessageProjector.toolTranscript(
                        List.of(call, result), 2, 80);

        assertEquals(1, transcript.size());
        String arguments = String.valueOf(
                transcript.getFirst().get("arguments"));
        String body = String.valueOf(transcript.getFirst().get("result"));
        assertTrue(arguments.length() + body.length() <= 80);
        assertEquals("", transcript.getFirst().get("id"));
    }

    @Test
    void mismatchedIdIsNotProjectedAsDurableToolContext() {
        List<Message> projected = ChatMemoryMessageProjector.forPersistence(
                List.of(
                        call("call-1", "lookup", "{}"),
                        result("call-2", "lookup", "{}")));

        assertTrue(projected.isEmpty());
    }

    private AssistantMessage call(
            String id,
            String name,
            String arguments) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        id, "function", name, arguments)))
                .build();
    }

    private ToolResponseMessage result(
            String id,
            String name,
            String body) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        id, name, body)))
                .build();
    }
}
