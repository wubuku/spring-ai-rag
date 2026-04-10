package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagChatHistory entity.
 */
class RagChatHistoryTest {

    @Test
    void defaultValues_sessionIdIsNull() {
        RagChatHistory history = new RagChatHistory();
        assertNull(history.getSessionId());
    }

    @Test
    void allFields_setAndGet() {
        LocalDateTime now = LocalDateTime.now();

        RagChatHistory history = new RagChatHistory();
        history.setId(1L);
        history.setSessionId("session-123");
        history.setUserMessage("What is RAG?");
        history.setAiResponse("RAG stands for Retrieval-Augmented Generation...");
        history.setRelatedDocumentIds("[1, 2, 3]");
        history.setMetadata(Map.of("model", "gpt-4", "temperature", 0.7));
        history.setCreatedAt(now);

        assertEquals(1L, history.getId());
        assertEquals("session-123", history.getSessionId());
        assertEquals("What is RAG?", history.getUserMessage());
        assertEquals("RAG stands for Retrieval-Augmented Generation...", history.getAiResponse());
        assertEquals("[1, 2, 3]", history.getRelatedDocumentIds());
        assertEquals("gpt-4", history.getMetadata().get("model"));
        assertEquals(0.7, history.getMetadata().get("temperature"));
        assertEquals(now, history.getCreatedAt());
    }

    @Test
    void defaultConstructor_works() {
        RagChatHistory history = new RagChatHistory();
        assertNotNull(history);
        assertNull(history.getId());
    }

    @Test
    void optionalFields_canBeNull() {
        RagChatHistory history = new RagChatHistory();
        history.setAiResponse(null);
        history.setRelatedDocumentIds(null);
        history.setMetadata(null);

        assertNull(history.getAiResponse());
        assertNull(history.getRelatedDocumentIds());
        assertNull(history.getMetadata());
    }

    @Test
    void userMessage_canBeEmpty() {
        RagChatHistory history = new RagChatHistory();
        history.setUserMessage("");
        assertEquals("", history.getUserMessage());
    }

    @Test
    void metadata_jsonMapSerialization() {
        RagChatHistory history = new RagChatHistory();
        Map<String, Object> metadata = Map.of(
                "model", "claude-3",
                "tokens_used", 1500,
                "retrieval_time_ms", 45
        );
        history.setMetadata(metadata);

        assertEquals("claude-3", history.getMetadata().get("model"));
        assertEquals(1500, history.getMetadata().get("tokens_used"));
        assertEquals(45, history.getMetadata().get("retrieval_time_ms"));
    }

    @Test
    void relatedDocumentIds_jsonArrayFormat() {
        RagChatHistory history = new RagChatHistory();
        history.setRelatedDocumentIds("[5, 10, 15]");
        assertEquals("[5, 10, 15]", history.getRelatedDocumentIds());

        history.setRelatedDocumentIds(null);
        assertNull(history.getRelatedDocumentIds());
    }

    @Test
    void createdAt_canBeSet() {
        LocalDateTime customTime = LocalDateTime.of(2024, 1, 15, 10, 30, 0);
        RagChatHistory history = new RagChatHistory();
        history.setCreatedAt(customTime);
        assertEquals(customTime, history.getCreatedAt());
    }
}
