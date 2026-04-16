package com.springairag.core.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ApiCompatibilityAdapter} default methods.
 */
class ApiCompatibilityAdapterTest {

    @Nested
    @DisplayName("normalizeMessages - single system message mode")
    class SingleSystemMessageTests {

        private final ApiCompatibilityAdapter adapter = new ApiCompatibilityAdapter() {
            @Override
            public boolean supportsMultipleSystemMessages() { return false; }

            @Override
            public boolean requiresSystemMessageFirst() { return true; }
        };

        @Test
        @DisplayName("multiple system messages should be merged into one")
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
        @DisplayName("single system message is unchanged")
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
        @DisplayName("no system message passes through unchanged")
        void shouldPassThroughWhenNoSystem() {
            List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                    new ApiCompatibilityAdapter.ChatMessage("user", "你好"),
                    new ApiCompatibilityAdapter.ChatMessage("assistant", "你好！")
            );

            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("empty list returns empty")
        void shouldReturnEmptyForEmptyInput() {
            List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(List.of());
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("normalizeMessages - multiple system messages mode")
    class MultiSystemMessageTests {

        private final ApiCompatibilityAdapter adapter = new ApiCompatibilityAdapter() {
            @Override
            public boolean supportsMultipleSystemMessages() { return true; }

            @Override
            public boolean requiresSystemMessageFirst() { return false; }
        };

        @Test
        @DisplayName("messages unchanged when multiple system messages are supported")
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
    @DisplayName("ChatMessage record created normally")
    void chatMessageShouldWork() {
        var msg = new ApiCompatibilityAdapter.ChatMessage("user", "hello");
        assertEquals("user", msg.role());
        assertEquals("hello", msg.content());
    }

    @Nested
    @DisplayName("supportsSystemMessage = false (system to user conversion)")
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
        @DisplayName("system message should be converted to user with [System] prefix")
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
        @DisplayName("all system messages are converted to user")
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
        @DisplayName("non-system messages are unaffected")
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
        @DisplayName("system message not in first position should be reordered to first")
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
        @DisplayName("system message already first should not change order")
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
        @DisplayName("no system message should not change order")
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
