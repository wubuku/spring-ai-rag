package com.springairag.core.chat;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * verifyReplay 授权收窄/扩宽矩阵：首次请求的授权证据在重放时必须
 * 仍然成立——集合访问被撤销/收窄即拒绝，扩宽无害。
 */
class ChatAuthorizationReplayMatrixTest {

    private final RagDocumentRepository documentRepository =
            mock(RagDocumentRepository.class);
    private final com.springairag.core.service.ApiKeyManagementService
            apiKeyManagementService =
            mock(com.springairag.core.service.ApiKeyManagementService.class);
    private final ChatAuthorizationService service =
            new ChatAuthorizationService(
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    documentRepository,
                    apiKeyManagementService);

    private static String snapshot(String scopeMode, String callerAccessMode,
                                   String selectedIds, String allowList,
                                   boolean unassignedAllowed) {
        return """
                {
                  "authorizationSnapshotVersion": 1,
                  "scopeMode": "%s",
                  "callerAccessMode": "%s",
                  "effectiveSelectedCollectionIds": %s,
                  "callerAllowList": %s,
                  "unassignedDocumentsAllowed": %s,
                  "sourceDocumentCollectionSnapshot": [],
                  "sourceCollectionIdsObserved": []
                }
                """.formatted(scopeMode, callerAccessMode, selectedIds,
                allowList, unassignedAllowed).replaceAll("\\s+", "");
    }

    private static AuthenticatedApiPrincipal restrictedPolicy(String allowedIds) {
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

    private ChatPrincipal dbPrincipal(String principalId) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/chat/stream");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                principalId);
        return ChatPrincipal.from(request);
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

    @Test
    void callerVisibleUnrestrictedFailsWhenCurrentAccessIsNarrower() {
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(restrictedPolicy("3"));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(snapshot("CALLER_VISIBLE", "UNRESTRICTED",
                                "[]", "[]", true)),
                        dbPrincipal("principal-1")));
        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCodeEnum());
    }

    @Test
    void restrictedSnapshotPassesWhenCurrentAccessWidened() {
        // 当前 principal 变为无限制（非 db 身份）：对既有有界回答无害。

        assertDoesNotThrow(() -> service.verifyReplay(
                operation(snapshot("CALLER_VISIBLE", "RESTRICTED",
                        "[]", "[3]", false)),
                ChatPrincipal.local()));
    }

    @Test
    void restrictedSnapshotFailsWhenCollectionAccessWasRevoked() {
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(restrictedPolicy("7"));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(snapshot("CALLER_VISIBLE", "RESTRICTED",
                                "[]", "[3]", false)),
                        dbPrincipal("principal-1")));
        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCodeEnum());
    }

    @Test
    void restrictedSnapshotPassesWhenAllowListStillContainsTheCollections() {
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(restrictedPolicy("3,7"));

        assertDoesNotThrow(() -> service.verifyReplay(
                operation(snapshot("CALLER_VISIBLE", "RESTRICTED",
                        "[]", "[3]", false)),
                dbPrincipal("principal-1")));
    }

    @Test
    void unrestrictedAnyCollectionFailsWhenCurrentAccessIsRestricted() {
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(restrictedPolicy("3"));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(snapshot("ANY_COLLECTION", "UNRESTRICTED",
                                "[]", "[]", false)),
                        dbPrincipal("principal-1")));
        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCodeEnum());
    }

    @Test
    void unrestrictedSelectedCollectionsPassWhenStillAllowed() {
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(restrictedPolicy("3"));

        assertDoesNotThrow(() -> service.verifyReplay(
                operation(snapshot("SELECTED_COLLECTIONS", "UNRESTRICTED",
                        "[3]", "[]", false)),
                dbPrincipal("principal-1")));
    }

    @Test
    void unrestrictedSelectedCollectionsFailWhenScopeEscaped() {
        when(apiKeyManagementService.findActivePrincipal("principal-1"))
                .thenReturn(restrictedPolicy("3"));

        RagException error = assertThrows(RagException.class,
                () -> service.verifyReplay(
                        operation(snapshot("SELECTED_COLLECTIONS",
                                "UNRESTRICTED", "[9]", "[]", false)),
                        dbPrincipal("principal-1")));
        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCodeEnum());
    }

    @Test
    void notApplicableScopePassesWithoutFurtherChecks() {
        assertDoesNotThrow(() -> service.verifyReplay(
                operation(snapshot("NOT_APPLICABLE", "NOT_APPLICABLE",
                        "[]", "[]", false)),
                ChatPrincipal.local()));
    }
}
