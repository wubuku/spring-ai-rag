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

    @Nested
    @DisplayName("supportsSystemMessage = false (system → user conversion)")
    class SystemToUserConversionTests {

        private final ApiCompatibilityAdapter adapter = new ApiCompatibilityAdapter() {
            @Override
            public boolean supportsSystemMessage() { return false; }

            @Override
            public boolean supportsMultipleSystemMessages() { return true; }

            @Override
            public boolean requiresSystemMessageFirst() { return false; }
        };

        @Test
        @DisplayName("system 消息应转换为 user 消息并添加 [System] 前缀")
        void shouldConvertSystemToUserWithPrefix() {
            List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                    new ApiCompatibilityAdapter.ChatMessage("system", "You are a helpful assistant"),
                    new ApiCompatibilityAdapter.ChatMessage("user", "Hello")
            );

            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

            assertEquals(2, result.size());
            assertEquals("user", result.get(0).role());
            assertEquals("[System] You are a helpful assistant", result.get(0).content());
            assertEquals("user", result.get(1).role());
            assertEquals("Hello", result.get(1).content());
        }

        @Test
        @DisplayName("多个 system 消息全部转换为 user")
        void shouldConvertAllSystemMessages() {
            List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                    new ApiCompatibilityAdapter.ChatMessage("system", "Sys1"),
                    new ApiCompatibilityAdapter.ChatMessage("system", "Sys2"),
                    new ApiCompatibilityAdapter.ChatMessage("user", "Hi")
            );

            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

            assertEquals(3, result.size());
            assertEquals("user", result.get(0).role());
            assertEquals("[System] Sys1", result.get(0).content());
            assertEquals("user", result.get(1).role());
            assertEquals("[System] Sys2", result.get(1).content());
            assertEquals("user", result.get(2).role());
        }

        @Test
        @DisplayName("非 system 消息不受影响")
        void shouldNotChangeNonSystemMessages() {
            List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                    new ApiCompatibilityAdapter.ChatMessage("assistant", "回答"),
                    new ApiCompatibilityAdapter.ChatMessage("user", "问题")
            );

            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

            assertEquals(2, result.size());
            assertEquals("assistant", result.get(0).role());
            assertEquals("回答", result.get(0).content());
        }
    }

    @Nested
    @DisplayName("requiresSystemMessageFirst = true (reordering)")
    class SystemFirstReorderTests {

        private final ApiCompatibilityAdapter adapter = new ApiCompatibilityAdapter() {
            @Override
            public boolean supportsSystemMessage() { return true; }

            @Override
            public boolean supportsMultipleSystemMessages() { return true; }

            @Override
            public boolean requiresSystemMessageFirst() { return true; }
        };

        @Test
        @DisplayName("system 消息不在首位时应重新排序到首位")
        void shouldReorderSystemToFirst() {
            List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                    new ApiCompatibilityAdapter.ChatMessage("user", "Hello"),
                    new ApiCompatibilityAdapter.ChatMessage("system", "You are assistant"),
                    new ApiCompatibilityAdapter.ChatMessage("assistant", "Hi")
            );

            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

            assertEquals(3, result.size());
            assertEquals("system", result.get(0).role());
            assertEquals("You are assistant", result.get(0).content());
            assertEquals("user", result.get(1).role());
            assertEquals("Hello", result.get(1).content());
        }

        @Test
        @DisplayName("system 消息已在首位时不改变顺序")
        void shouldNotReorderWhenSystemAlreadyFirst() {
            List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                    new ApiCompatibilityAdapter.ChatMessage("system", "You are assistant"),
                    new ApiCompatibilityAdapter.ChatMessage("user", "Hello"),
                    new ApiCompatibilityAdapter.ChatMessage("assistant", "Hi")
            );

            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

            assertEquals(3, result.size());
            assertEquals("system", result.get(0).role());
            assertEquals("You are assistant", result.get(0).content());
        }

        @Test
        @DisplayName("无 system 消息时不改变顺序")
        void shouldNotReorderWhenNoSystem() {
            List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                    new ApiCompatibilityAdapter.ChatMessage("user", "Hello"),
                    new ApiCompatibilityAdapter.ChatMessage("assistant", "Hi")
            );

            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

            assertEquals(2, result.size());
            assertEquals("user", result.get(0).role());
        }
    }

    @Nested
    @DisplayName("mixed adaptation scenarios")
    class MixedAdaptationTests {

        @Test
        @DisplayName("supportsSystem=false AND supportsMultiple=false: convert then merge")
        void shouldConvertSystemThenMergeMultiple() {
            ApiCompatibilityAdapter adapter = new ApiCompatibilityAdapter() {
                @Override
                public boolean supportsSystemMessage() { return false; }

                @Override
                public boolean supportsMultipleSystemMessages() { return false; }

                @Override
                public boolean requiresSystemMessageFirst() { return false; }
            };

            List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                    new ApiCompatibilityAdapter.ChatMessage("system", "Sys1"),
                    new ApiCompatibilityAdapter.ChatMessage("system", "Sys2"),
                    new ApiCompatibilityAdapter.ChatMessage("user", "Hello")
            );

            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

            // Step 1: system→user conversion turns both system messages into user messages
            // Step 2: mergeSystemMessages only merges "system" role, so converted user messages pass through
            assertEquals(3, result.size());
            assertEquals("user", result.get(0).role());
            assertEquals("[System] Sys1", result.get(0).content());
            assertEquals("user", result.get(1).role());
            assertEquals("[System] Sys2", result.get(1).content());
            assertEquals("user", result.get(2).role());
        }

        @Test
        @DisplayName("supportsSystem=false AND requiresFirst=true: convert then reorder")
        void shouldConvertSystemThenReorder() {
            ApiCompatibilityAdapter adapter = new ApiCompatibilityAdapter() {
                @Override
                public boolean supportsSystemMessage() { return false; }

                @Override
                public boolean supportsMultipleSystemMessages() { return true; }

                @Override
                public boolean requiresSystemMessageFirst() { return true; }
            };

            List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                    new ApiCompatibilityAdapter.ChatMessage("user", "Hello"),
                    new ApiCompatibilityAdapter.ChatMessage("system", "Sys1"),
                    new ApiCompatibilityAdapter.ChatMessage("assistant", "Hi")
            );

            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

            // Step 1: system→user conversion
            // Step 2: requiresFirst only checks "system" role, converted messages are user so no reorder
            assertEquals(3, result.size());
            assertEquals("user", result.get(0).role()); // "Hello" stays first
            assertEquals("[System] Sys1", result.get(1).content());
        }
    }
}
