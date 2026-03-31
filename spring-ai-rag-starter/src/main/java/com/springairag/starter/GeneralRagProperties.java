package com.springairag.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通用 RAG 服务配置属性
 */
@ConfigurationProperties(prefix = "general.rag")
public class GeneralRagProperties {

    /**
     * 是否启用 RAG 服务
     */
    private boolean enabled = true;

    /**
     * 对话记忆配置
     */
    private Memory memory = new Memory();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Memory getMemory() { return memory; }
    public void setMemory(Memory memory) { this.memory = memory; }

    public static class Memory {
        private boolean enabled = true;
        private String type = "jdbc";
        private int maxMessages = 20;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public int getMaxMessages() { return maxMessages; }
        public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }
    }
}
