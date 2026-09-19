package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.openai.OpenAiChatRequestMapper;
import com.springairag.core.openai.OpenAiModelAliasRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * OpenAI 兼容控制器模型归一长尾（Batch 539，JaCoCo 驱动）：快照
 * publicAlias / declared / DEFAULT 三级回退、快照 JSON 损坏忽略、
 * keyed completion id 稳定且格式固定。
 */
class OpenAiCompatibilityModelResolutionTailTest {

    private static final UUID TURN_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private OpenAiCompatibilityController controller;

    @BeforeEach
    void setUp() {
        controller = new OpenAiCompatibilityController(
                mock(OpenAiModelAliasRegistry.class),
                mock(OpenAiChatRequestMapper.class),
                mock(ChatExecutionService.class),
                new ObjectMapper());
        controller.setTurnOperationService(
                mock(ChatTurnOperationService.class));
    }

    private com.springairag.api.dto.ChatResponse response(
            String requestedModel) {
        var response = new com.springairag.api.dto.ChatResponse();
        response.setAnswer("answer");
        response.setRequestedModel(requestedModel);
        return response;
    }

    private ChatTurnOperation operationWithSnapshot(String snapshot) {
        return new ChatTurnOperation(
                1L, "db:1", "key-hash", "fp-hash", 1,
                "session-1", TURN_ID,
                ChatTurnOperation.Transport.OPENAI_JSON,
                ChatTurnOperation.Status.SUCCEEDED,
                UUID.randomUUID(), Instant.now().plusSeconds(60),
                1, 1L, 1,
                snapshot, null, null, null, null,
                Instant.now(), Instant.now(), null);
    }

    private Object invokeModelFor(ChatTurnOperation operation,
                                  com.springairag.api.dto.ChatResponse response,
                                  String fallback) throws Exception {
        var method = OpenAiCompatibilityController.class.getDeclaredMethod(
                "modelFor", ChatTurnOperation.class,
                com.springairag.api.dto.ChatResponse.class, String.class);
        method.setAccessible(true);
        return method.invoke(controller, operation, response, fallback);
    }

    private Object invokeKeyedCompletionId(UUID turnId) throws Exception {
        var method = OpenAiCompatibilityController.class.getDeclaredMethod(
                "keyedCompletionId", UUID.class);
        method.setAccessible(true);
        return method.invoke(controller, turnId);
    }

    @Test
    void modelForPrefersSnapshotPublicAlias() throws Exception {
        String snapshot = "{\"publicModelAlias\":\"vendor/x\","
                + "\"declaredModelIdentifier\":\"internal/y\"}";
        assertEquals("vendor/x", invokeModelFor(
                operationWithSnapshot(snapshot),
                response("ignored"), "fallback"));
    }

    @Test
    void modelForFallsBackToDeclaredWhenPublicMissingOrDefault()
            throws Exception {
        assertEquals("internal/y", invokeModelFor(
                operationWithSnapshot(
                        "{\"declaredModelIdentifier\":\"internal/y\"}"),
                response("ignored"), "fallback"));

        assertEquals("fallback", invokeModelFor(
                operationWithSnapshot(
                        "{\"publicModelAlias\":\"DEFAULT\","
                                + "\"declaredModelIdentifier\":\"DEFAULT\"}"),
                response("from-response"), "fallback"));
    }

    @Test
    void modelForIgnoresCorruptSnapshotAndUsesResponse() throws Exception {
        assertEquals("from-response", invokeModelFor(
                operationWithSnapshot("{broken"),
                response("from-response"), "fallback"));
    }

    @Test
    void keyedCompletionIdIsDeterministicSha256WithPrefix()
            throws Exception {
        String first = (String) invokeKeyedCompletionId(TURN_ID);
        String second = (String) invokeKeyedCompletionId(TURN_ID);

        assertEquals(first, second);
        assertTrue(first.startsWith("chatcmpl-rag-"));
        assertEquals("chatcmpl-rag-".length() + 64, first.length());
    }
}
