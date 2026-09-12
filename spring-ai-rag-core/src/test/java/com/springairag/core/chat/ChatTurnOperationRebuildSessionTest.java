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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 4 参 claim → claimNew → withEffectiveSession（Batch 320）：
 * 新 key 首次领取走会话快速通道（ChatCommand 构造器已保证
 * sessionId 合法），插入携带原会话；unkeyed prepared 直接短路。
 * 要点：withEffectiveSession 的"非法会话重建"分支为防御性死代
 * 码——ChatCommand 紧凑构造器经 SessionIdValidator.resolve 对非
 * 法值抛错、对空值生成 UUID，合法不变式在构造期即成立。
 */
class ChatTurnOperationRebuildSessionTest {

    private static final String PRINCIPAL_ID = "local:auth-disabled";
    private static final String KEY_HASH =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

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
        // 服务经 10 参 insert 重载写入（执行快照 + 授权快照）；
        // 全 untyped 匹配器避免重载/装箱匹配偏差。
        when(repository.insert(
                any(), any(), any(), any(), any(), any(),
                any(), anyInt(), any(), any()))
                .thenReturn(true);
    }

    private ChatTurnOperationService.Prepared prepared() {
        return new ChatTurnOperationService.Prepared(
                principal, KEY_HASH, "fp-hash", null, null, true);
    }

    private ChatCommand command(String sessionId) {
        return new ChatCommand(
                "hello", sessionId, principal, null,
                ChatMode.KNOWLEDGE, null, null, null,
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
    void claimNewInsertsWithTheCommandSessionVerbatim() {
        ChatTurnOperation newOp = insertedOperation("clean-session");
        // 首次 find（claim 判新）返回 null → claimNew；insert 成功后
        // 的第二次 find 返回已插入行。
        when(repository.find(PRINCIPAL_ID, KEY_HASH))
                .thenReturn(null)
                .thenReturn(newOp);

        ChatTurnOperationService.Claim claim = service.claim(
                prepared(), command("clean-session"),
                ChatTurnOperation.Transport.NATIVE_JSON, false);

        assertSame(newOp, claim.operation());
        assertFalse(claim.replay());
        ArgumentCaptor<String> sessionCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(repository).insert(
                eq(PRINCIPAL_ID), eq(KEY_HASH), eq("fp-hash"),
                sessionCaptor.capture(), any(UUID.class), any(),
                any(UUID.class), anyInt(), any(), any());
        assertEquals("clean-session", sessionCaptor.getValue());
    }

    @Test
    void unkeyedPreparedShortCircuitsWithoutRepositoryReads() {
        ChatTurnOperationService.Prepared unkeyed =
                new ChatTurnOperationService.Prepared(
                        principal, null, "fp-hash", null, null, false);

        ChatTurnOperationService.Claim claim = service.claim(
                unkeyed,
                command("any-session"),
                ChatTurnOperation.Transport.NATIVE_JSON,
                false);

        Mockito.verifyNoInteractions(repository);
        assertFalse(claim.replay());
    }
}
