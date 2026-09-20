package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.openai.OpenAiChatRequestMapper;
import com.springairag.core.openai.OpenAiModelAliasRegistry;
import com.springairag.api.openai.OpenAiChatCompletionResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * OpenAI 兼容控制器流式与映射长尾（Batch 548，JaCoCo 驱动）：json
 * Response 发送后完成、mapEvent 的增量/完成/未知事件三分支、
 * toResponse 双重载（执行结果 usage/finishReason 与原生响应 null
 * 归一）、send 的 IO 异常传播、prepareTurn 携带集合头 OpenAI 指纹。
 */
class OpenAiCompatibilityEventMappingTailTest {

    private static final String COLLECTION_HEADER =
            "X-RAG-Collection-Key";

    private ChatTurnOperationService turnOperationService;
    private OpenAiCompatibilityController controller;

    @BeforeEach
    void setUp() {
        turnOperationService = mock(ChatTurnOperationService.class);
        controller = new OpenAiCompatibilityController(
                mock(OpenAiModelAliasRegistry.class),
                mock(OpenAiChatRequestMapper.class),
                mock(com.springairag.core.chat.ChatExecutionService.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
        controller.setTurnOperationService(turnOperationService);
    }

    private Object invoke(String name, Class<?>[] params, Object... args)
            throws Exception {
        Method method = OpenAiCompatibilityController.class
                .getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method.invoke(controller, args);
    }

    private ChatExecutionResult result() {
        return new ChatExecutionResult(
                "answer", "session-1", "trace-1", null, "test/model",
                ChatMode.PLAIN, List.of(),
                Map.of("promptTokens", 3, "completionTokens", 5,
                        "totalTokens", 8),
                "STOP", List.of(), Map.of());
    }

    @Test
    void jsonResponseEmitsAndCompletes() throws Exception {
        var emitter = (org.springframework.web.servlet.mvc.method.annotation
                .ResponseBodyEmitter)
                invoke("jsonResponse",
                        new Class<?>[]{Object.class}, Map.of("ok", true));

        assertNotNull(emitter);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapEventContentDeltaProducesChunkWithContent() throws Exception {
        var flux = (reactor.core.publisher.Flux<String>) invoke(
                "mapEvent",
                new Class<?>[]{String.class, long.class, String.class,
                        ChatEvent.class},
                "chatcmpl-1", 1_700_000_000L, "gpt-x",
                new ChatEvent.ContentDelta("hello"));

        String json = flux.blockFirst();
        assertNotNull(json);
        assertTrue(json.contains("chat.completion.chunk"));
        assertTrue(json.contains("hello"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapEventCompletedProducesFinishReason() throws Exception {
        var flux = (reactor.core.publisher.Flux<String>) invoke(
                "mapEvent",
                new Class<?>[]{String.class, long.class, String.class,
                        ChatEvent.class},
                "chatcmpl-1", 1_700_000_000L, "gpt-x",
                new ChatEvent.Completed("trace", "session-1", "req-m",
                        "res-m", ChatMode.PLAIN, Map.of(), "STOP",
                        List.of(), Map.of()));

        String json = flux.blockFirst();
        assertNotNull(json);
        assertTrue(json.contains("stop"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapEventUnknownTypeReturnsEmptyFlux() throws Exception {
        var flux = (reactor.core.publisher.Flux<String>) invoke(
                "mapEvent",
                new Class<?>[]{String.class, long.class, String.class,
                        ChatEvent.class},
                "chatcmpl-1", 1_700_000_000L, "gpt-x",
                new ChatEvent.ToolStarted("call-1", "lookup", "q"));

        assertEquals(List.of(), flux.collectList().block());
    }

    @Test
    void toResponseFromExecutionResultMapsUsageAndFinishReason()
            throws Exception {
        var params = new Class<?>[]{String.class, long.class, String.class,
                ChatExecutionResult.class};
        var response = (com.springairag.api.openai.OpenAiChatCompletionResponse)
                invoke("toResponse", params,
                        "chatcmpl-1", 1_700_000_000L, "gpt-x", result());

        assertEquals("chatcmpl-1", response.id());
        assertEquals("gpt-x", response.model());
        assertEquals("stop", response.choices().getFirst().finishReason());
        assertNotNull(response.usage());
        assertEquals(8, response.usage().totalTokens());
    }

    @Test
    void toResponseFromNativeResponseNormalizesNullFinishReason()
            throws Exception {
        ChatResponse native_response = new ChatResponse();
        native_response.setAnswer("native answer");
        native_response.setFinishReason(null);
        native_response.setUsage(null);

        var params = new Class<?>[]{String.class, long.class, String.class,
                ChatResponse.class};
        var response = (com.springairag.api.openai.OpenAiChatCompletionResponse)
                invoke("toResponse", params,
                        "chatcmpl-2", 1_700_000_000L, "gpt-x",
                        native_response);

        assertEquals("stop", response.choices().getFirst().finishReason());
        assertNull(response.usage());
    }

    @Test
    void sendPropagatesIOExceptionAsReactorException() throws Exception {
        var emitter = mock(SseEmitter.class);
        org.mockito.Mockito.doThrow(new java.io.IOException("broken pipe"))
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));

        var error = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> invoke("send",
                        new Class<?>[]{SseEmitter.class, String.class},
                        emitter, "data"));

        // reactor 将 IOException 包装为运行时异常再被反射包裹。
        assertTrue(error.getCause() instanceof RuntimeException);
        assertTrue(error.getCause().getCause()
                instanceof java.io.IOException);
    }

    @Test
    @SuppressWarnings("unchecked")
    void prepareTurnFingerprintsOpenAiRequestWithCollectionHeader() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/v1/chat/completions");
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
        request.addHeader(COLLECTION_HEADER, "kb-1");

        when(turnOperationService.prepare(
                any(com.springairag.core.chat.ChatPrincipal.class),
                anyList(), any()))
                .thenReturn(new ChatTurnOperationService.Prepared(
                        com.springairag.core.chat.ChatPrincipal.local(),
                        "key-hash", "fp-hash", null, null, true));

        var prepared = (ChatTurnOperationService.Prepared) invokeTurn(
                new com.springairag.api.openai.OpenAiChatCompletionRequest(),
                request);

        assertNotNull(prepared);
        assertTrue(prepared.keyed());
        verify(turnOperationService).prepare(
                any(com.springairag.core.chat.ChatPrincipal.class),
                anyList(),
                isNotNull());
    }

    private Object invokeTurn(
            com.springairag.api.openai.OpenAiChatCompletionRequest request,
            HttpServletRequest httpRequest) {
        try {
            return invoke("prepareTurn",
                    new Class<?>[]{request.getClass(),
                            HttpServletRequest.class},
                    request, httpRequest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
