package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 原生 claim 的跨会话租约调整（acquireSessionLease 的 adjusted 命
 * 令分支）与 stableSnapshot 的来源深拷贝语义（字段逐项复制、unkeyed
 * 透传零拷贝）。
 */
class ChatTurnOperationStableSourceLeaseTest {

    private static final String PRINCIPAL_ID = "local:auth-disabled";
    private static final String KEY_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    /** 记录已执行 SQL 的 JdbcTemplate 桩。 */
    private static class StubJdbc extends JdbcTemplate {
        final List<String> seenSql = new java.util.ArrayList<>();

        @Override
        public int update(String sql, Object... args) {
            seenSql.add(sql);
            return 1;
        }
    }

    private ChatTurnOperationRepository repository;
    private ChatSessionCoordinator coordinator;
    private StubJdbc jdbc;
    private ChatTurnOperationService service;
    private ChatPrincipal principal;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        jdbc = new StubJdbc();
        coordinator = new ChatSessionCoordinator(
                jdbc,
                mock(com.springairag.core.repository.RagChatHistoryRepository.class),
                mock(org.springframework.ai.chat.memory.repository.jdbc
                        .JdbcChatMemoryRepository.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                new RagProperties());
        service = new ChatTurnOperationService(
                repository,
                new ObjectMapper(),
                new RagChatProperties(),
                new RagProperties(),
                mock(ChatAuthorizationService.class),
                mock(ChatObservabilityService.class),
                mock(ChatExecutionService.class));
        service.setSessionCoordinator(coordinator);
        principal = ChatPrincipal.local();
    }

    private ChatTurnOperation operation(String sessionId, boolean expired) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, PRINCIPAL_ID, KEY_HASH, "fp-hash", 1,
                sessionId, UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(),
                expired ? now.minusSeconds(60) : now.plusSeconds(600),
                1, 0L, 1,
                "{\"executionSnapshotVersion\":1}", null, null, null, "{}",
                now, now, null);
    }

    private ChatTurnOperationService.Prepared prepared(ChatTurnOperation op) {
        return new ChatTurnOperationService.Prepared(
                principal, KEY_HASH, "fp-hash", null, op, true);
    }

    private ChatCommand command(String sessionId) {
        return new ChatCommand(
                "hello", sessionId, principal, null,
                ChatMode.KNOWLEDGE, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private ChatSource source() {
        ChatSource source = new ChatSource();
        source.setCitationId("c-1");
        source.setDocumentId("doc-9");
        source.setChunkIndex(2);
        source.setTitle("Doc Title");
        source.setChunkText("chunk body");
        source.setScore(0.87);
        source.setMetadata(Map.of("page", 3));
        return source;
    }

    private DocumentMutationResponse mutation() {
        DocumentLifecycleResponse lifecycle = new DocumentLifecycleResponse(
                "LIVE", "SEARCHABLE", "COMPLETED", "COMPLETED", "profile",
                null, null, null, false);
        return new DocumentMutationResponse(
                42L, "UPDATED", 3L, 6, true, false, false,
                "SYNC_EMBEDDED", null, null, lifecycle);
    }

    @Test
    void reclaimWithCrossSessionOperationBuildsAdjustedCommandAndAcquiresLease() {
        ChatTurnOperation expired = operation("session-1", true);
        ChatTurnOperation reclaimed = operation("session-1", false);
        // 快照存在：回收时快照参数为 null（不覆盖库值）。
        when(repository.reclaim(
                any(ChatTurnOperation.class), any(UUID.class), anyInt(),
                isNull(), anyInt()))
                .thenReturn(reclaimed);
        when(repository.find(PRINCIPAL_ID, KEY_HASH)).thenReturn(reclaimed);

        // 命令会话（other-session）与操作会话（session-1）不同：
        // acquireSessionLease 走 adjusted 命令分支后成功获取租约。
        ChatTurnOperationService.Claim claim = service.claim(
                prepared(expired), command("other-session"),
                ChatTurnOperation.Transport.NATIVE_JSON, false);

        assertFalse(claim.replay());
        assertSame(reclaimed, claim.operation());
        assertTrue(jdbc.seenSql.stream()
                .anyMatch(sql -> sql.contains("INSERT INTO rag_chat_session_lease")));
    }

    @Test
    void completeDeepCopiesSourcesThroughStableSnapshot() {
        ChatTurnOperation operation = operation("session-1", false);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(operation, false);
        when(repository.completeSuccess(
                eq(operation), anyString(), anyString())).thenReturn(true);
        ChatSource source = source();
        ChatResponse response = ChatResponse.builder()
                .answer("answer")
                .sessionId("session-1")
                .sources(List.of(source))
                .metadata(Map.of("k", "v"))
                .build();
        ChatRequest request = new ChatRequest();
        request.setMode(ChatMode.KNOWLEDGE);

        ChatResponse result = service.complete(claim, response, request);

        // 深拷贝：来源为全新实例且字段逐项复制。
        assertNotSame(source, result.getSources().get(0));
        assertEquals("c-1", result.getSources().get(0).getCitationId());
        assertEquals("Doc Title", result.getSources().get(0).getTitle());
        assertEquals(0.87, result.getSources().get(0).getScore());
        assertEquals(Map.of("page", 3),
                result.getSources().get(0).getMetadata());
        assertEquals(operation.turnId().toString(), result.getTurnId());
    }

    @Test
    void unkeyedCompleteReturnsSameResponseInstance() {
        ChatTurnOperationService.Claim unkeyed =
                new ChatTurnOperationService.Claim(null, false);
        ChatResponse response = ChatResponse.builder()
                .answer("answer")
                .sources(List.of())
                .build();
        ChatRequest request = new ChatRequest();
        request.setMode(ChatMode.KNOWLEDGE);

        ChatResponse result = service.complete(unkeyed, response, request);

        // 无键控声明：直接透传同一实例（零拷贝语义）。
        assertSame(response, result);
    }
}
