package com.springairag.core.chat;

/**
 * 与 HTTP transport 解耦的对话输入消息。
 */
public record ChatInputMessage(Role role, String content) {

    public ChatInputMessage {
        if (role == null) {
            throw new IllegalArgumentException("chat input message role must not be null");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("chat input message content must not be blank");
        }
    }

    public enum Role {
        SYSTEM,
        DEVELOPER,
        USER,
        ASSISTANT
    }
}
