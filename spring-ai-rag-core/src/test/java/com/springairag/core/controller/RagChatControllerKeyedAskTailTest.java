package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatSessionCoordinator;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagChatController keyed /ask 长尾（Batch 496，JaCoCo 驱动）：
 * 幂等 mapper/execution 未配置的 IDEMPOTENCY_DISABLED 拒绝、keyed
 * JSON 成功链（prepareForOperation → completePrepared → finalize →
 * idempotentResponse 双头）、inspect 命中重放、claim 后重放（快照
 * 映射分支）、执行失败 fail + 租约释放，以及回放快照 DEFAULT 模型
 * 回退。
 */
class RagChatControllerKeyedAskTailTest {

    private static final UUID TURN_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");

    private RagChatService ragChatService;
    private ChatTurnOperationService turnOperationService;
    private ChatCommandMapper commandMapper;
    private ChatExecutionService executionService;
    private ChatSessionCoordinator coordinator;
    private ChatSessionCoordinator.LeaseHandle lease;
    private RagChatController controller;
    private MockHttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        turnOperationService = mock(ChatTurnOperationService.class);
        commandMapper = mock(ChatCommandMapper.class);
        executionService = mock(ChatExecutionService.class);
        coordinator = mock(ChatSessionCoordinator.class);
        lease = mock(ChatSessionCoordinator.LeaseHandle.class);
        controller = new RagChatController(
                ragChatService,
                mock(com.springairag.core.repository.RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                new RagSseProperties(),
                mock(CollectionRetrievalScopeResolver.class),
                mock(AuditLogService.class));
        controller.configureTurnOperationService(turnOperationService);
        controller.configureSessionCoordinator(coordinator);
        controller.configureModeAwareExecution(commandMapper, executionService);
        controller.configureObjectMapper(new ObjectMapper());
        httpRequest = new MockHttpServletRequest("POST", "/ask");
        httpRequest.addHeader("Idempotency-Key",
                UUID.randomUUID().toString());
    }

    @AfterEach
    void tearDown() {
        org.springframework.web.context.request.RequestContextHolder
                .resetRequestAttributes();
    }

    private ChatTurnOperationService.Prepared keyedPrepared(
            ChatTurnOperation operation) {
        return new ChatTurnOperationService.Prepared(
                ChatPrincipal.local(), "key-hash", "fp-hash", null,
                operation, true);
    }

