package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * 指纹终局序列化失败包装（Batch 964，JaCoCo 驱动）：validateMetadata
 * 已对 clientMetadata 做过同 mapper 预序列化，原生路径的自然失败进
 * 不到终局 catch——这里用"第一次真跑、第二次抛"的顺序桩验证包装
 * 契约；OpenAI 路径没有预序列化，第一次写即终局写。
 */
class ChatRequestFingerprintCanonicalizeWrapTailTest {

    @Test
    void nativeRequestWrapsFinalCanonicalizationFailure() throws Exception {
        ObjectMapper failing = spy(new ObjectMapper());
        // 第 1 次写是 validateMetadata 的预序列化：放行；
        // 第 2 次写是终局序列化：抛出。
        doAnswer(invocation -> invocation.callRealMethod())
                .doThrow(new IllegalStateException("boom"))
                .when(failing).writeValueAsBytes(any());
        ChatRequest request = new ChatRequest();
        request.setMessage("q");
        request.setMetadata(Map.of("note", "ok"));

        RagException error = assertThrows(RagException.class,
                () -> ChatRequestFingerprint.nativeRequest(request, failing));

        assertEquals(ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage()
                .contains("metadata cannot be canonicalized"));
    }

    @Test
    void openAiRequestWrapsCanonicalizationFailure() throws Exception {
        ObjectMapper failing = spy(new ObjectMapper());
        doThrow(new IllegalStateException("boom"))
                .when(failing).writeValueAsBytes(any());
        OpenAiChatCompletionRequest request =
                new OpenAiChatCompletionRequest();
        request.setMessages(List.of());

        RagException error = assertThrows(RagException.class,
                () -> ChatRequestFingerprint.openAiRequest(request, failing));

        assertEquals(ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage()
                .contains("OpenAI request cannot be canonicalized"));
    }
}
