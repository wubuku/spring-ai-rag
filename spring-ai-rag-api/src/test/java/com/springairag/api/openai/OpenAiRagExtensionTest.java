package com.springairag.api.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenAiRagExtensionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void legacyConstructorsKeepRagFieldAbsent() throws Exception {
        OpenAiChatCompletionResponse response = new OpenAiChatCompletionResponse(
                "id", "chat.completion", 1L, "model", List.of(), null);
        OpenAiChatCompletionChunk chunk = new OpenAiChatCompletionChunk(
                "id", "chat.completion.chunk", 1L, "model", List.of());

        assertFalse(objectMapper.writeValueAsString(response).contains("\"rag\""));
        assertFalse(objectMapper.writeValueAsString(chunk).contains("\"rag\""));
    }

    @Test
    void ragExtensionSerializesOnlyAsAnAdditiveField() throws Exception {
        OpenAiRagExtension extension = new OpenAiRagExtension(
                "trace-1", null, List.of(), java.util.Map.of("version", 1));
        OpenAiChatCompletionResponse response = new OpenAiChatCompletionResponse(
                "id", "chat.completion", 1L, "model", List.of(), null,
                extension);

        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsString(response));

        assertEquals("chat.completion", json.path("object").asText());
        assertEquals("trace-1", json.path("rag").path("traceId").asText());
        assertNotNull(json.path("rag").path("sources"));
        assertEquals(1, json.path("rag").path("citationRegistry")
                .path("version").asInt());
        assertFalse(json.path("rag").has("collectionCoverage"));
    }

    @Test
    void collectionCoverageSerializesOnlyWhenPresent() throws Exception {
        OpenAiRagExtension extension = new OpenAiRagExtension(
                "trace-1", null, List.of(), Map.of(),
                Map.of("mode", "EACH_COLLECTION"));
        OpenAiChatCompletionResponse response = new OpenAiChatCompletionResponse(
                "id", "chat.completion", 1L, "model", List.of(), null,
                extension);

        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsString(response));

        assertEquals("EACH_COLLECTION", json.path("rag")
                .path("collectionCoverage").path("mode").asText());
    }
}
