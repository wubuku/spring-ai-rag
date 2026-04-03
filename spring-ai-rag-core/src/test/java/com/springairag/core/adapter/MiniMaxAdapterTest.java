package com.springairag.core.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MiniMaxAdapter 单元测试
 *
 * <p>MiniMax API 不支持 role: system，所有 system 消息会转为 user 消息。
 */
class MiniMaxAdapterTest {

    private final MiniMaxAdapter adapter = new MiniMaxAdapter();

    @Test
    void supportsSystemMessage_returnsFalse() {
        assertFalse(adapter.supportsSystemMessage());
    }

    @Test
    void supportsMultipleSystemMessages_returnsFalse() {
        assertFalse(adapter.supportsMultipleSystemMessages());
    }

    @Test
    void requiresSystemMessageFirst_returnsTrue() {
        assertTrue(adapter.requiresSystemMessageFirst());
    }

    @Test
    void normalizeMessages_systemMessagesConvertedToUser() {
        List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                new ApiCompatibilityAdapter.ChatMessage("system", "你是助手A"),
                new ApiCompatibilityAdapter.ChatMessage("user", "你好")
        );

        List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

        // MiniMax 不支持 system 角色，所有 system 消息应转为 user
        assertEquals(2, result.size());
        assertEquals("user", result.get(0).role());
        assertEquals("[System] 你是助手A", result.get(0).content());
        assertEquals("user", result.get(1).role());
        assertEquals("你好", result.get(1).content());
    }

    @Test
    void normalizeMessages_multipleSystemMessages_allConvertedToUser() {
        List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                new ApiCompatibilityAdapter.ChatMessage("system", "角色A"),
                new ApiCompatibilityAdapter.ChatMessage("system", "角色B"),
                new ApiCompatibilityAdapter.ChatMessage("user", "提问")
        );

        List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

        // 所有 system 消息都转为 user
        assertEquals(3, result.size());
        assertEquals("user", result.get(0).role());
        assertEquals("[System] 角色A", result.get(0).content());
        assertEquals("user", result.get(1).role());
        assertEquals("[System] 角色B", result.get(1).content());
        assertEquals("user", result.get(2).role());
        assertEquals("提问", result.get(2).content());
    }

    @Test
    void normalizeMessages_noSystemMessages_unchanged() {
        List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                new ApiCompatibilityAdapter.ChatMessage("user", "你好"),
                new ApiCompatibilityAdapter.ChatMessage("assistant", "嗨")
        );

        List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

        assertEquals(2, result.size());
        assertEquals("user", result.get(0).role());
        assertEquals("你好", result.get(0).content());
        assertEquals("assistant", result.get(1).role());
        assertEquals("嗨", result.get(1).content());
    }

    @Test
    void normalizeMessages_assistantMessages_preserved() {
        List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                new ApiCompatibilityAdapter.ChatMessage("assistant", "回复内容"),
                new ApiCompatibilityAdapter.ChatMessage("user", "下一条")
        );

        List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

        assertEquals(2, result.size());
        assertEquals("assistant", result.get(0).role());
        assertEquals("回复内容", result.get(0).content());
    }
}
