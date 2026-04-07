package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chat memory configuration properties.
 */
@ConfigurationProperties(prefix = "rag.memory")
public class RagMemoryProperties {

    /**
     * Maximum number of messages in chat context (Spring AI JdbcChatMemory limit).
     */
    private int maxMessages = 20;

    /**
     * Chat history retention days (0 = no expiration).
     * Records in rag_chat_history older than this will be purged by scheduled cleanup.
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
