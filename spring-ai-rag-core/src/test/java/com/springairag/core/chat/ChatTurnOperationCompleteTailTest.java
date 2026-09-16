package com.springairag.core.chat;

import com.springairag.api.dto.ChatResponse;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.exception.ChatTurnInProgressException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatTurnOperationService 完成与主体长尾（Batch 449）：
 * responseWithTurnId 的 turnId/元数据合并、principalFor 的
 * db:/root/legacy/未知四类主体归一。
 */
class ChatTurnOperationCompleteTailTest {

    private final ChatTurnOperationService service =
            new ChatTurnOperationService(
                    mock(com.springairag.core.repository.ChatTurnOperationRepository.class),
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    new RagChatProperties(),
                    new com.springairag.core.config.RagProperties(),
                    mock(ChatAuthorizationService.class),
                    mock(ChatObservabilityService.class),
                    mock(ChatExecutionService.class));

    private ChatResponse responseWithMetadata(Map<String, Object> metadata) {
        ChatResponse response = new ChatResponse();
        response.setMetadata(metadata);
        return response;
    }

    private ChatResponse invokeResponseWithTurnId(ChatResponse response,
                                                  UUID turnId) throws Exception {
        Method method = ChatTurnOperationService.class.getDeclaredMethod(
                "responseWithTurnId", ChatResponse.class, UUID.class);
        method.setAccessible(true);
        return (ChatResponse) method.invoke(service, response, turnId);
    }

    private String principalTypeFor(String owner) throws Exception {
        Method method = ChatTurnOperationService.class.getDeclaredMethod(
                "principalFor", ChatTurnOperation.class);
        method.setAccessible(true);
        ChatTurnOperation operation = operationWithOwner(owner);
        ChatPrincipal principal = (ChatPrincipal) method.invoke(service, operation);
        return principal.type();
    }

    private ChatTurnOperation operationWithOwner(String owner) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, owner, "key-hash", "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS, UUID.randomUUID(), null,
                1, 0L, 1,
                null, null, null, null, null,
                now, now, null);
    }

    @Test
    void responseWithTurnIdSetsIdAndMergesMetadata() throws Exception {
        ChatResponse withoutMetadata = new ChatResponse();
        ChatResponse result = invokeResponseWithTurnId(withoutMetadata,
                UUID.randomUUID());
        assertEquals(1, result.getMetadata().size());
        assertTrue(result.getMetadata().containsKey("turnId"));
        assertEquals(result.getTurnId(),
                result.getMetadata().get("turnId"));

        ChatResponse withMetadata = new ChatResponse();
        withMetadata.setMetadata(Map.of("tenant", "acme"));
        ChatResponse merged = invokeResponseWithTurnId(withMetadata,
                UUID.randomUUID());
        assertEquals("acme", merged.getMetadata().get("tenant"));
        assertEquals(2, merged.getMetadata().size());
    }

    @Test
    void principalForMapsOwnerKinds() throws Exception {
        assertEquals("DATABASE_API_KEY", principalTypeFor("db:principal-1"));
        assertEquals("ENVIRONMENT_ROOT", principalTypeFor("root:environment-root"));
        assertEquals("LEGACY_STATIC", principalTypeFor("legacy:static"));
        assertEquals("AUTH_DISABLED", principalTypeFor("someone-else"));
    }
}
