package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.ApiKeyManagementService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatAuthorizationService 快照不变量长尾（Batch 497，JaCoCo 驱
 * 动）：verifyReplay 对篡改/损坏快照的不变量拒绝矩阵（非布尔
 * unassigned 标志、NOT_APPLICABLE 与脏字段互斥、非数组/非正整数
 * 集合列表、非对象来源行、ANY_COLLECTION 非正集合禁止），以及
 * snapshot() 对序列化期运行时异常与非法 documentId 的降级。
 */
class ChatAuthorizationSnapshotInvariantTailTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RagDocumentRepository documentRepository =
            mock(RagDocumentRepository.class);
    private final ApiKeyManagementService apiKeyManagementService =
            mock(ApiKeyManagementService.class);
    private final ChatAuthorizationService service =
            new ChatAuthorizationService(
                    objectMapper, documentRepository, apiKeyManagementService);

    private static String snapshot(String scopeMode, String callerAccessMode,
                                   String selectedIds, String allowList,
                                   String unassignedFlag, String sources,
                                   String observed) {
        return """
                {
                  "authorizationSnapshotVersion": 1,
                  "scopeMode": "%s",
                  "callerAccessMode": "%s",
                  "effectiveSelectedCollectionIds": %s,
                  "callerAllowList": %s,
                  "unassignedDocumentsAllowed": %s,
                  "sourceDocumentCollectionSnapshot": %s,
                  "sourceCollectionIdsObserved": %s
                }
                """.formatted(scopeMode, callerAccessMode, selectedIds,
                allowList, unassignedFlag, sources, observed);
    }

    private ChatPrincipal dbPrincipal() {
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(adminPrincipal());
        RagDocument existing = new RagDocument();
        existing.setId(41L);
        existing.setCollectionId(7L);
        existing.setEnabled(Boolean.TRUE);
        when(documentRepository.findById(41L))
                .thenReturn(java.util.Optional.of(existing));
        return new ChatPrincipal("db:principal-1", "DATABASE_API_KEY", false);
    }

    private AuthenticatedApiPrincipal adminPrincipal() {
        return new AuthenticatedApiPrincipal(
                "rag_p_current", "rag_k_current_v1", 1,
                "DATABASE_API_KEY", ApiKeyRole.ADMIN, null,
                LocalDateTime.now().plusYears(1), 1L, null,
                List.of("RAG_READ"));
    }

    private ChatTurnOperation operation(String authorizationSnapshot) {
        java.time.Instant now = java.time.Instant.now();
        return new ChatTurnOperation(
                1L, "db:principal-1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                1, "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.SUCCEEDED,
                null, null, 1, 1L, 1,
                "{\"executionSnapshotVersion\":1}",
                "{\"answer\":\"stable\"}", null, null,
                authorizationSnapshot, now, now, now);
    }

    private RagException invokeVerifyReplayExpectingInvalid(String json) {
        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(json), dbPrincipal()));
        assertEquals(ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
        return error;
    }

    @Test
    void nonBooleanUnassignedFlagIsInvalid() {
        RagException error = invokeVerifyReplayExpectingInvalid(snapshot(
                "SELECTED_COLLECTIONS", "UNRESTRICTED", "[7]", "[]",
                "\"yes\"", "[]", "[]"));
        assertTrue(error.getMessage() != null);
    }

    @Test
    void notApplicableScopeWithPollutedFieldsIsInvalid() {
        // NOT_APPLICABLE 范围不允许任何已选集合/白名单/来源证据。
        RagException error = invokeVerifyReplayExpectingInvalid(snapshot(
                "NOT_APPLICABLE", "NOT_APPLICABLE", "[7]", "[]",
                "false", "[]", "[]"));
        assertTrue(error.getMessage() != null);
    }

    @Test
    void notApplicableCallerWithScopedScopeIsInvalid() {
        RagException error = invokeVerifyReplayExpectingInvalid(snapshot(
                "SELECTED_COLLECTIONS", "NOT_APPLICABLE", "[7]", "[]",
                "false", "[]", "[]"));
        assertTrue(error.getMessage() != null);
    }

    @Test
    void nonArraySelectedCollectionIdsIsInvalid() {
        RagException error = invokeVerifyReplayExpectingInvalid(snapshot(
                "SELECTED_COLLECTIONS", "UNRESTRICTED", "{\"k\":7}", "[]",
                "false", "[]", "[]"));
        assertTrue(error.getMessage() != null);
    }

    @Test
    void nonPositiveIdInCallerAllowListIsInvalid() {
        RagException error = invokeVerifyReplayExpectingInvalid(snapshot(
                "SELECTED_COLLECTIONS", "RESTRICTED", "[7]", "[0]",
                "false", "[]", "[]"));
        assertTrue(error.getMessage() != null);
    }

    @Test
    void nonObjectSourceRowIsInvalid() {
        RagException error = invokeVerifyReplayExpectingInvalid(snapshot(
                "SELECTED_COLLECTIONS", "UNRESTRICTED", "[7]", "[]",
                "false", "[1]", "[]"));
        assertTrue(error.getMessage() != null);
    }

    @Test
    void anyCollectionScopeWithPositiveSourceCollectionPasses() {
        // ANY_COLLECTION 只要求来源集合 id 为正；0 会被 observed
        // 的正整数校验先行拒绝（防御顺序），此处验证放行路径。
        service.verifyReplay(operation(snapshot(
                "ANY_COLLECTION", "UNRESTRICTED", "[]", "[]",
                "false",
                "[{\"documentId\":41,\"collectionId\":7}]",
                "[7]")), dbPrincipal());
    }

    // ── snapshot() 序列化降级 ─────────────────────────────────────

    private ChatCommand command() {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                ChatMode.PLAIN, null, null, null, null, null,
                Map.of(), null, null, null, null, null);
    }

    @Test
    void snapshotRejectsSourceWithNonNumericDocumentId() {
        ChatSource source = new ChatSource();
        source.setDocumentId("not-a-number");
        ChatResponse response = ChatResponse.builder()
                .sources(List.of(source))
                .build();

        RagException error = assertThrows(RagException.class,
                () -> service.snapshot(command(), response));
        assertEquals(ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void snapshotWrapsUnexpectedRuntimeFailures() {
        ChatSource source = mock(ChatSource.class);
        when(source.getDocumentId())
                .thenThrow(new IllegalStateException("source blew up"));
        ChatResponse response = ChatResponse.builder()
                .sources(List.of(source))
                .build();

        RagException error = assertThrows(RagException.class,
                () -> service.snapshot(command(), response));
        assertEquals(ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
        assertTrue(error.getCause() instanceof IllegalStateException);
    }
}
