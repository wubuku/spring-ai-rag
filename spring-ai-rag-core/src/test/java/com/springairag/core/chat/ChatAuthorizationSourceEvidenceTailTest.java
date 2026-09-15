package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.entity.RagDocument;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.ApiKeyManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatAuthorizationService 回放校验长尾（Batch 416）：来源证据
 * 的形态/重复/观测一致性、来源文档的存在/启用/归属/授权矩阵、
 * 未分配文档仅在 CALLER_VISIBLE+UNRESTRICTED+显式允许时放行。
 */
class ChatAuthorizationSourceEvidenceTailTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RagDocumentRepository documentRepository =
            mock(RagDocumentRepository.class);
    private final ApiKeyManagementService apiKeyManagementService =
            mock(ApiKeyManagementService.class);
    private final ChatAuthorizationService service =
            new ChatAuthorizationService(
                    objectMapper, documentRepository, apiKeyManagementService);

    @BeforeEach
    void setUp() {
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(adminPolicy());
    }

    private static String snapshot(String scopeMode, String callerAccessMode,
                                   String selectedIds, String allowList,
                                   boolean unassignedAllowed, String sources,
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
                allowList, unassignedAllowed, sources, observed);
    }

    private static AuthenticatedApiPrincipal restrictedPolicy(String allowedIds) {
        return new AuthenticatedApiPrincipal(
                "rag_p_current", "rag_k_current_v1", 1,
                "DATABASE_API_KEY", ApiKeyRole.NORMAL, allowedIds,
                LocalDateTime.now().plusYears(1), 1L, null,
                List.of("RAG_READ"));
    }

    private static AuthenticatedApiPrincipal adminPolicy() {
        return new AuthenticatedApiPrincipal(
                "rag_p_current", "rag_k_current_v1", 1,
                "DATABASE_API_KEY", ApiKeyRole.ADMIN, null,
                LocalDateTime.now().plusYears(1), 1L, null,
                List.of("RAG_READ"));
    }

    private ChatPrincipal dbPrincipal() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/chat/stream");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                "principal-1");
        return ChatPrincipal.from(request);
    }

    private ChatTurnOperation operation(String authorizationSnapshot) {
        Instant now = Instant.now();
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

    private RagDocument document(long id, Long collectionId, boolean enabled,
                                 boolean tombstoned) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setCollectionId(collectionId);
        document.setEnabled(enabled);
        document.setSourceDeletedAt(tombstoned
                ? java.time.LocalDateTime.now() : null);
        return document;
    }

    private RagException forbiddenOf(RagException error) {
        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCodeEnum());
        return error;
    }

    private RagException invalidOf(RagException error) {
        assertEquals(ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
        return error;
    }

    @Test
    void missingSnapshotIsForbidden() {
        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(null), dbPrincipal()));
        assertTrue(forbiddenOf(error).getMessage()
                .contains("snapshot is missing"));
    }

    @Test
    void wrongSnapshotVersionIsInvalid() {
        String json = snapshot("NOT_APPLICABLE", "NOT_APPLICABLE", "[]",
                "[]", false, "[]", "[]")
                .replace("\"authorizationSnapshotVersion\": 1",
                        "\"authorizationSnapshotVersion\": 9");
        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(json), dbPrincipal()));
        invalidOf(error);
    }

    @Test
    void observedCollectionMismatchIsInvalid() {
        String json = snapshot("SELECTED_COLLECTIONS", "UNRESTRICTED",
                "[7]", "[]", false,
                "[{\"documentId\":41,\"collectionId\":7}]", "[]");
        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(json), dbPrincipal()));
        invalidOf(error);
    }

    @Test
    void duplicateSourceDocumentsAreInvalid() {
        String json = snapshot("SELECTED_COLLECTIONS", "UNRESTRICTED",
                "[7]", "[]", false,
                "[{\"documentId\":41,\"collectionId\":7},"
                        + "{\"documentId\":41,\"collectionId\":7}]",
                "[7]");
        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(json), dbPrincipal()));
        invalidOf(error);
    }

    @Test
    void nonPositiveSourceCollectionIdIsInvalid() {
        String json = snapshot("SELECTED_COLLECTIONS", "UNRESTRICTED",
                "[7]", "[]", false,
                "[{\"documentId\":41,\"collectionId\":0}]", "[]");
        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(json), dbPrincipal()));
        invalidOf(error);
    }

    @Test
    void vanishedSourceDocumentIsForbidden() {
        String json = snapshot("SELECTED_COLLECTIONS", "UNRESTRICTED",
                "[7]", "[]", false,
                "[{\"documentId\":41,\"collectionId\":7}]", "[7]");
        when(documentRepository.findById(41L)).thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(json), dbPrincipal()));
        assertTrue(forbiddenOf(error).getMessage()
                .contains("no longer exists"));
    }

    @Test
    void disabledOrTombstonedSourceDocumentIsForbidden() {
        String json = snapshot("SELECTED_COLLECTIONS", "UNRESTRICTED",
                "[7]", "[]", false,
                "[{\"documentId\":41,\"collectionId\":7}]", "[7]");
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document(41L, 7L, false, false)));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(json), dbPrincipal()));
        assertTrue(forbiddenOf(error).getMessage()
                .contains("disabled or tombstoned"));

        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document(41L, 7L, true, true)));
        error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(json), dbPrincipal()));
        assertTrue(forbiddenOf(error).getMessage()
                .contains("disabled or tombstoned"));
    }

    @Test
    void collectionChangeIsForbidden() {
        String json = snapshot("SELECTED_COLLECTIONS", "UNRESTRICTED",
                "[7]", "[]", false,
                "[{\"documentId\":41,\"collectionId\":7}]", "[7]");
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document(41L, 8L, true, false)));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(json), dbPrincipal()));
        assertTrue(forbiddenOf(error).getMessage()
                .contains("Collection changed"));
    }

    @Test
    void unassignedSourceRequiresCallerVisibleUnrestrictedWithFlag() {
        String json = snapshot("CALLER_VISIBLE", "UNRESTRICTED",
                "[]", "[]", true,
                "[{\"documentId\":41,\"collectionId\":null}]", "[]");
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document(41L, null, true, false)));

        // CALLER_VISIBLE + UNRESTRICTED + 允许未分配 → 放行。
        service.verifyReplay(operation(json), dbPrincipal());

        // 关闭 unassignedDocumentsAllowed → 拒绝。
        String strict = snapshot("CALLER_VISIBLE", "UNRESTRICTED",
                "[]", "[]", false,
                "[{\"documentId\":41,\"collectionId\":null}]", "[]");
        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(strict), dbPrincipal()));
        assertTrue(forbiddenOf(error).getMessage()
                .contains("not assigned"));
    }

    @Test
    void sourceOutsideCurrentAllowListIsForbidden() {
        // firstAllow 与当前密钥一致（不触发吊销分支），
        // 但来源文档集合 7 不在当前允许列表 [8] 内。
        String json = snapshot("CALLER_VISIBLE", "RESTRICTED",
                "[]", "[8]", false,
                "[{\"documentId\":41,\"collectionId\":7}]", "[7]");
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(restrictedPolicy("8"));
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document(41L, 7L, true, false)));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(json), dbPrincipal()));
        assertTrue(forbiddenOf(error).getMessage()
                .contains("not authorized"));
    }

    @Test
    void selectedScopeEscapeIsForbidden() {
        String json = snapshot("SELECTED_COLLECTIONS", "UNRESTRICTED",
                "[7]", "[]", false,
                "[{\"documentId\":41,\"collectionId\":8}]", "[8]");
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document(41L, 8L, true, false)));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(json), dbPrincipal()));
        assertTrue(forbiddenOf(error).getMessage()
                .contains("escaped the selected scope"));
    }

    @Test
    void inactiveOwnerPrincipalIsForbidden() {
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(null);
        String json = snapshot("SELECTED_COLLECTIONS", "UNRESTRICTED",
                "[7]", "[]", false, "[]", "[]");

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(operation(json), dbPrincipal()));
        assertTrue(forbiddenOf(error).getMessage()
                .contains("no longer active"));
    }
}
