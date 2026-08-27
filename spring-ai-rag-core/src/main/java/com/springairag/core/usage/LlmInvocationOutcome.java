package com.springairag.core.usage;

/**
 * 单次模型 invocation 的终态。
 */
public enum LlmInvocationOutcome {
    SUCCEEDED,
    FAILED,
    CANCELLED
}
