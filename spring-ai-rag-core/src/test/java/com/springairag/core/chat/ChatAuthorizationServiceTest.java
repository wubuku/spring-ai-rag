package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.ChatTurnOperationRepository;
import com.springairag.core.service.ApiKeyManagementService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ChatAuthorizationServiceTest {

    private final RagDocumentRepository documentRepository =
            mock(RagDocumentRepository.class);
    private final ApiKeyManagementService apiKeyManagementService =
            mock(ApiKeyManagementService.class);
    private final ChatAuthorizationService service =
            new ChatAuthorizationService(
                    new ObjectMapper(), documentRepository, apiKeyManagementService);

    @Test
    void unknownScopeModeFailsClosed() {
        RagException error = assertThrows(
                RagException.class,
                () -> service.verifyReplay(
                        operation("""
                                {
                                  "authorizationSnapshotVersion": 1,
                                  "scopeMode": "UNKNOWN",
                                  "callerAccessMode": "UNRESTRICTED",
                                  "effectiveSelectedCollectionIds": [],
                                  "callerAllowList": [],
                                  "unassignedDocumentsAllowed": false,
                                  "sourceDocumentCollectionSnapshot": [],
                                  "sourceCollectionIdsObserved": []
                                }
                                """),
                        ChatPrincipal.local()));

        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void unknownCallerAccessModeFailsClosed() {
        RagException error = assertThrows(
                RagException.class,
                () -> service.verifyReplay(
                        operation("""
                                {
                                  "authorizationSnapshotVersion": 1,
                                  "scopeMode": "ANY_COLLECTION",
                                  "callerAccessMode": "UNKNOWN",
                                  "effectiveSelectedCollectionIds": [],
                                  "callerAllowList": [],
                                  "unassignedDocumentsAllowed": false,
                                  "sourceDocumentCollectionSnapshot": [],
                                  "sourceCollectionIdsObserved": []
                                }
                                """),
                        ChatPrincipal.local()));

        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void nonIntegralSourceIdsFailClosed() {
        RagException error = assertThrows(
                RagException.class,
                () -> service.verifyReplay(
                        operation("""
                                {
                                  "authorizationSnapshotVersion": 1,
                                  "scopeMode": "ANY_COLLECTION",
                                  "callerAccessMode": "UNRESTRICTED",
                                  "effectiveSelectedCollectionIds": [],
                                  "callerAllowList": [],
                                  "unassignedDocumentsAllowed": false,
                                  "sourceDocumentCollectionSnapshot": [
                                    {"documentId": "42", "collectionId": 7}
                                  ],
                                  "sourceCollectionIdsObserved": [7]
                                }
                                """),
                        ChatPrincipal.local()));

        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    private ChatTurnOperation operation(String authorizationSnapshot) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L,
                "local:auth-disabled",
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
                "{\"executionSnapshotVersion\":1,\"resolvedCandidates\":[\"provider/model\"]}",
                "{\"answer\":\"stable\"}",
                null,
                null,
                authorizationSnapshot.replaceAll("\\s+", ""),
                now,
                now,
                now);
    }
}
