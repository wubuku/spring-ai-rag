package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        RagException merged = assertThrows(
                RagException.class,
                () -> ChatRequestFingerprint.openAiRequest(
                        request, objectMapper, List.of("support,billing")));
        RagException blank = assertThrows(
                RagException.class,
                () -> ChatRequestFingerprint.openAiRequest(
                        request, objectMapper, List.of(" ")));
        // 合并成一个键与整串空白是同一个契约：请求元数据里的 header 值非法。
        assertEquals(ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                merged.getErrorCodeEnum());
        assertEquals(ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                blank.getErrorCodeEnum());
    }
}
