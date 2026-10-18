package com.springairag.core.chat;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class ModelSafetyRefusalNormalizer {

    static final String METADATA_KEY = "modelSafetyRefusal";
    static final String FILTERED_ANSWER = "模型因安全策略未回答该请求。请调整问题，避免请求敏感或不安全的内容。";

    private ModelSafetyRefusalNormalizer() {
    }

    static ChatClientResponse normalize(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null || response.chatResponse().getResult() == null) {
            return response;
        }
        ChatResponse normalized = normalize(response.chatResponse());
        return normalized == response.chatResponse() ? response : new ChatClientResponse(normalized, response.context());
    }

    static ChatResponse normalize(ChatResponse chat) {
        if (chat == null || chat.getResult() == null) {
            return chat;
        }
        Generation generation = chat.getResult();
        AssistantMessage output = generation.getOutput();
        AssistantMessage normalized = normalizeMessage(output,
                generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason());
        if (normalized == output) {
            return chat;
        }
        ArrayList<Generation> results = new ArrayList<>(chat.getResults());
        results.set(0, new Generation(normalized, generation.getMetadata()));
        return new ChatResponse(results, chat.getMetadata());
    }

    static AssistantMessage normalizeMessage(AssistantMessage output, String finishReason) {
        Map<String, Object> properties = output == null ? Map.of() : output.getMetadata();
        if (Boolean.TRUE.equals(properties.get(METADATA_KEY))) {
            return output;
        }
        Object refusalValue = properties.get("refusal");
        String refusal = refusalValue instanceof String text && !text.isBlank() ? text : null;
        boolean filtered = "content_filter".equalsIgnoreCase(finishReason);
        if (refusal == null && !filtered) {
            return output;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(properties);
        metadata.put(METADATA_KEY, true);
        return AssistantMessage.builder()
                .content(refusal == null ? FILTERED_ANSWER : refusal)
                .properties(metadata)
                .toolCalls(output == null ? java.util.List.of() : output.getToolCalls())
                .media(output == null ? java.util.List.of() : output.getMedia())
                .build();
    }
}
