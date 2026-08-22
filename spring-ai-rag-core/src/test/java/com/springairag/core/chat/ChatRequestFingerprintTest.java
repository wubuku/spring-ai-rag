package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatRequestFingerprintTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void collectionHeaderIsPartOfOpenAiDeclarationFingerprint() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model": "rag-default",
                  "messages": [
                    {"role": "user", "content": "hello"}
                  ]
                }
                """, OpenAiChatCompletionRequest.class);

        ChatRequestFingerprint.Result withoutHeader =
                ChatRequestFingerprint.openAiRequest(
                        request, objectMapper, List.of());
        ChatRequestFingerprint.Result withSupportHeader =
                ChatRequestFingerprint.openAiRequest(
                        request, objectMapper, List.of("support"));
        ChatRequestFingerprint.Result withBillingHeader =
                ChatRequestFingerprint.openAiRequest(
                        request, objectMapper, List.of("billing"));

        assertNotEquals(withoutHeader.sha256(), withSupportHeader.sha256());
        assertNotEquals(withSupportHeader.sha256(), withBillingHeader.sha256());
    }

    @Test
    void collectionHeaderRejectsMergedOrBlankValues() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model": "rag-default",
                  "messages": [
                    {"role": "user", "content": "hello"}
                  ]
                }
                """, OpenAiChatCompletionRequest.class);

        assertThrows(
                RuntimeException.class,
                () -> ChatRequestFingerprint.openAiRequest(
                        request, objectMapper, List.of("support,billing")));
        assertThrows(
                RuntimeException.class,
                () -> ChatRequestFingerprint.openAiRequest(
                        request, objectMapper, List.of(" ")));
    }
}
