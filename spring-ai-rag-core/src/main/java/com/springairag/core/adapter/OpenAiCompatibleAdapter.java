package com.springairag.core.adapter;

/**
 * OpenAI 兼容 API 适配器
 *
 * <p>适用于：OpenAI、DeepSeek、智谱等完全兼容 OpenAI 的 API。
 * 支持多个 system 消息，无需特殊处理。
 */
public class OpenAiCompatibleAdapter implements ApiCompatibilityAdapter {

    @Override
    public boolean supportsMultipleSystemMessages() {
        return true;
    }

    @Override
    public boolean requiresSystemMessageFirst() {
        return false;
    }
}
