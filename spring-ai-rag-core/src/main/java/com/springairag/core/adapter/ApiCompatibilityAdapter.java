package com.springairag.core.adapter;

import java.util.List;

/**
 * API 兼容性适配接口
 *
 * <p>不同 LLM API 对 OpenAI 兼容程度不同。此接口定义了需要适配的行为，
 * 各 API 提供者可实现不同的适配策略。
 */
public interface ApiCompatibilityAdapter {

    /**
     * 检查是否支持多个 system 消息
     *
     * <p>MiniMax、部分国产模型只支持单个 system 消息（且必须在最前面）。
     * OpenAI、Anthropic 支持多个 system 消息。
     */
    boolean supportsMultipleSystemMessages();

    /**
     * 检查 system 消息是否必须在最前面
     */
    boolean requiresSystemMessageFirst();

    /**
     * 规范化消息列表——确保符合目标 API 的要求
     *
     * <p>默认实现：合并多个 system 消息为一个
     */
    default List<ChatMessage> normalizeMessages(List<ChatMessage> messages) {
        if (supportsMultipleSystemMessages()) {
            return messages;
        }

        // 合并所有 system 消息为一个
        StringBuilder combinedSystem = new StringBuilder();
        List<ChatMessage> nonSystemMessages = new java.util.ArrayList<>();

        for (ChatMessage msg : messages) {
            if ("system".equals(msg.role())) {
                if (!combinedSystem.isEmpty()) {
                    combinedSystem.append("\n\n");
                }
                combinedSystem.append(msg.content());
            } else {
                nonSystemMessages.add(msg);
            }
        }

        List<ChatMessage> result = new java.util.ArrayList<>();
        if (!combinedSystem.isEmpty()) {
            result.add(new ChatMessage("system", combinedSystem.toString()));
        }
        result.addAll(nonSystemMessages);
        return result;
    }

    /**
     * 消息记录
     */
    record ChatMessage(String role, String content) {}
}
