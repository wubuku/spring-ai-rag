package com.springairag.core.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiCompatibilityAdapter 默认方法测试
 */
class ApiCompatibilityAdapterTest {

    @Nested
    @DisplayName("normalizeMessages - 单 system 消息模式")
    class SingleSystemMessageTests {

        private final ApiCompatibilityAdapter adapter = new ApiCompatibilityAdapter() {
            @Override
            public boolean supportsMultipleSystemMessages() { return false; }

            @Override
            public boolean requiresSystemMessageFirst() { return true; }
        };

        @Test
        @DisplayName("多个 system 消息应合并为一个")
        void shouldMergeMultipleSystemMessages() {
            List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                    new ApiCompatibilityAdapter.ChatMessage("system", "你是助手"),
                    new ApiCompatibilityAdapter.ChatMessage("system", "保持简洁"),
                    new ApiCompatibilityAdapter.ChatMessage("user", "你好")
            );

            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

            assertEquals(2, result.size());
            assertEquals("system", result.get(0).role());
            assertEquals("你是助手\n\n保持简洁", result.get(0).content());
            assertEquals("user", result.get(1).role());
        }

        @Test
        @DisplayName("单个 system 消息不改变")
        void shouldKeepSingleSystemMessage() {
            List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                    new ApiCompatibilityAdapter.ChatMessage("system", "你是助手"),
                    new ApiCompatibilityAdapter.ChatMessage("user", "你好")
            );

            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

            assertEquals(2, result.size());
            assertEquals("你是助手", result.get(0).content());
        }

        @Test
        @DisplayName("无 system 消息不改变")
        void shouldPassThroughWhenNoSystem() {
            List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                    new ApiCompatibilityAdapter.ChatMessage("user", "你好"),
                    new ApiCompatibilityAdapter.ChatMessage("assistant", "你好！")
            );

            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("空列表返回空")
        void shouldReturnEmptyForEmptyInput() {
            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(List.of());
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("normalizeMessages - 多 system 消息模式")
    class MultiSystemMessageTests {

        private final ApiCompatibilityAdapter adapter = new ApiCompatibilityAdapter() {
            @Override
            public boolean supportsMultipleSystemMessages() { return true; }

            @Override
            public boolean requiresSystemMessageFirst() { return false; }
        };

        @Test
        @DisplayName("支持多 system 消息时不改变消息列表")
        void shouldNotChangeMessagesWhenMultipleSupported() {
            List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                    new ApiCompatibilityAdapter.ChatMessage("system", "你是助手"),
                    new ApiCompatibilityAdapter.ChatMessage("system", "保持简洁"),
                    new ApiCompatibilityAdapter.ChatMessage("user", "你好")
            );

            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

            assertEquals(3, result.size());
            assertEquals(messages, result);
        }
    }

    @Test
    @DisplayName("ChatMessage record 正常创建")
    void chatMessageShouldWork() {
        var msg = new ApiCompatibilityAdapter.ChatMessage("user", "hello");
        assertEquals("user", msg.role());
        assertEquals("hello", msg.content());
    }
}
