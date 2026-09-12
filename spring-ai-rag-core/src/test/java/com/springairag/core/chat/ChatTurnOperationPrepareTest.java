package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * prepare 的幂等键准备矩阵（Batch 327）：缺键 → disabled、开关
 * 关闭/缺指纹/键换请求复用三类拒绝、命中同指纹返回带既有操作
 * 的 keyed 准备、新键返回无操作 keyed 准备。
 */
class ChatTurnOperationPrepareTest {

    private static final String PRINCIPAL_ID = "local:auth-disabled";

    private ChatTurnOperationRepository repository;
    private RagProperties ragProperties;
    private RagChatProperties chatProperties;
    private ChatPrincipal principal;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        ragProperties = new RagProperties();
        chatProperties = new RagChatProperties();
        principal = ChatPrincipal.local();
    }

    private ChatTurnOperationService service() {
        ChatExecutionService executionService = mock(ChatExecutionService.class);
        Mockito.lenient().when(executionService.resolveCandidateRefs(
                Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(List.of("vendor/model"));
        return new ChatTurnOperationService(
                repository,
                new ObjectMapper(),
                chatProperties,
                ragProperties,
                mock(ChatAuthorizationService.class),
                mock(ChatObservabilityService.class),
                executionService);
    }

    private ChatRequestFingerprint.Result fingerprint(String seed) {
        return ChatRequestFingerprint.openAiRequest(
                fingerprintRequest(seed), new ObjectMapper());
    }

    private com.springairag.api.openai.OpenAiChatCompletionRequest
            fingerprintRequest(String content) {
        try {
            return new ObjectMapper().readValue(
                    "{\"messages\":[{\"role\":\"user\",\"content\":"
                            + "\"" + content + "\"}]}",
                    com.springairag.api.openai.OpenAiChatCompletionRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ChatTurnOperation existingOperation(String fingerprintSha) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, PRINCIPAL_ID,
                "hash", fingerprintSha, 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), now.plusSeconds(60),
                1, 0L, 1,
                null, null, null, null, "{}",
                now, now, null);
    }

    @Test
    void missingKeyReturnsDisabledPrepared() {
        var prepared = service().prepare(principal, null, fingerprint("a"));
        assertFalse(prepared.keyed());
        prepared = service().prepare(principal, List.of(), fingerprint("a"));
        assertFalse(prepared.keyed());
        // disabled 准备不查询仓储。
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void disabledIdempotencyRejected() {
        chatProperties.getIdempotency().setEnabled(false);

        RagException error = assertThrowsRag(() -> service().prepare(
                principal, List.of("key-1"), fingerprint("a")));

        assertEquals(ErrorCode.IDEMPOTENCY_DISABLED, error.getErrorCodeEnum());
    }

    @Test
    void missingFingerprintRejected() {
        RagException error = assertThrowsRag(() -> service().prepare(
                principal, List.of("key-1"), null));

        assertEquals(ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void reusedKeyWithDifferentFingerprintRejected() {
        when(repository.find(anyString(), anyString()))
                .thenReturn(existingOperation("different-fingerprint"));

        RagException error = assertThrowsRag(() -> service().prepare(
                principal, List.of("key-1"), fingerprint("a")));

        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                error.getErrorCodeEnum());
    }

    @Test
    void matchingFingerprintReturnsKeyedPreparedWithOperation() {
        ChatRequestFingerprint.Result fingerprint = fingerprint("a");
        ChatTurnOperation existing =
                existingOperation(fingerprint.sha256());
        when(repository.find(anyString(), anyString()))
                .thenReturn(existing);

        var prepared = service().prepare(
                principal, List.of("  key-1  "), fingerprint);

        assertTrue(prepared.keyed());
        assertSame(existing, prepared.operation());
        assertEquals(fingerprint.sha256(), prepared.fingerprintHash());
        // 键经 OWSTrim + SHA-256 归一。
        assertEquals(
                IdempotencyKeyValidator.hash("key-1"),
                prepared.keyHash());
        assertSame(fingerprint.canonical(), prepared.canonical());
    }

    @Test
    void freshKeyReturnsKeyedPreparedWithoutOperation() {
        when(repository.find(anyString(), anyString())).thenReturn(null);
        ChatRequestFingerprint.Result fingerprint = fingerprint("b");

        var prepared = service().prepare(
                principal, List.of("key-new"), fingerprint);

        assertTrue(prepared.keyed());
        assertNull(prepared.operation());
        assertEquals(fingerprint.sha256(), prepared.fingerprintHash());
        assertEquals(IdempotencyKeyValidator.hash("key-new"),
                prepared.keyHash());
    }

    private RagException assertThrowsRag(Runnable action) {
        try {
            action.run();
        } catch (RagException error) {
            return error;
        }
        throw new AssertionError("Expected RagException was not thrown");
    }
}