    private ChatTurnOperation operation(String executionSnapshot) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, ChatPrincipal.local().id(), "key-hash", "fp-hash", 1,
                "session-1", TURN_ID,
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), now.plusSeconds(60),
                1, 1L, 1,
                executionSnapshot, null, null, null, "{}",
                now, now, null);
    }

    private ChatTurnOperationService.Claim claimWithLease(
            ChatTurnOperation operation) throws Exception {
        Constructor<ChatTurnOperationService.Claim> ctor =
                ChatTurnOperationService.Claim.class.getDeclaredConstructor(
                        ChatTurnOperation.class, boolean.class,
                        ChatSessionCoordinator.LeaseHandle.class);
        ctor.setAccessible(true);
        return ctor.newInstance(operation, false, lease);
    }

    private ChatCommand command() {
        return new ChatCommand(
                "问题", "session-1", ChatPrincipal.local(), null,
                ChatMode.PLAIN, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void keyedAskWithoutMapperIsRejected() {
        ChatCommand raw = command();
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(keyedPrepared(operation(null)));
        // mapper 未配置：重新构造仅带 execution 的 controller。
        RagChatController bare = new RagChatController(
                ragChatService,
                mock(com.springairag.core.repository.RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                new RagSseProperties(),
                mock(CollectionRetrievalScopeResolver.class),
                mock(AuditLogService.class));
        bare.configureTurnOperationService(turnOperationService);
        bare.configureModeAwareExecution(null, executionService);

        RagException error = assertThrows(RagException.class,
                () -> bare.ask(new ChatRequest("问题", "session-1"),
                        httpRequest));
        assertEquals(
                com.springairag.api.enums.ErrorCode.IDEMPOTENCY_DISABLED,
                error.getErrorCodeEnum());
    }

    @Test
    void keyedAskWithoutExecutionIsRejectedAndFailsOperation()
            throws Exception {
        RagChatController bare = new RagChatController(
                ragChatService,
                mock(com.springairag.core.repository.RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                new RagSseProperties(),
                mock(CollectionRetrievalScopeResolver.class),
                mock(AuditLogService.class));
        bare.configureTurnOperationService(turnOperationService);
        bare.configureSessionCoordinator(coordinator);
        // 仅 mapper，execution 缺失。
        bare.configureModeAwareExecution(commandMapper, null);

        ChatTurnOperation op = operation("{\"publicModelAlias\":\"a\"}");
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(keyedPrepared(op));
        when(turnOperationService.inspectExisting(any())).thenReturn(null);
        // op 带 executionSnapshot → 控制器走 mapFromExecutionSnapshot。
        when(commandMapper.mapFromExecutionSnapshot(
                any(), any(), anyString(), anyString()))
                .thenReturn(command());
        when(turnOperationService.claim(any(), any(), any(), anyBoolean()))
                .thenReturn(claimWithLease(op));
        when(turnOperationService.commandForClaim(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThrows(RagException.class,
                () -> bare.ask(new ChatRequest("问题", "session-1"),
                        httpRequest));
        verify(turnOperationService).fail(
                any(ChatTurnOperationService.Claim.class), any());
    }

    @Test
    void keyedAskJsonSuccessReturnsIdempotentHeaders() throws Exception {
        ChatTurnOperation op = operation("{\"publicModelAlias\":\"a\"}");
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(keyedPrepared(op));
        when(turnOperationService.inspectExisting(any())).thenReturn(null);
        // op 带 executionSnapshot → 控制器走 mapFromExecutionSnapshot。
        when(commandMapper.mapFromExecutionSnapshot(
                any(), any(), anyString(), anyString()))
                .thenReturn(command());
        when(turnOperationService.claim(any(), any(), any(), anyBoolean()))
                .thenReturn(claimWithLease(op));
        when(turnOperationService.commandForClaim(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(executionService.prepareForOperation(
                any(ChatCommand.class), any(), eq(false)))
                .thenReturn(mock(ChatExecutionService.PreparedExecution.class));
        when(turnOperationService.completePrepared(
                any(ChatTurnOperationService.Claim.class),
                any(ChatExecutionService.PreparedExecution.class)))
                .thenReturn(ChatResponse.builder().answer("ok").build());

        ResponseEntity<ChatResponse> response = controller.ask(
                new ChatRequest("问题", "session-1"), httpRequest);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(TURN_ID.toString(),
                response.getHeaders().getFirst("X-RAG-Turn-Id"));
        assertEquals("false",
                response.getHeaders().getFirst("X-RAG-Idempotent-Replay"));
        assertEquals("ok", response.getBody().getAnswer());
        verify(executionService).finalizePreparedOperation(any());
    }

    @Test
    void inspectReplayRespondsWithReplayHeader() {
        ChatTurnOperation op = operation("{\"publicModelAlias\":\"a\"}");
        ChatTurnOperationService.Prepared prepared = keyedPrepared(op);
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(prepared);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(new ChatTurnOperationService.Claim(op, true));
        when(turnOperationService.replay(
                any(ChatTurnOperationService.Claim.class)))
                .thenReturn(ChatResponse.builder().answer("cached").build());

        ResponseEntity<ChatResponse> response = controller.ask(
                new ChatRequest("问题", "session-1"), httpRequest);

        assertEquals("true",
                response.getHeaders().getFirst("X-RAG-Idempotent-Replay"));
        assertEquals("cached", response.getBody().getAnswer());
        verify(turnOperationService).replay(
                any(ChatTurnOperationService.Claim.class));
    }

    @Test
    void claimReplayAfterSnapshotMappingRespondsFromSnapshot() {
        ChatTurnOperation op = operation("{\"publicModelAlias\":\"a\"}");
        ChatTurnOperationService.Prepared prepared = keyedPrepared(op);
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(prepared);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(null);
        when(commandMapper.mapFromExecutionSnapshot(
                any(ChatRequest.class), any(),
                eq("session-1"), eq(op.executionSnapshot())))
                .thenReturn(command());
        when(turnOperationService.claim(any(), any(), any(), anyBoolean()))
                .thenReturn(new ChatTurnOperationService.Claim(op, true));
        when(turnOperationService.replay(
                any(ChatTurnOperationService.Claim.class)))
                .thenReturn(ChatResponse.builder().answer("snapshot").build());

        ResponseEntity<ChatResponse> response = controller.ask(
                new ChatRequest("问题", "session-1"), httpRequest);

        assertEquals("snapshot", response.getBody().getAnswer());
        verify(executionService, org.mockito.Mockito.never())
                .prepareForOperation(any(), any(), anyBoolean());
    }

    @Test
    void keyedExecutionFailureFailsOperationAndReleasesLease()
            throws Exception {
        ChatTurnOperation op = operation("{\"publicModelAlias\":\"a\"}");
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(keyedPrepared(op));
        when(turnOperationService.inspectExisting(any())).thenReturn(null);
        // op 带 executionSnapshot → 控制器走 mapFromExecutionSnapshot。
        when(commandMapper.mapFromExecutionSnapshot(
                any(), any(), anyString(), anyString()))
                .thenReturn(command());
        when(turnOperationService.claim(any(), any(), any(), anyBoolean()))
                .thenReturn(claimWithLease(op));
        when(turnOperationService.commandForClaim(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        IllegalStateException failure =
                new IllegalStateException("prepare failed");
        when(executionService.prepareForOperation(
                any(ChatCommand.class), any(), eq(false))).thenThrow(failure);

        assertThrows(IllegalStateException.class,
                () -> controller.ask(
                        new ChatRequest("问题", "session-1"), httpRequest));
        verify(turnOperationService).fail(
                any(ChatTurnOperationService.Claim.class), same(failure));
        verify(coordinator).release(lease);
    }
}
