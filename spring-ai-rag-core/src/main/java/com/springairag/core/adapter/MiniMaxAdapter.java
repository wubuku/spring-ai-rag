package com.springairag.core.adapter;

/**
 * MiniMax API 适配器
 *
 * <p>MiniMax 只支持单个 system 消息（且必须在最前面）。
 * 多个 system 消息会被合并为一个。
 *
 * <p>已验证：
 * - ✅ 单个 system 消息在最前面
 * - ❌ 多个 system 消息 → 400 错误
 * - ❌ system 消息在 user 之后 → 400 错误
 */
public class MiniMaxAdapter implements ApiCompatibilityAdapter {

    @Override
    public boolean supportsMultipleSystemMessages() {
        return false;
    }

    @Override
    public boolean requiresSystemMessageFirst() {
        return true;
    }
}
