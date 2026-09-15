package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.repository.ChatTurnOperationRepository;
import com.springairag.core.chat.ChatObservabilityService;
import com.springairag.core.exception.RagException;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.ApiKeyManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatTurnOperationService 执行快照序列化长尾（Batch 444）：
 * executionSnapshot 的 DEFAULT 声明归一与尺寸上限、
 * snapshotCandidates 的版本/空链/空白候选拒绝、
 * resolvedCandidateRefs 的执行器缺失与空链拒绝、
 * declaredModelIdentifier 的回退语义。
 */
class ChatTurnOperationSnapshotTailTest {

    private ChatTurnOperationRepository repository;
    private ChatObservabilityService observability;
    private ChatExecutionService executionService;
    private RagChatProperties chatProperties;
    private ChatTurnOperationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        observability = mock(ChatObservabilityService.class);
        executionService = mock(ChatExecutionService.class);
        chatProperties = new RagChatProperties();
        service = new ChatTurnOperationService(
                repository,
                new ObjectMapper(),
                chatProperties,
                new RagProperties(),
                new ChatAuthorizationService(
                        new ObjectMapper(),
                        mock(com.springairag.core.repository.RagDocumentRepository.class),
                        mock(ApiKeyManagementService.class)),
                observability,
                executionService);
    }

    private ChatCommand command() {
        return new ChatCommand(
                "question", "session-1", ChatPrincipal.local(),
                ChatPrincipal.local().memoryConversationId("session-1"),
                ChatMode.KNOWLEDGE, MemoryMode.SERVER, null, null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.3, true, false, 0.5, 0.5),
                java.util.Map.of());
    }

    private Method snapshotMethod() throws Exception {
        Method method = ChatTurnOperationService.class.getDeclaredMethod(
                "executionSnapshot", ChatCommand.class, String.class, List.class);
        method.setAccessible(true);
        return method;
    }

    private Method candidatesMethod() throws Exception {
        Method method = ChatTurnOperationService.class.getDeclaredMethod(
                "snapshotCandidates", ChatTurnOperation.class);
        method.setAccessible(true);
        return method;
    }

    private Method refsMethod() throws Exception {
        Method method = ChatTurnOperationService.class.getDeclaredMethod(
                "resolvedCandidateRefs", ChatCommand.class, boolean.class);
        method.setAccessible(true);
        return method;
    }

    private Method declaredMethod() throws Exception {
        Method method = ChatTurnOperationService.class.getDeclaredMethod(
                "declaredModelIdentifier",
                ChatTurnOperationService.Prepared.class);
        method.setAccessible(true);
        return method;
    }

    @Test
    void executionSnapshotSerializesFieldsAndNormalizesDefaultAlias()
            throws Exception {
        String json = (String) snapshotMethod().invoke(service,
                command(), null, List.of("acme/m1"));

        assertTrue(json.contains("\"declaredModelIdentifier\":\"DEFAULT\""));
        assertTrue(json.contains("\"resolvedCandidates\":[\"acme/m1\"]"));
        assertTrue(json.contains("\"mode\":\"KNOWLEDGE\""));
    }

    @Test
    void executionSnapshotRejectsOversizedPayload() throws Exception {
        chatProperties.getIdempotency().setExecutionSnapshotMaxBytes(64);
        Method method = snapshotMethod();
        try {
            method.invoke(service, command(), null, List.of("a"));
            throw new AssertionError("expected RagException");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertEquals(RagException.class, e.getCause().getClass());
        }
    }

    @Test
    void snapshotCandidatesRejectsWrongVersionEmptyChainAndBlankEntries()
            throws Exception {
        Method method = candidatesMethod();

        // 空快照 → List.of()。
        assertEquals(List.of(), method.invoke(service,
                new ChatTurnOperation(1, null, null, null, 1, null, null,
                        null, null, null, null, 1, 0L, 1, null, null,
                        null, null, null, null, null, null)));

        // 错误版本 → invalid。
        ChatTurnOperation badVersion = operationWithSnapshot(
                "{\"executionSnapshotVersion\":2,\"resolvedCandidates\":[\"a\"]}");
        assertCauseInvalid(() -> method.invoke(service, badVersion));

        // 空候选链 → invalid。
        ChatTurnOperation emptyChain = operationWithSnapshot(
                "{\"executionSnapshotVersion\":1,\"resolvedCandidates\":[]}");
        assertCauseInvalid(() -> method.invoke(service, emptyChain));

        // 空白候选 → invalid。
        ChatTurnOperation blankCandidate = operationWithSnapshot(
                "{\"executionSnapshotVersion\":1,\"resolvedCandidates\":[\" \"]}");
        assertCauseInvalid(() -> method.invoke(service, blankCandidate));

        // 合法候选 → 透出。
        ChatTurnOperation valid = operationWithSnapshot(
                "{\"executionSnapshotVersion\":1,\"resolvedCandidates\":[\"a/m1\"]}");
        assertEquals(List.of("a/m1"), method.invoke(service, valid));
    }

    private void assertCauseInvalid(ThrowingInvoke call) throws Exception {
        try {
            call.invoke();
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertEquals(RagException.class, e.getCause().getClass());
            return;
        }
        throw new AssertionError("expected RagException");
    }

    private interface ThrowingInvoke {
        Object invoke() throws Exception;
    }

    private ChatTurnOperation operationWithSnapshot(String executionSnapshot) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, "db:principal-1", "key-hash", "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.SUCCEEDED, UUID.randomUUID(), null,
                1, 0L, 1,
                executionSnapshot, null, null, null, null,
                now, now, null);
    }

    @Test
    void resolvedCandidateRefsRequireExecutorAndNonEmptyChain()
            throws Exception {
        // executionService 缺失 → invalid。
        ChatTurnOperationService noExecutor = new ChatTurnOperationService(
                repository,
                new ObjectMapper(),
                chatProperties,
                new RagProperties(),
                new ChatAuthorizationService(
                        new ObjectMapper(),
                        mock(com.springairag.core.repository.RagDocumentRepository.class),
                        mock(ApiKeyManagementService.class)),
                observability,
                null);
        Method refs = refsMethod();
        // executionService 缺失的实例 → 调用即 invalid。
        try {
            refs.invoke(noExecutor, command(), true);
            throw new AssertionError("expected RagException");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertEquals(RagException.class, e.getCause().getClass());
        }

        // 空候选链 → invalid。
        when(executionService.resolveCandidateRefs(any(), eq(true)))
                .thenReturn(List.of());
        try {
            refs.invoke(service, command(), true);
            throw new AssertionError("expected RagException");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertEquals(RagException.class, e.getCause().getClass());
        }
    }

    @Test
    void declaredModelIdentifierFallsBackToDefault() throws Exception {
        Method declared = declaredMethod();

        assertEquals("DEFAULT", declared.invoke(service,
                (Object) null));
        assertEquals("DEFAULT", declared.invoke(service,
                new ChatTurnOperationService.Prepared(
                        ChatPrincipal.local(), "k", "f", null, null, true)));

        var canonical = new ObjectMapper().readTree(
                "{\"declaredModelIdentifier\":\"alias-m\"}");
        assertEquals("alias-m", declared.invoke(service,
                new ChatTurnOperationService.Prepared(
                        ChatPrincipal.local(), "k", "f", canonical, null, true)));
    }
}
