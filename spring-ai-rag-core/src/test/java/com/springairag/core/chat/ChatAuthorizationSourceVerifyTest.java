package com.springairag.core.chat;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.ChatTurnOperationRepository;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.entity.ApiKeyRole;
import org.junit.jupiter.api.AfterEach;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * verifySources 文档状态回放校验：来源文档缺失/禁用/墓碑化/换集合/
 * 未分配/集合越权/逃逸选定范围 各分支 fail-closed。
 */
class ChatAuthorizationSourceVerifyTest {

    private RagDocumentRepository documentRepository =
            mock(RagDocumentRepository.class);
    private ChatTurnOperationRepository operationRepository =
            mock(ChatTurnOperationRepository.class);
    private ApiKeyManagementService apiKeyManagementService =
            mock(ApiKeyManagementService.class);
    private ChatAuthorizationService service = new ChatAuthorizationService(
            new com.fasterxml.jackson.databind.ObjectMapper(),
            documentRepository,
            apiKeyManagementService);

    @BeforeEach
    void resetContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    private ChatTurnOperation operation(String authorizationSnapshot) {
        return new ChatTurnOperation(
                1L,
                "db:principal-1",
                "a".repeat(64),
                "b".repeat(64),
                1,
                "session-1",
                UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.SUCCEEDED,
                UUID.randomUUID(),
                null,
                1,
                0L,
                1,
                null,
                "{\"answer\":\"cached\"}",
                null,
                null,
                authorizationSnapshot,
                Instant.now(),
                Instant.now(),
                Instant.now());
    }

    /** 带单条来源证据的快照 JSON。 */
    private String snapshot(String scopeMode, String callerAccessMode,
                            String selectedIds, String allowList,
                            boolean unassignedAllowed,
                            Long documentId, Long collectionId) {
        String sources = documentId == null
                ? "[]"
                : ("[{\"documentId\":%d,\"collectionId\":%d}]"
                        .formatted(documentId, collectionId));
        String observed = collectionId == null ? "[]" : ("[%d]".formatted(collectionId));
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
                allowList, unassignedAllowed, sources, observed)
                .replaceAll("\\s+", "");
    }

    private void setCurrentDbPolicy(String allowedIds) {
        AuthenticatedApiPrincipal policy = new AuthenticatedApiPrincipal(
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
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/chat/stream");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE, policy);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
        // db: 前缀 principal 经 findActivePrincipal 重新解析。
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(policy);
    }

    private RagDocument document(Long id, Long collectionId,
                                 boolean enabled, LocalDateTime sourceDeletedAt) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setCollectionId(collectionId);
        document.setEnabled(enabled);
        document.setSourceDeletedAt(sourceDeletedAt);
        return document;
    }

    private void stubDocument(Long id, RagDocument document) {
        when(documentRepository.findById(id)).thenReturn(Optional.of(document));
    }

    private static final String SNAPSHOT_TEMPLATE_BASE = "CALLER_VISIBLE";

    @Test
    void replayFailsWhenSourceDocumentNoLongerExists() {
        String snapshot = snapshot("CALLER_VISIBLE", "UNRESTRICTED",
                "[]", "[]", true, 10L, 7L);
        when(documentRepository.findById(10L))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(snapshot),
                        ChatPrincipal.local()));
        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("no longer exists"));
    }

    @Test
    void replayFailsWhenSourceDocumentWasDisabled() {
        String snapshot = snapshot("CALLER_VISIBLE", "UNRESTRICTED",
                "[]", "[]", true, 10L, 7L);
        stubDocument(10L, document(10L, 7L, false, null));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(snapshot),
                        ChatPrincipal.local()));
        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("disabled or tombstoned"));
    }

    @Test
    void replayFailsWhenSourceDocumentWasTombstoned() {
        String snapshot = snapshot("CALLER_VISIBLE", "UNRESTRICTED",
                "[]", "[]", true, 10L, 7L);
        stubDocument(10L, document(10L, 7L, true,
                LocalDateTime.now().minusDays(1)));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(snapshot),
                        ChatPrincipal.local()));
        assertTrue(error.getMessage().contains("disabled or tombstoned"));
    }

    @Test
    void replayFailsWhenSourceDocumentMovedCollections() {
        String snapshot = snapshot("CALLER_VISIBLE", "UNRESTRICTED",
                "[]", "[]", true, 10L, 7L);
        stubDocument(10L, document(10L, 8L, true, null));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(snapshot),
                        ChatPrincipal.local()));
        assertTrue(error.getMessage().contains("Collection changed"));
    }

    @Test
    void replayFailsWhenUnassignedSourceIsNoLongerAllowed() {
        // 快照允许未分配文档，但当前策略改为受限。
        String snapshot = snapshot("CALLER_VISIBLE", "RESTRICTED",
                "[]", "[3]", false, 10L, null);
        setCurrentDbPolicy("3");
        stubDocument(10L, document(10L, null, true, null));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(snapshot),
                        dbPrincipal()));
        assertTrue(error.getMessage().contains("not assigned"));
    }

    @Test
    void replayFailsWhenSourceCollectionIsNoLongerAuthorized() {
        // SELECTED 范围：快照选中 7+9，当前白名单只剩 7；来源 9 越权。
        String snapshot = snapshot("SELECTED_COLLECTIONS", "RESTRICTED",
                "[7,9]", "[7]", false, 10L, 9L);
        setCurrentDbPolicy("7");
        stubDocument(10L, document(10L, 9L, true, null));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(snapshot),
                        dbPrincipal()));
        assertTrue(error.getMessage().contains("not authorized"));
    }

    @Test
    void replayFailsWhenSourceEscapedTheSelectedScope() {
        String snapshot = snapshot("SELECTED_COLLECTIONS", "RESTRICTED",
                "[8]", "[7,8]", false, 10L, 7L);
        setCurrentDbPolicy("7,8");
        stubDocument(10L, document(10L, 7L, true, null));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(snapshot),
                        dbPrincipal()));
        assertTrue(error.getMessage().contains("escaped the selected scope"));
    }

    private ChatPrincipal dbPrincipal() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/chat/stream");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                "principal-1");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
        return ChatPrincipal.from(request);
    }
}
