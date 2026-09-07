package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.enums.ErrorCode;
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
 * 传输中立请求指纹契约：语义等价的请求产生相同 SHA-256，
 * 凭据字段/控制字符/超大元数据被拒绝，PLAIN 模式禁用检索范围。
 */
class ChatRequestFingerprintContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatRequest chatRequest() {
        ChatRequest request = new ChatRequest("hello", "session-1");
        request.setMode(ChatMode.KNOWLEDGE);
        return request;
    }

    private void assertMetadataRejected(Map<String, Object> metadata,
                                        ErrorCode expectedCode) {
        ChatRequest request = chatRequest();
        request.setMetadata(metadata);

        RagException error = assertThrows(RagException.class,
                () -> ChatRequestFingerprint.nativeRequest(request, objectMapper));
        assertEquals(expectedCode, error.getErrorCodeEnum());
    }

    @Test
    void rejectsNullRequests() {
        assertThrows(IllegalArgumentException.class,
                () -> ChatRequestFingerprint.nativeRequest(null, objectMapper));
        assertThrows(IllegalArgumentException.class,
                () -> ChatRequestFingerprint.openAiRequest(null, objectMapper));
    }

    @Test
    void rejectsCredentialFieldsInClientMetadata() {
        assertMetadataRejected(
                Map.of("apiKey", "sk-secret"),
                ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID);
        assertMetadataRejected(
                Map.of("nested", Map.of("Authorization", "Bearer x")),
                ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID);
    }

    @Test
    void rejectsControlCharactersInClientMetadata() {
        assertMetadataRejected(
                Map.of("note", "line1\nline2"),
                ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID);
    }

    @Test
    void rejectsOversizedClientMetadata() {
        Map<String, Object> metadata = Map.of(
                "blob", "x".repeat(40 * 1024));
        assertMetadataRejected(metadata,
                ErrorCode.IDEMPOTENCY_REQUEST_TOO_LARGE);
    }

    @Test
    void canonicalizesMetadataSoKeyOrderDoesNotAffectFingerprint() {
        ChatRequest first = chatRequest();
        first.setMetadata(new java.util.LinkedHashMap<>(Map.of("a", "1", "b", "2")));
        ChatRequest second = chatRequest();
        second.setMetadata(new java.util.LinkedHashMap<>(Map.of("b", "2", "a", "1")));

        assertEquals(
                ChatRequestFingerprint.nativeRequest(first, objectMapper).sha256(),
                ChatRequestFingerprint.nativeRequest(second, objectMapper).sha256());
    }

    @Test
    void infersScopeModeWhenNotDeclared() {
        ChatRequest withKeys = chatRequest();
        withKeys.setCollectionKeys(List.of("kb"));

        ChatRequestFingerprint.Result declared =
                ChatRequestFingerprint.nativeRequest(withKeys, objectMapper);
        assertTrue(declared.canonical().path("scope").path("mode").asText()
                .equals(CollectionScopeMode.SELECTED_COLLECTIONS.name()));

        ChatRequest plainish = chatRequest();
        ChatRequestFingerprint.Result fallback =
                ChatRequestFingerprint.nativeRequest(plainish, objectMapper);
        assertTrue(fallback.canonical().path("scope").path("mode").asText()
                .equals(CollectionScopeMode.CALLER_VISIBLE.name()));
    }

    @Test
    void plainModeMarksScopeNotApplicable() {
        ChatRequest request = chatRequest();
        request.setMode(ChatMode.PLAIN);

        ChatRequestFingerprint.Result result =
                ChatRequestFingerprint.nativeRequest(request, objectMapper);
        assertEquals("NOT_APPLICABLE",
                result.canonical().path("scope").path("mode").asText());
    }

    @Test
    void blankSessionIdsResolveToAutoSession() {
        ChatRequest request = new ChatRequest("hello", "  ");

        ChatRequestFingerprint.Result result =
                ChatRequestFingerprint.nativeRequest(request, objectMapper);
        assertEquals("AUTO_SESSION",
                result.canonical().path("sessionId").asText());
    }

    @Test
    void openAiPlainModeRejectsDeclaredRetrievalScope() {
        OpenAiChatCompletionRequest request = new OpenAiChatCompletionRequest();
        request.setModel("rag-default");
        OpenAiChatCompletionRequest.RagOptions rag =
                new OpenAiChatCompletionRequest.RagOptions();
        rag.setMode(ChatMode.PLAIN);
        rag.setScope(new OpenAiChatCompletionRequest.Scope());
        request.setRag(rag);

        RagException error = assertThrows(RagException.class,
                () -> ChatRequestFingerprint.openAiRequest(request, objectMapper));
        assertEquals(ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                error.getErrorCodeEnum());
    }

    @Test
    void openAiPlainModeRejectsCollectionHeaderKeys() {
        OpenAiChatCompletionRequest request = new OpenAiChatCompletionRequest();
        request.setModel("rag-default");
        // PLAIN 模式需显式声明（缺省为 KNOWLEDGE）。
        OpenAiChatCompletionRequest.RagOptions rag =
                new OpenAiChatCompletionRequest.RagOptions();
        rag.setMode(ChatMode.PLAIN);
        request.setRag(rag);

        RagException error = assertThrows(RagException.class,
                () -> ChatRequestFingerprint.openAiRequest(
                        request, objectMapper, List.of("kb")));
        assertEquals(ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                error.getErrorCodeEnum());
    }

    @Test
    void openAiHeaderKeysMustBeSingleNonBlankValues() {
        OpenAiChatCompletionRequest request = new OpenAiChatCompletionRequest();
        request.setModel("rag-default");
        OpenAiChatCompletionRequest.RagOptions rag =
                new OpenAiChatCompletionRequest.RagOptions();
        rag.setMode(ChatMode.KNOWLEDGE);
        request.setRag(rag);

        RagException comma = assertThrows(RagException.class,
                () -> ChatRequestFingerprint.openAiRequest(
                        request, objectMapper, List.of("a,b")));
        assertEquals(ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                comma.getErrorCodeEnum());

        assertThrows(RagException.class,
                () -> ChatRequestFingerprint.openAiRequest(
                        request, objectMapper, List.of(" ")));
    }

    @Test
    void openAiMessagesAndModesAreNormalizedIntoTheFingerprint() {
        OpenAiChatCompletionRequest request = new OpenAiChatCompletionRequest();
        request.setModel(null);
        OpenAiChatCompletionRequest.Message message =
                new OpenAiChatCompletionRequest.Message();
        message.setRole("USER");
        message.setContent(objectMapper.valueToTree("hello"));
        request.setMessages(List.of(message));
        OpenAiChatCompletionRequest.RagOptions rag =
                new OpenAiChatCompletionRequest.RagOptions();
        rag.setMode(ChatMode.KNOWLEDGE);
        rag.setMemory("default");
        request.setRag(rag);

        ChatRequestFingerprint.Result result =
                ChatRequestFingerprint.openAiRequest(request, objectMapper);

        // 角色小写、memory 大写、model 缺省为 DEFAULT。
        assertEquals("user", result.canonical()
                .path("inputMessages").get(0).path("role").asText());
        assertEquals("DEFAULT",
                result.canonical().path("declaredModelIdentifier").asText());
        assertEquals("DEFAULT",
                result.canonical().path("memoryMode").asText());
    }
}
