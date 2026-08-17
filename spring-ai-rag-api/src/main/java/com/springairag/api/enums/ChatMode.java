package com.springairag.api.enums;

/**
 * 对话执行模式。
 */
public enum ChatMode {
    /**
     * 每轮固定执行知识库检索。
     */
    KNOWLEDGE,

    /**
     * 由模型通过 Spring AI Tool Calling 按需检索。
     */
    AGENT,

    /**
     * 不访问知识库的普通模型对话。
     */
    PLAIN
}
