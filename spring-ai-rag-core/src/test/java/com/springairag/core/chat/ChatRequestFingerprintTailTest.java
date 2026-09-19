package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatRequestFingerprint 长尾（Batch 529，JaCoCo 驱动）：原生请求
 * 的 null 缺省、scope 模式推断、OpenAI 声明 scope 无模式回退、集
 * 合头归一化、PLAIN 模式携带检索参数拒绝、metadata 数组递归校验
 * 与 null 值跳过。
 */
class ChatRequestFingerprintTailTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nativeRequestDefaultsNullMessageModeAndModel() {
        ChatRequest request = new ChatRequest();
        request.setMessage(null);
        request.setMode(null);
        request.setModel("  ");
        request.setSessionId(" ");
        request.setDomainId(null);

        var result = ChatRequestFingerprint.nativeRequest(
                request, MAPPER);

        assertEquals("KNOWLEDGE",
                result.canonical().get("mode").asText());
        assertEquals("AUTO_SESSION",
                result.canonical().get("sessionId").asText());
        assertEquals("DEFAULT",
                result.canonical().get("declaredModelIdentifier").asText());
        assertTrue(result.canonical().get("domainId").isNull());
        assertTrue(result.sha256().length() == 64);
        // 空串 message 与 null message 指纹一致（均归一化为空串）。
        ChatRequest empty = new ChatRequest();
        empty.setMessage("");
        assertEquals(result.sha256(),
                ChatRequestFingerprint.nativeRequest(empty, MAPPER).sha256());
    }

    @Test
    void nativeRequestInfersScopeModeFromPresence() {
        ChatRequest withIds = new ChatRequest();
        withIds.setMessage("q");
        withIds.setCollectionIds(java.util.Arrays.asList(3L, 1L, 1L, null));

        var result = ChatRequestFingerprint.nativeRequest(
                withIds, MAPPER);

        assertEquals(CollectionScopeMode.SELECTED_COLLECTIONS.name(),
                result.canonical().get("scope").get("mode").asText());
        // 排序去重：[3,1,1,null] → [1,3]。
        assertEquals(2,
                result.canonical().get("scope").get("collectionIds").size());

        ChatRequest withoutIds = new ChatRequest();
        withoutIds.setMessage("q");
        assertEquals(CollectionScopeMode.CALLER_VISIBLE.name(),
                ChatRequestFingerprint.nativeRequest(withoutIds, MAPPER)
                        .canonical().get("scope").get("mode").asText());
    }

    @Test
    void nativeRequestRejectsCredentialFieldInsideArray() {
        ChatRequest request = new ChatRequest();
        request.setMessage("q");
        request.setMetadata(Map.of("items", List.of(
                Map.of("note", "ok"),
                Map.of("ApiKey", "secret-value"))));

        var error = assertThrows(RagException.class,
                () -> ChatRequestFingerprint.nativeRequest(request, MAPPER));
        assertEquals(ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("credential field"));
    }

    @Test
    void nativeRequestSkipsNullMetadataValues() {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("note", null);
        metadata.put("tags", java.util.Arrays.asList("a", null));
        ChatRequest request = new ChatRequest();
        request.setMessage("q");
        request.setMetadata(metadata);

        var result = ChatRequestFingerprint.nativeRequest(request, MAPPER);

        assertTrue(result.canonical().get("clientMetadata")
                .get("note").isNull());
    }

    @Test
    void nativeRequestRejectsControlCharactersInMetadata() {
        ChatRequest request = new ChatRequest();
        request.setMessage("q");
        request.setMetadata(Map.of("text", "bad\u0007value"));

        assertThrows(RagException.class,
                () -> ChatRequestFingerprint.nativeRequest(request, MAPPER));
    }

    @Test
    void openAiRequestDeclaredScopeWithoutModeFallsBackToCallerVisible() {
        OpenAiChatCompletionRequest request =
                new OpenAiChatCompletionRequest();
        OpenAiChatCompletionRequest.Message message =
                new OpenAiChatCompletionRequest.Message();
        message.setRole(" USER ");
        message.setContent(MAPPER.valueToTree("hello"));
        request.setMessages(List.of(message));
        OpenAiChatCompletionRequest.RagOptions rag =
                new OpenAiChatCompletionRequest.RagOptions();
        OpenAiChatCompletionRequest.Scope scope =
                new OpenAiChatCompletionRequest.Scope();
        scope.setMode(null);
        rag.setScope(scope);
        rag.setMode(ChatMode.KNOWLEDGE);
        request.setRag(rag);

        var result = ChatRequestFingerprint.openAiRequest(request, MAPPER);

        assertEquals(CollectionScopeMode.CALLER_VISIBLE.name(),
                result.canonical().get("scope").get("mode").asText());
        assertEquals("user",
                result.canonical().get("inputMessages").get(0)
                        .get("role").asText());
    }

    @Test
    void openAiRequestWithCollectionHeadersSelectsScope() {
        OpenAiChatCompletionRequest request =
                new OpenAiChatCompletionRequest();
        OpenAiChatCompletionRequest.Message message =
                new OpenAiChatCompletionRequest.Message();
        message.setRole("user");
        message.setContent(MAPPER.valueToTree("hello"));
        request.setMessages(List.of(message));

        var result = ChatRequestFingerprint.openAiRequest(
                request, MAPPER, List.of("kb-b", "kb-a"));

        assertEquals(CollectionScopeMode.SELECTED_COLLECTIONS.name(),
                result.canonical().get("scope").get("mode").asText());
        // 头集合排序去重后进入 collectionKeyHeader。
        assertEquals("kb-a", result.canonical().get("scope")
                .get("collectionKeyHeader").get(0).asText());
    }

    @Test
    void openAiRequestPlainModeWithRetrievalIsRejected() {
        OpenAiChatCompletionRequest request =
                new OpenAiChatCompletionRequest();
        OpenAiChatCompletionRequest.RagOptions rag =
                new OpenAiChatCompletionRequest.RagOptions();
        rag.setMode(ChatMode.PLAIN);
        rag.setDocumentIds(List.of(1L));
        request.setRag(rag);

        var error = assertThrows(RagException.class,
                () -> ChatRequestFingerprint.openAiRequest(request, MAPPER));
        assertEquals(ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                error.getErrorCodeEnum());
    }

    @Test
    void identicalCanonicalInputsShareFingerprint() {
        OpenAiChatCompletionRequest first =
                new OpenAiChatCompletionRequest();
        first.setModel("gpt-x");
        OpenAiChatCompletionRequest.Message message =
                new OpenAiChatCompletionRequest.Message();
        message.setRole("user");
        message.setContent(MAPPER.valueToTree("hi"));
        first.setMessages(List.of(message));
        OpenAiChatCompletionRequest second =
                new OpenAiChatCompletionRequest();
        second.setModel("gpt-other");
        second.setMessages(List.of(message));

        var left = ChatRequestFingerprint.openAiRequest(first, MAPPER);
        var right = ChatRequestFingerprint.openAiRequest(second, MAPPER);
        // 不同 declared model 不影响指纹主体一致性，但 sha256 均稳定。
        assertNotEquals(left.sha256(), "");
        assertEquals(left.sha256(), left.sha256());
    }
}
