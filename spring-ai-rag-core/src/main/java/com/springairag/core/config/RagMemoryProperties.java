package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话记忆配置
 */
@ConfigurationProperties(prefix = "rag.memory")
public class RagMemoryProperties {

    /**
     * 对话上下文最大消息条数（Spring AI JdbcChatMemory 限制）
     */
    private int maxMessages = 20;

    /**
     * 聊天历史保留天数（0 = 不过期）
     * 超过此天数的 rag_chat_history 记录将被定时清理
     */
    private int messageTtlDays = 30;

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public int getMessageTtlDays() {
        return messageTtlDays;
    }

    public void setMessageTtlDays(int messageTtlDays) {
        this.messageTtlDays = messageTtlDays;
    }
}
