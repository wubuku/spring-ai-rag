package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * withEffectiveSession 的会话 ID 规范化语义：非法 sessionId 在插入
 * 前被替换为合法 UUID；合法 sessionId 原样保留。
 */
class ChatTurnOperationEffectiveSessionTest {

    private static final String PRINCIPAL_ID = "local:auth-disabled";
    private static final String KEY_HASH =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private ChatTurnOperationRepository repository;
    private ChatTurnOperationService service;
    private ChatPrincipal principal;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        ChatExecutionService executionService = mock(ChatExecutionService.class);
        Mockito.lenient().when(executionService.resolveCandidateRefs(
                any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(List.of("vendor/model"));
        service = new ChatTurnOperationService(
                repository,
                new ObjectMapper(),
                new RagChatProperties(),
                new RagProperties(),
                mock(ChatAuthorizationService.class),
                mock(ChatObservabilityService.class),
                executionService);
        principal = ChatPrincipal.local();
    }

    private ChatTurnOperationService.Prepared prepared() {
        return new ChatTurnOperationService.Prepared(
                principal, KEY_HASH, "fp-hash", null, null, true);
    }

    private ChatCommand command(String sessionId) {
        return new ChatCommand(
                "hello", sessionId, principal, null,
                com.springairag.api.enums.ChatMode.KNOWLEDGE, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private ChatTurnOperation insertedOperation(String sessionId) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, PRINCIPAL_ID, KEY_HASH, "fp-hash", 1,
                sessionId, UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), now.plusSeconds(60),
                1, 0L, 1,
                null, null, null, null, "{}",
                now, now, null);
    }

    @Test
    void invalidSessionIdIsReplacedWithUuidBeforeInsert() {
        ChatTurnOperation newOp = insertedOperation("normalized-session");
        when(repository.insert(
                anyString(), anyString(), anyString(), anyString(),
                any(UUID.class), any(), any(UUID.class), anyInt(), anyString()))
                .thenReturn(true);
        when(repository.find(PRINCIPAL_ID, KEY_HASH)).thenReturn(newOp);

        // 非法 sessionId（含空格与感叹号）→ 触发 UUID 规范化。
        ChatTurnOperationService.Claim claim = service.claim(
                prepared(), "bad session!!",
                ChatTurnOperation.Transport.NATIVE_JSON);

        assertFalse(claim.replay());
        assertSame(newOp, claim.operation());

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).insert(
                eq(PRINCIPAL_ID), eq(KEY_HASH), eq("fp-hash"),
                sessionCaptor.capture(), any(UUID.class), any(),
                any(UUID.class), anyInt(), anyString());
        // 规范化后的 sessionId 必须是合法 UUID。
        UUID normalized = UUID.fromString(sessionCaptor.getValue());
        assertEquals(normalized.toString(), sessionCaptor.getValue());
    }

    @Test
    void validSessionIdIsPreservedVerbatim() {
        ChatTurnOperation newOp = insertedOperation("my-valid-session");
        when(repository.insert(
                anyString(), anyString(), anyString(), anyString(),
                any(UUID.class), any(), any(UUID.class), anyInt(), anyString()))
                .thenReturn(true);
        when(repository.find(PRINCIPAL_ID, KEY_HASH)).thenReturn(newOp);

        service.claim(prepared(), "my-valid-session",
                ChatTurnOperation.Transport.NATIVE_JSON);

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).insert(
                eq(PRINCIPAL_ID), eq(KEY_HASH), eq("fp-hash"),
                sessionCaptor.capture(), any(UUID.class), any(),
                any(UUID.class), anyInt(), anyString());
        assertEquals("my-valid-session", sessionCaptor.getValue());
    }

    @Test
    void withEffectiveSessionGeneratesUuidForInvalidSession() {
        // 直接调用 3 参 claim 的 claimNew 路径验证 withEffectiveSession
        // 将非法会话替换为合法 UUID。
        ChatTurnOperation newOp = insertedOperation("normalized-session");
        when(repository.insert(
                anyString(), anyString(), anyString(), anyString(),
                any(UUID.class), any(), any(UUID.class), anyInt(), anyString()))
                .thenReturn(true);
        when(repository.find(PRINCIPAL_ID, KEY_HASH)).thenReturn(newOp);

        // 含连续两个空格的 sessionId 不匹配 [A-Za-z0-9_-]+ 模式。
        String invalidSession = "ab  cd";
        assertFalse(invalidSession.contains("!"));

        ChatTurnOperationService.Claim claim = service.claim(
                prepared(), invalidSession,
                ChatTurnOperation.Transport.NATIVE_JSON);

        assertSame(newOp, claim.operation());
        // insert 收到的 sessionId 是 UUID（≠ 原始非法值）。
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(repository, Mockito.atLeastOnce()).insert(
                eq(PRINCIPAL_ID), eq(KEY_HASH), eq("fp-hash"),
                captor.capture(), any(UUID.class), any(),
                any(UUID.class), anyInt(), anyString());
        assertNotEquals(invalidSession, captor.getValue());
        // 合法 UUID 格式：无空格且可反解。
        UUID parsed = UUID.fromString(captor.getValue());
        assertEquals(parsed.toString(), captor.getValue());
    }
}
