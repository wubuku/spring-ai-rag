package com.springairag.api.service;

/**
 * 自定义 Advisor 的执行作用域。
 */
public enum AdvisorScope {
    /**
     * 每个模型候选或重试执行一次。
     */
    ATTEMPT,

    /**
     * 每次底层模型调用执行一次；AGENT 工具循环中可能执行多次。
     */
    MODEL_CALL
}
