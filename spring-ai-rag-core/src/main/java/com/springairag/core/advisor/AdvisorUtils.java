package com.springairag.core.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * Advisor 共享工具方法
 */
public final class AdvisorUtils {

    private AdvisorUtils() {}

    /**
     * 从 ChatClientRequest 中提取最后一个非空 UserMessage 文本
     *
     * @param request 聊天请求
     * @return 用户消息文本，无有效消息时返回 null
     */
    public static String extractUserMessage(ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return null;
        }
        List<Message> messages = request.prompt().getInstructions();
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof UserMessage um) {
                String text = um.getText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }
}
