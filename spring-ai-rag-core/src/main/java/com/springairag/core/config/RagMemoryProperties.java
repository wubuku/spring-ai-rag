package com.springairag.core.config;

/**
 * 对话记忆配置
 */
public class RagMemoryProperties {

    private int maxMessages = 20;

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }
}
