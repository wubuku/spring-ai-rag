package com.springairag.core.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAiCompatibleAdapter Unit Tests
 */
class OpenAiCompatibleAdapterTest {

    private final OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter();

    @Test
    void supportsMultipleSystemMessages_returnsTrue() {
        assertTrue(adapter.supportsMultipleSystemMessages());
    }

    @Test
    void requiresSystemMessageFirst_returnsFalse() {
        assertFalse(adapter.requiresSystemMessageFirst());
    }

    @Test
    void normalizeMessages_multipleSystemMessages_unchanged() {
        List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                new ApiCompatibilityAdapter.ChatMessage("system", "你是助手A"),
                new ApiCompatibilityAdapter.ChatMessage("user", "你好"),
                new ApiCompatibilityAdapter.ChatMessage("system", "你也是助手B"),
                new ApiCompatibilityAdapter.ChatMessage("assistant", "嗨")
        );

        List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

        // OpenAI 兼容：支持多个 system 消息，不合并
        assertEquals(4, result.size());
        assertEquals("system", result.get(0).role());
        assertEquals("你是助手A", result.get(0).content());
        assertEquals("user", result.get(1).role());
        assertEquals("system", result.get(2).role());
        assertEquals("你也是助手B", result.get(2).content());
        assertEquals("assistant", result.get(3).role());
    }

    @Test
    void normalizeMessages_noSystemMessages_unchanged() {
        List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                new ApiCompatibilityAdapter.ChatMessage("user", "你好"),
                new ApiCompatibilityAdapter.ChatMessage("assistant", "嗨")
        );

        List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

        assertEquals(2, result.size());
        assertEquals(messages, result);
    }

    @Test
    void normalizeMessages_singleSystemMessage_unchanged() {
        List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                new ApiCompatibilityAdapter.ChatMessage("system", "你是助手"),
                new ApiCompatibilityAdapter.ChatMessage("user", "你好")
        );

        List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

        assertEquals(2, result.size());
        assertEquals(messages, result);
    }
}
