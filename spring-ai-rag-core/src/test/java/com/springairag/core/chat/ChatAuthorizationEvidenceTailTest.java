package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 授权证据长尾（Batch 589，JaCoCo 驱动）：ANY_ASSIGNED 作用域快照、
 * 空来源容错、null 集合观察跳过、非法 documentId 拒绝、证据超限、
 * verifyReplay 对空操作/坏快照/字段形状/来源证据的失败闭合，以及
 * 未分配文档在 CALLER_VISIBLE + 非受限下的放行。
 */
class ChatAuthorizationEvidenceTailTest {

    private final RagDocumentRepository documentRepository =
            mock(RagDocumentRepository.class);
    private final com.springairag.core.service.ApiKeyManagementService
            apiKeyManagementService =
            mock(com.springairag.core.service.ApiKeyManagementService.class);
    private final ChatAuthorizationService service =
            new ChatAuthorizationService(
                    new ObjectMapper(), documentRepository,
                    apiKeyManagementService);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ─── snapshot 生成路径 ───────────────────────────────────────────

    private ChatCommand knowledgeCommand(RetrievalScope scope) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "question",
                "session-1",
                principal,
                principal.memoryConversationId("session-1"),
                ChatMode.KNOWLEDGE,
                MemoryMode.SERVER,
                null,
                null,
                scope,
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of());
    }

    private void useUnrestrictedRequestContext() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest("POST", "/chat")));
    }

    private ChatSource source(String documentId) {
        ChatSource source = new ChatSource();
        source.setCitationId("S-" + documentId);
        source.setDocumentId(documentId);
        source.setChunkIndex(0);
        return source;
    }

    private ChatResponse responseOf(List<ChatSource> sources) {
        ChatResponse response = new ChatResponse();
        response.setAnswer("answer");
        response.setSources(sources);
        return response;
    }

    private RagDocument document(long id, Long collectionId) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setCollectionId(collectionId);
        document.setEnabled(true);
        return document;
    }

    @Test
    void anyAssignedScopeSnapshotMarksAnyCollection() {
        useUnrestrictedRequestContext();

        String json = service.snapshot(
                knowledgeCommand(RetrievalScope.anyAssigned(
                        List.of(), null)),
                null);

        assertTrue(json.contains("\"scopeMode\":\"ANY_COLLECTION\""));
        // ANY_COLLECTION 不放行未分配文档。
        assertTrue(json.contains("\"unassignedDocumentsAllowed\":false"));
    }

    @Test
    void snapshotToleratesNullSourcesList() {
        useUnrestrictedRequestContext();
        ChatResponse response = new ChatResponse();
        response.setAnswer("answer");
        response.setSources(null);

        String json = service.snapshot(
                knowledgeCommand(RetrievalScope.unscoped()), response);

        assertTrue(json.contains(
                "\"sourceDocumentCollectionSnapshot\":[]"));
    }

    @Test
    void snapshotObservationSkipsNullCollectionIds() throws Exception {
        useUnrestrictedRequestContext();
        when(documentRepository.findById(10L))
                .thenReturn(Optional.of(document(10L, null)));

        String json = service.snapshot(
                knowledgeCommand(RetrievalScope.unscoped()),
                responseOf(List.of(source("10"))));

        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        // collectionId 为 null 的来源不进入 observed 列表，派生值一致。
        assertEquals(List.of(), parsed.get("sourceCollectionIdsObserved"));
        assertEquals(1,
                ((List<?>) parsed.get("sourceDocumentCollectionSnapshot"))
                        .size());
    }

    @Test
    void snapshotRejectsNullOrUnparseableDocumentIds() {
        useUnrestrictedRequestContext();
        ChatSource nullId = source(null);
        ChatSource textId = source("not-a-number");
        ChatSource zeroId = source("0");

        for (ChatSource bad : List.of(nullId, textId, zeroId)) {
            RagException error = assertThrows(RagException.class,
                    () -> service.snapshot(
                            knowledgeCommand(RetrievalScope.unscoped()),
                            responseOf(List.of(bad))),
                    "documentId=" + bad.getDocumentId());
            assertEquals(
                    ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                    error.getErrorCodeEnum(),
                    "documentId=" + bad.getDocumentId());
        }
    }

    @Test
    void snapshotRejectsEvidenceAboveConfiguredSize() {
        useUnrestrictedRequestContext();
        // 2000 条来源 ≈ 74KB，超过 64KB 快照上限。
        List<ChatSource> sources = new ArrayList<>();
        for (long id = 1; id <= 2000; id++) {
            sources.add(source(Long.toString(id)));
        }
        when(documentRepository.findById(anyLong()))
                .thenAnswer(invocation -> Optional.of(
                        document(invocation.getArgument(0, Long.class), 1L)));

        RagException error = assertThrows(RagException.class,
                () -> service.snapshot(
                        knowledgeCommand(RetrievalScope.unscoped()),
                        responseOf(sources)));

        assertEquals(ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("exceeds configured size"));
    }

    // ─── verifyReplay 路径 ───────────────────────────────────────────

    private static AuthenticatedApiPrincipal policyFor(String allowedIds) {
        return new AuthenticatedApiPrincipal(
                "rag_p_current",
                "rag_k_current_v1",
                1,
                "DATABASE_API_KEY",
                ApiKeyRole.NORMAL,
                allowedIds,
                LocalDateTime.now().plusYears(1),
                1L,
                null,
                List.of("RAG_READ"));
    }

    private ChatTurnOperation operation(String authorizationSnapshot) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L,
                "db:principal-1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                1,
                "session-1",
                UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.SUCCEEDED,
                null,
                null,
                1,
                1L,
                1,
                "{\"executionSnapshotVersion\":1}",
                "{\"answer\":\"stable\"}",
                null,
                null,
                authorizationSnapshot,
                now,
                now,
                now);
    }

    /** 手工构造快照 JSON，可注入来源证据与观察集合。 */
    private static String snapshotWith(
            String scopeMode,
            String callerAccessMode,
            String selectedIds,
            String allowList,
            String unassignedAllowed,
            String sources,
            String observed) {
        return """
                {"authorizationSnapshotVersion":1,
                 "scopeMode":"%s",
                 "callerAccessMode":"%s",
                 "effectiveSelectedCollectionIds":%s,
                 "callerAllowList":%s,
                 "unassignedDocumentsAllowed":%s,
                 "sourceDocumentCollectionSnapshot":%s,
                 "sourceCollectionIdsObserved":%s}
                """.formatted(scopeMode, callerAccessMode, selectedIds,
                allowList, unassignedAllowed, sources, observed)
                .replaceAll("\\s+", "");
    }

    private static String plainSnapshot(
            String scopeMode, String callerAccessMode,
            String selectedIds, String allowList,
            String unassignedAllowed) {
        return snapshotWith(scopeMode, callerAccessMode, selectedIds,
                allowList, unassignedAllowed, "[]", "[]");
    }

    @Test
    void verifyReplayRejectsMissingOperationOrSnapshot() {
        RagException nullOperation = assertThrows(RagException.class,
                () -> service.verifyReplay(null, ChatPrincipal.local()));
        assertEquals(ErrorCode.FORBIDDEN, nullOperation.getErrorCodeEnum());

        RagException nullSnapshot = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(null), ChatPrincipal.local()));
        assertEquals(ErrorCode.FORBIDDEN, nullSnapshot.getErrorCodeEnum());
    }

    @Test
    void verifyReplayRejectsMissingEnumAndBooleanFields() {
        // 缺失 scopeMode 字段。
        RagException missingScope = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation("""
                                {"authorizationSnapshotVersion":1,
                                 "callerAccessMode":"UNRESTRICTED",
                                 "effectiveSelectedCollectionIds":[],
                                 "callerAllowList":[],
                                 "unassignedDocumentsAllowed":false,
                                 "sourceDocumentCollectionSnapshot":[],
                                 "sourceCollectionIdsObserved":[]}"""
                                .replaceAll("\\s+", "")),
                        ChatPrincipal.local()));
        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                missingScope.getErrorCodeEnum());

        // scopeMode 不在允许枚举内。
        RagException bogusScope = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(plainSnapshot("BOGUS", "UNRESTRICTED",
                                "[]", "[]", "false")),
                        ChatPrincipal.local()));
        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                bogusScope.getErrorCodeEnum());

        // 缺失 unassignedDocumentsAllowed 字段。
        RagException missingFlag = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation("""
                                {"authorizationSnapshotVersion":1,
                                 "scopeMode":"CALLER_VISIBLE",
                                 "callerAccessMode":"UNRESTRICTED",
                                 "effectiveSelectedCollectionIds":[],
                                 "callerAllowList":[],
                                 "sourceDocumentCollectionSnapshot":[],
                                 "sourceCollectionIdsObserved":[]}"""
                                .replaceAll("\\s+", "")),
                        ChatPrincipal.local()));
        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                missingFlag.getErrorCodeEnum());
    }

    @Test
    void verifyReplayRejectsNotApplicableWithNonEmptyScopeOrSources() {
        // NOT_APPLICABLE 不允许已选集合。
        RagException withSelected = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(plainSnapshot("NOT_APPLICABLE",
                                "NOT_APPLICABLE", "[5]", "[]", "false")),
                        ChatPrincipal.local()));
        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                withSelected.getErrorCodeEnum());

        // NOT_APPLICABLE 不允许来源证据。
        RagException withSources = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(snapshotWith("NOT_APPLICABLE",
                                "NOT_APPLICABLE", "[]", "[]", "false",
                                "[{\"documentId\":10,\"collectionId\":2}]",
                                "[2]")),
                        ChatPrincipal.local()));
        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                withSources.getErrorCodeEnum());
    }

    @Test
    void verifyReplayRejectsNonArraySourceRows() {
        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(snapshotWith("CALLER_VISIBLE",
                                "UNRESTRICTED", "[]", "[]", "false",
                                "5", "[]")),
                        ChatPrincipal.local()));

        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void verifyReplayRejectsTextualAllowListEntries() {
        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(snapshotWith("CALLER_VISIBLE",
                                "RESTRICTED", "[]", "[\"x\"]", "false",
                                "[]", "[]")),
                        ChatPrincipal.local()));

        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void verifyReplaySelectedSourceWithinScopePasses() {
        when(documentRepository.findById(10L))
                .thenReturn(Optional.of(document(10L, 7L)));
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(policyFor("7"));

        assertDoesNotThrow(() -> service.verifyReplay(
                operation(snapshotWith("SELECTED_COLLECTIONS",
                        "RESTRICTED", "[7]", "[7]", "false",
                        "[{\"documentId\":10,\"collectionId\":7}]", "[7]")),
                new ChatPrincipal("db:principal-1", "DATABASE_API_KEY",
                        false)));
    }

    @Test
    void verifyReplayRejectsUnassignedSourceWhenCurrentAccessRestricted() {
        // CALLER_VISIBLE + 允许未分配，但当前主体已受限 → 拒绝。
        // 注意首访访问模式需为 RESTRICTED，否则更早触发收窄守卫。
        when(documentRepository.findById(10L))
                .thenReturn(Optional.of(document(10L, null)));
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(policyFor("7"));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(snapshotWith("CALLER_VISIBLE",
                                "RESTRICTED", "[]", "[7]", "true",
                                "[{\"documentId\":10}]", "[]")),
                        new ChatPrincipal("db:principal-1",
                                "DATABASE_API_KEY", false)));

        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("not assigned"));
    }

    @Test
    void snapshotRejectsNullSourceElement() {
        useUnrestrictedRequestContext();
        // ChatResponse.setSources 经 List.copyOf 防御，null 元素直接 NPE，
        // 因此 parseDocumentId(null) 为不可达防御分支——此处仅固化行为。
        ChatResponse response = new ChatResponse();
        response.setAnswer("answer");
        assertThrows(NullPointerException.class,
                () -> response.setSources(
                        java.util.Arrays.asList((ChatSource) null)));
    }

    @Test
    void verifyReplayFailsClosedOnMalformedSnapshotJson() {
        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation("not-json"), ChatPrincipal.local()));

        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("could not be verified"));
    }

    @Test
    void verifyReplayRejectsNonBooleanUnassignedFlag() {
        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(snapshotWith("CALLER_VISIBLE",
                                "UNRESTRICTED", "[]", "[]", "\"yes\"",
                                "[]", "[]")),
                        ChatPrincipal.local()));

        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void verifyReplayRejectsNotApplicableWithMismatchedFields() {
        // NOT_APPLICABLE 必须配 NOT_APPLICABLE 访问模式。
        RagException mismatched = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(plainSnapshot("NOT_APPLICABLE",
                                "UNRESTRICTED", "[]", "[]", "false")),
                        ChatPrincipal.local()));
        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                mismatched.getErrorCodeEnum());

        // NOT_APPLICABLE 不允许 unassignedDocumentsAllowed=true。
        RagException unassigned = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(plainSnapshot("NOT_APPLICABLE",
                                "NOT_APPLICABLE", "[]", "[]", "true")),
                        ChatPrincipal.local()));
        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                unassigned.getErrorCodeEnum());
    }

    @Test
    void verifyReplayRejectsNonTextualScopeMode() {
        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(snapshotWith("42", "UNRESTRICTED",
                                "[]", "[]", "false", "[]", "[]")),
                        ChatPrincipal.local()));

        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void verifyReplayWithNullPrincipalTreatsCallerAsUnrestricted() {
        // RESTRICTED 快照 + null 主体（视为放宽）→ 无害通过。
        assertDoesNotThrow(() -> service.verifyReplay(
                operation(plainSnapshot("CALLER_VISIBLE", "RESTRICTED",
                        "[]", "[3]", "false")),
                null));
    }

    @Test
    void verifyReplayUnrestrictedAnyCollectionPassesWhenStillUnrestricted() {
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(policyFor(null));

        assertDoesNotThrow(() -> service.verifyReplay(
                operation(plainSnapshot("ANY_COLLECTION", "UNRESTRICTED",
                        "[]", "[]", "false")),
                new ChatPrincipal("db:principal-1", "DATABASE_API_KEY",
                        false)));
    }

    @Test
    void verifyReplayAllowsUnassignedSourceWhenCallerVisibleAndUnrestricted() {
        // 来源证据缺 collectionId → 归一为 null；未分配文档在
        // CALLER_VISIBLE + 允许未分配 + 当前非受限时放行。
        when(documentRepository.findById(10L))
                .thenReturn(Optional.of(document(10L, null)));

        assertDoesNotThrow(() -> service.verifyReplay(
                operation(snapshotWith("CALLER_VISIBLE", "UNRESTRICTED",
                        "[]", "[]", "true",
                        "[{\"documentId\":10}]", "[]")),
                ChatPrincipal.local()));
    }

    @Test
    void verifyReplayRejectsUnassignedSourceOutsideCallerVisibleScope() {
        when(documentRepository.findById(10L))
                .thenReturn(Optional.of(document(10L, null)));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(snapshotWith("ANY_COLLECTION",
                                "UNRESTRICTED", "[]", "[]", "false",
                                "[{\"documentId\":10,\"collectionId\":null}]",
                                "[]")),
                        ChatPrincipal.local()));

        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("not assigned"));
    }

    @Test
    void verifyReplayRejectsSourceEscapingSelectedCollections() {
        when(documentRepository.findById(10L))
                .thenReturn(Optional.of(document(10L, 7L)));
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(policyFor("7"));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(snapshotWith("SELECTED_COLLECTIONS",
                                "RESTRICTED", "[3]", "[7]", "false",
                                "[{\"documentId\":10,\"collectionId\":7}]",
                                "[7]")),
                        new ChatPrincipal("db:principal-1",
                                "DATABASE_API_KEY", false)));

        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("escaped the selected scope"));
    }

    @Test
    void verifyReplayRejectsMalformedSourceEvidenceRows() {
        String[][] badRows = new String[][] {
                // 行不是对象
                {"[\"str\"]", "[]"},
                // 缺 documentId
                {"[{}]", "[]"},
                // documentId 非正整数
                {"[{\"documentId\":0,\"collectionId\":1}]", "[]"},
                // collectionId 非数值
                {"[{\"documentId\":10,\"collectionId\":\"x\"}]", "[]"},
                // 白名单条目非正整数
                {"[]", "[-3]"}};
        for (String[] row : badRows) {
            String snapshot = snapshotWith("CALLER_VISIBLE", "UNRESTRICTED",
                    "[]", row[1], "false", row[0], "[]");
            RagException error = assertThrows(RagException.class,
                    () -> service.verifyReplay(
                            operation(snapshot), ChatPrincipal.local()),
                    "rows=" + row[0] + " allowList=" + row[1]);
            assertEquals(
                    ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                    error.getErrorCodeEnum(),
                    "rows=" + row[0] + " allowList=" + row[1]);
        }
    }

    @Test
    void verifyReplayRejectsSnapshotWithoutSourceRowsField() {
        String snapshot = """
                {"authorizationSnapshotVersion":1,
                 "scopeMode":"CALLER_VISIBLE",
                 "callerAccessMode":"UNRESTRICTED",
                 "effectiveSelectedCollectionIds":[],
                 "callerAllowList":[],
                 "unassignedDocumentsAllowed":false,
                 "sourceCollectionIdsObserved":[]}"""
                .replaceAll("\\s+", "");

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(snapshot), ChatPrincipal.local()));

        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }
}
