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
     * 检查是否支持 system 消息角色
     *
     * <p>MiniMax、部分国产模型不支持 role: system，直接拒绝。
     * OpenAI、DeepSeek 等支持。
     *
     * @return true=支持 system 角色，false=不支持（会自动转换为 user 角色）
     */
    default boolean supportsSystemMessage() {
        return true;
    }

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
     * <p>处理策略：
     * <ol>
     *   <li>如果不支持 system 角色，将所有 system 消息转为 user 消息（加 [System] 前缀）</li>
     *   <li>如果不支持多个 system 消息，合并多个为单个</li>
     *   <li>如果要求 system 在最前面但当前不在前面，调整顺序</li>
     * </ol>
     */
    default List<ChatMessage> normalizeMessages(List<ChatMessage> messages) {
        List<ChatMessage> normalized = messages;

        // Step 1: 如果不支持 system 角色，转换为 user 消息
        if (!supportsSystemMessage()) {
            normalized = normalized.stream()
                    .map(msg -> "system".equals(msg.role())
                            ? new ChatMessage("user", "[System] " + msg.content())
                            : msg)
                    .toList();
        }

        // Step 2: 如果不支持多个 system，合并为单个
        if (!supportsMultipleSystemMessages()) {
            normalized = mergeSystemMessages(normalized);
        }

        // Step 3: 如果要求 system 在最前面但当前不在前面，调整顺序
        if (requiresSystemMessageFirst() && !normalized.isEmpty()) {
            normalized = reorderSystemMessageFirst(normalized);
        }

        return normalized;
    }

    /** 合并多个 system 消息为一个 */
    private List<ChatMessage> mergeSystemMessages(List<ChatMessage> messages) {
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

    /** 将 system 消息移到列表最前面 */
    private List<ChatMessage> reorderSystemMessageFirst(List<ChatMessage> messages) {
        List<ChatMessage> systemMsgs = new java.util.ArrayList<>();
        List<ChatMessage> otherMsgs = new java.util.ArrayList<>();
        for (ChatMessage msg : messages) {
            if ("system".equals(msg.role())) {
                systemMsgs.add(msg);
            } else {
                otherMsgs.add(msg);
            }
        }
        List<ChatMessage> result = new java.util.ArrayList<>(systemMsgs);
        result.addAll(otherMsgs);
        return result;
    }

    /**
     * 消息记录
     */
    record ChatMessage(String role, String content) {}
}
