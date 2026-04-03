package com.springairag.core.adapter;

/**
 * MiniMax API 适配器
 *
 * <p>MiniMax API 不支持 role: system 消息，会返回错误。
 * 所有 system 消息会自动转换为 user 消息（加 [System] 前缀）。
 * 即使转换后只有一个 system 消息也转为 user（因为不支持 system 角色）。
 *
 * <p>已验证：
 * - ❌ role: system → 400 错误 "invalid message role: system"
 * - ✅ 所有 system 消息 → 转为 user 消息
 */
public class MiniMaxAdapter implements ApiCompatibilityAdapter {

    @Override
    public boolean supportsSystemMessage() {
        return false; // MiniMax 不支持 system 角色
    }

    @Override
    public boolean supportsMultipleSystemMessages() {
        return false;
    }

    @Override
    public boolean requiresSystemMessageFirst() {
        return true;
    }
}
