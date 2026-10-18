package com.springairag.core.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSafetyRefusalNormalizerTest {

    @Test
    void ordinaryTextAndUnclassifiedEmptyAnswersAreNotReinterpreted() {
        for (String answer : List.of("普通回答", "出于安全原因不能回答。", "")) {
            ChatClientResponse response = response(answer, Map.of(), "stop");
            assertSame(response, ModelSafetyRefusalNormalizer.normalize(response));
        }
        assertNull(ModelSafetyRefusalNormalizer.normalize((ChatClientResponse) null));
    }

    @Test
    void explicitRefusalBecomesVisibleWithoutChangingUsageContextOrFinishReason() {
        ChatClientResponse response = response("", Map.of("refusal", "不能提供敏感凭据。"), "stop");
        ChatClientResponse normalized = ModelSafetyRefusalNormalizer.normalize(response);
        assertEquals("不能提供敏感凭据。", normalized.chatResponse().getResult().getOutput().getText());
        assertTrue((Boolean) normalized.chatResponse().getResult().getOutput().getMetadata().get("modelSafetyRefusal"));
        assertSame(response.chatResponse().getMetadata(), normalized.chatResponse().getMetadata());
        assertSame(response.context(), normalized.context());
        assertEquals("stop", normalized.chatResponse().getResult().getMetadata().getFinishReason());
    }

    @Test
    void contentFilterWithNoBodyGetsControlledSafeAnswer() {
        ChatClientResponse normalized = ModelSafetyRefusalNormalizer.normalize(response("", Map.of(), "content_filter"));
        assertEquals(ModelSafetyRefusalNormalizer.FILTERED_ANSWER, normalized.chatResponse().getResult().getOutput().getText());
        assertEquals("content_filter", normalized.chatResponse().getResult().getMetadata().getFinishReason());
    }

    @Test
    void explicitRefusalWinsOverConflictingBodyWithoutInventingCitations() {
        ChatClientResponse normalized = ModelSafetyRefusalNormalizer.normalize(response(
                "不应输出的内容。[c1]", Map.of("refusal", "不能回答。"), "stop"));
        assertEquals("不能回答。", normalized.chatResponse().getResult().getOutput().getText());
    }

    @Test
    void splitStreamingRefusalSurvivesAggregationAndEmptyFinalChunk() {
        AtomicReference<ChatClientResponse> result = new AtomicReference<>();
        new ChatClientMessageAggregator().aggregateChatClientResponse(Flux.just(
                response("", Map.of("refusal", "不能提供"), ""),
                response("", Map.of("refusal", "敏感凭据。"), ""),
                response("", Map.of("refusal", ""), "stop"))
                .map(ModelSafetyRefusalNormalizer::normalize), result::set).blockLast();
        assertEquals("不能提供敏感凭据。", result.get().chatResponse().getResult().getOutput().getText());
        assertTrue((Boolean) result.get().chatResponse().getResult().getOutput().getMetadata().get("modelSafetyRefusal"));
    }

    @Test
    void modelBoundaryNormalizesBeforeAdvisorsAndNeverRetries() {
        var delegate = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        var prompt = new org.springframework.ai.chat.prompt.Prompt("敏感问题");
        var upstream = response("", Map.of("refusal", "不能回答。"), "stop").chatResponse();
        org.mockito.Mockito.when(delegate.call(prompt)).thenReturn(upstream);
        org.mockito.Mockito.when(delegate.stream(prompt)).thenReturn(Flux.just(upstream));
        SafetyRefusalChatModel model = new SafetyRefusalChatModel(delegate);
        assertEquals("不能回答。", model.call(prompt).getResult().getOutput().getText());
        assertEquals("不能回答。", model.stream(prompt).blockLast().getResult().getOutput().getText());
        org.mockito.Mockito.verify(delegate).call(prompt);
        org.mockito.Mockito.verify(delegate).stream(prompt);
    }

    @Test
    void normalizationIsIdempotentAfterResponseAggregation() {
        var normalized = ModelSafetyRefusalNormalizer.normalize(response("", Map.of(), "content_filter"));
        assertSame(normalized, ModelSafetyRefusalNormalizer.normalize(normalized));
    }

    private ChatClientResponse response(String text, Map<String, Object> properties, String finishReason) {
        return new ChatClientResponse(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(text).properties(properties).build(),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())),
                ChatResponseMetadata.builder().model("test-model").build()), Map.of("diagnostic", "preserved"));
    }
}
