package com.springairag.core.chat;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 可替换的 prompt token 保守估算器。
 *
 * <p>估算用于服务端预算保护，不宣称等于具体 provider 的 tokenizer 结果。</p>
 */
public interface PromptTokenEstimator {

    int estimate(String text);

    default int estimate(Message message) {
        return message == null ? 0 : estimate(message.getText());
    }

    default int estimate(Document document) {
        return document == null ? 0 : estimate(document.getFormattedContent());
    }

    default int estimate(ToolDefinition definition) {
        if (definition == null) {
            return 0;
        }
        return estimate(String.join("\n",
                nullToEmpty(definition.name()),
                nullToEmpty(definition.description()),
                nullToEmpty(definition.inputSchema())));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
