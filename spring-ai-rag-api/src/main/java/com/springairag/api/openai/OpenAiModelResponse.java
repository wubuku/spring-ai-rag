package com.springairag.api.openai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI Models API 响应。
 */
public final class OpenAiModelResponse {

    private OpenAiModelResponse() {
    }

    public record Model(
            String id,
            String object,
            long created,
            @JsonProperty("owned_by") String ownedBy) {
    }

    public record ListEnvelope(String object, List<Model> data) {
        public ListEnvelope {
            data = data == null ? List.of() : List.copyOf(data);
        }
    }
}
