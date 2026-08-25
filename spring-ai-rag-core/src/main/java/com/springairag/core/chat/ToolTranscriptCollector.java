package com.springairag.core.chat;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 收集单次 Chat attempt 内已完成的配对工具交换。
 *
 * <p>Spring AI 的 ToolCallAdvisor 在内部维护工具循环，中间消息不会可靠进入
 * MessageChatMemoryAdvisor。该收集器通过 advisor context 随请求传播，只保存当前
 * attempt 的 assistant tool call 与已裁剪 tool result，最终再生成有界持久投影。</p>
 */
final class ToolTranscriptCollector {

    static final String CONTEXT_KEY =
            "com.springairag.core.chat.tool-transcript-collector";

    private final List<Message> pairedMessages = new ArrayList<>();

    synchronized void record(
            ChatClientResponse response,
            ToolExecutionResult result) {
        AssistantMessage assistant = response != null
                && response.chatResponse() != null
                && response.chatResponse().getResult() != null
                ? response.chatResponse().getResult().getOutput()
                : null;
        if (assistant == null || assistant.getToolCalls().isEmpty()
                || result == null || result.conversationHistory() == null) {
            return;
        }
        ToolResponseMessage toolResponse = null;
        List<Message> history = result.conversationHistory();
        for (int index = history.size() - 1; index >= 0; index--) {
            if (history.get(index) instanceof ToolResponseMessage found) {
                toolResponse = found;
                break;
            }
        }
        if (toolResponse == null
                || ChatMemoryMessageProjector.toolTranscript(
                        List.of(assistant, toolResponse),
                        assistant.getToolCalls().size(),
                        Integer.MAX_VALUE).isEmpty()) {
            return;
        }
        pairedMessages.add(assistant);
        pairedMessages.add(toolResponse);
    }

    synchronized List<Map<String, Object>> transcript(
            int maxCalls,
            int maxCharacters) {
        return ChatMemoryMessageProjector.toolTranscript(
                List.copyOf(pairedMessages),
                maxCalls,
                maxCharacters);
    }

    static List<Map<String, Object>> transcript(
            ChatClientResponse response,
            int maxCalls,
            int maxCharacters) {
        if (response == null || response.context() == null) {
            return List.of();
        }
        Object value = response.context().get(CONTEXT_KEY);
        return value instanceof ToolTranscriptCollector collector
                ? collector.transcript(maxCalls, maxCharacters)
                : List.of();
    }
}
