package com.springairag.core.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MiniMaxAdapter 单元测试
 */
class MiniMaxAdapterTest {

    private final MiniMaxAdapter adapter = new MiniMaxAdapter();

    @Test
    void supportsMultipleSystemMessages_returnsFalse() {
        assertFalse(adapter.supportsMultipleSystemMessages());
    }

    @Test
    void requiresSystemMessageFirst_returnsTrue() {
        assertTrue(adapter.requiresSystemMessageFirst());
    }

    @Test
    void normalizeMessages_multipleSystemMessages_merged() {
        List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                new ApiCompatibilityAdapter.ChatMessage("system", "你是助手A"),
                new ApiCompatibilityAdapter.ChatMessage("user", "你好"),
                new ApiCompatibilityAdapter.ChatMessage("system", "你也是助手B"),
                new ApiCompatibilityAdapter.ChatMessage("assistant", "嗨")
        );

        List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

        // MiniMax 不支持多 system 消息，应合并为一个
        assertEquals(3, result.size());

        // 第一条应该是合并后的 system 消息
        assertEquals("system", result.get(0).role());
        assertTrue(result.get(0).content().contains("你是助手A"));
        assertTrue(result.get(0).content().contains("你也是助手B"));

        // 后面是非 system 消息
        assertEquals("user", result.get(1).role());
        assertEquals("你好", result.get(1).content());
        assertEquals("assistant", result.get(2).role());
        assertEquals("嗨", result.get(2).content());
    }

    @Test
    void normalizeMessages_singleSystemMessage_unchanged() {
        List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                new ApiCompatibilityAdapter.ChatMessage("system", "你是助手"),
                new ApiCompatibilityAdapter.ChatMessage("user", "你好")
        );

        List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

        assertEquals(2, result.size());
        assertEquals("system", result.get(0).role());
        assertEquals("你是助手", result.get(0).content());
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
    void normalizeMessages_systemMessageNotFirst_mergedAndPlacedFirst() {
        // 场景：system 消息不在最前面（RerankAdvisor 注入的上下文）
        List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                new ApiCompatibilityAdapter.ChatMessage("user", "问题"),
                new ApiCompatibilityAdapter.ChatMessage("system", "参考资料A"),
                new ApiCompatibilityAdapter.ChatMessage("system", "参考资料B")
        );

        List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

        // 合并所有 system 消息，放在最前面
        assertEquals(2, result.size());
        assertEquals("system", result.get(0).role());
        assertTrue(result.get(0).content().contains("参考资料A"));
        assertTrue(result.get(0).content().contains("参考资料B"));
        assertEquals("user", result.get(1).role());
    }

    @Test
    void normalizeMessages_threeSystemMessages_allMerged() {
        List<ApiCompatibilityAdapter.ChatMessage> messages = List.of(
                new ApiCompatibilityAdapter.ChatMessage("system", "角色A"),
                new ApiCompatibilityAdapter.ChatMessage("system", "角色B"),
                new ApiCompatibilityAdapter.ChatMessage("system", "角色C"),
                new ApiCompatibilityAdapter.ChatMessage("user", "提问")
        );

        List<ApiCompatibilityAdapter.ChatMessage> result = adapter.normalizeMessages(messages);

        assertEquals(2, result.size());
        assertEquals("system", result.get(0).role());
        assertTrue(result.get(0).content().contains("角色A"));
        assertTrue(result.get(0).content().contains("角色B"));
        assertTrue(result.get(0).content().contains("角色C"));
    }
}
