package com.springairag.core.usage;

/**
 * 应用内一次模型调用的稳定用途分类。
 */
public enum LlmInvocationPurpose {
    CHAT,
    QUERY_TRANSFORM,
    QUERY_EXPAND,
    SUMMARY
}
