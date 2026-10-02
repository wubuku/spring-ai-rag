package com.springairag.core.controller;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * SSE 载荷真值表（Batch 778）。
 *
 * <p>既有的 {@link RagChatControllerSendEventTest}（Batch 375）把每个事件都分发一遍，
 * 但**只断言 {@code assertDoesNotThrow}**——它从不检查实际发出去的内容。这比看上去更弱：
 * {@code SseEmitters.sendProgress} 会把发送异常整个吞掉（best-effort，客户端多半已断开），
 * 所以"没抛异常"既证明不了载荷正确，也发现不了发送失败。把 {@code retrievalTraceId}
 * 删掉、把错误码写错、把 Completed 的字段名拼错——这些回归都能一路绿灯通过原测试。
 *
 * <p>本类改为截获 {@code SseEventBuilder} 并逐字段断言，覆盖
 * {@code sendChatEvent} 的 Completed / Failed 分支与 {@code sendChatError} 的
 * 错误码提取路径（含 {@code firstNonBlank} 的回退）。
 */
class RagChatControllerSsePayloadTest {

    /**
     * 记录每次发送的 SSE 帧。
     *
     * <p>{@code SseEmitters} 调用的是 {@code send(SseEventBuilder)}；该接口的
     * {@code build()} 是公开方法，{@code DataWithMediaType.getData()} 也是，
     * 因此这里无需反射任何私有字段就能取到真实载荷对象。
     */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<Object> sent = new ArrayList<>();
        private boolean completed;

        RecordingEmitter() {
            super(Long.MAX_VALUE);
        }

        @Override
        public void send(SseEventBuilder builder) {
            Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();
            parts.forEach(part -> sent.add(part.getData()));
        }

        @Override
        public void complete() {
            completed = true;
        }

        /**
         * 最近一次发送的载荷。
         *
         * <p>{@code build()} 返回的是**多个**部分——事件名/注释累积成的文本片段，
         * 外加 {@code data(...)} 放进去的载荷对象——所以这里取最后一个 Map，
         * 而不是最后一个元素。
         */
        @SuppressWarnings("unchecked")
        Map<String, Object> lastPayload() {
            List<Object> maps = sent.stream().filter(Map.class::isInstance).toList();
            assertFalse(maps.isEmpty(),
                    "本次用例期望发出一个带 Map 载荷的 SSE 事件，实际发出: " + sent);
            return (Map<String, Object>) maps.getLast();
        }
    }

    private RagChatController controller;
    private RecordingEmitter emitter;
    private Method sendChatEvent;
    private Method sendChatError;

    @BeforeEach
    void setUp() throws Exception {
        controller = new RagChatController(
                mock(com.springairag.core.config.RagChatService.class),
                mock(com.springairag.core.repository.RagChatHistoryRepository.class),
                mock(com.springairag.core.service.ChatExportService.class),
                null,
                mock(com.springairag.core.service.CollectionRetrievalScopeResolver.class),
                null);
        emitter = new RecordingEmitter();
        sendChatEvent = RagChatController.class.getDeclaredMethod(
                "sendChatEvent", SseEmitter.class, ChatEvent.class,
                String.class, String.class);
        sendChatEvent.setAccessible(true);
        sendChatError = RagChatController.class.getDeclaredMethod(
                "sendChatError", SseEmitter.class, Throwable.class,
                String.class, String.class);
        sendChatError.setAccessible(true);
    }

    private void dispatch(ChatEvent event) throws Exception {
        sendChatEvent.invoke(controller, emitter, event, "stream-trace", "stream-session");
    }

    // ── Completed ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Completed 载荷带出 retrievalTraceId")
    void completedCarriesRetrievalTraceId() throws Exception {
        dispatch(new ChatEvent.Completed(
                "trace-1", "session-1", "requested", "resolved",
                ChatMode.PLAIN, Map.of("tokens", 12), "stop",
                List.of(), Map.of("retrievalTraceId", "rt-1")));

        Map<String, Object> payload = emitter.lastPayload();
        assertEquals("complete", payload.get("status"));
        assertEquals("rt-1", payload.get("retrievalTraceId"));
        assertEquals("session-1", payload.get("sessionId"));
        assertEquals("requested", payload.get("requestedModel"));
        assertEquals("resolved", payload.get("resolvedModel"));
        assertEquals(ChatMode.PLAIN, payload.get("mode"));
        assertEquals("stop", payload.get("finishReason"));
    }

    @Test
    @DisplayName("Completed 载荷在 metadata 为 null 时不含 retrievalTraceId 键")
    void completedWithoutMetadataOmitsTraceId() throws Exception {
        dispatch(new ChatEvent.Completed(
                "trace-1", "session-1", "requested", "resolved",
                ChatMode.PLAIN, null, null, null, null));

        Map<String, Object> payload = emitter.lastPayload();
        assertEquals("complete", payload.get("status"));
        assertFalse(payload.containsKey("retrievalTraceId"),
                "metadata 为 null 时不得凭空造出 retrievalTraceId: " + payload);
    }

    @Test
    @DisplayName("metadata 存在但没有 retrievalTraceId 时同样不带该键")
    void completedWithUnrelatedMetadataOmitsTraceId() throws Exception {
        dispatch(new ChatEvent.Completed(
                "trace-1", "session-1", "requested", "resolved",
                ChatMode.PLAIN, Map.of("tokens", 12), "stop",
                List.of(), Map.of("unrelated", "value")));

        Map<String, Object> payload = emitter.lastPayload();
        assertFalse(payload.containsKey("retrievalTraceId"),
                "只有真的带出 traceId 时才应出现该键: " + payload);
    }

    @Test
    @DisplayName("Completed 的 traceId 为空白时回退到流级 traceId")
    void completedFallsBackToStreamTraceIdWhenBlank() throws Exception {
        dispatch(new ChatEvent.Completed(
                "   ", "session-1", "requested", "resolved",
                ChatMode.PLAIN, Map.of("tokens", 1), "stop",
                List.of(), Map.of()));

        assertEquals("stream-trace", emitter.lastPayload().get("traceId"),
                "空白 traceId 必须回退到流级取值，而不是把空白原样发出去");
    }

    // ── Failed ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Failed 载荷带上错误码与消息")
    void failedCarriesCodeAndMessage() throws Exception {
        dispatch(new ChatEvent.Failed(
                "trace-9", "session-9", "CHAT_BUDGET_EXHAUSTED", "预算耗尽"));

        Map<String, Object> payload = emitter.lastPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) payload.get("error");
        assertEquals("CHAT_BUDGET_EXHAUSTED", error.get("code"));
        assertEquals("预算耗尽", error.get("message"));
        assertEquals("trace-9", payload.get("traceId"));
        assertEquals("session-9", payload.get("sessionId"));
        assertTrue(emitter.completed, "错误事件之后必须关闭流");
    }

    @Test
    @DisplayName("Failed 的 code 为 null 时载荷里不出现 code 键")
    void failedWithoutCodeOmitsCodeKey() throws Exception {
        dispatch(new ChatEvent.Failed("trace-9", "session-9", null, "没有错误码"));

        Map<String, Object> payload = emitter.lastPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) payload.get("error");
        assertEquals("没有错误码", error.get("message"));
        assertFalse(error.containsKey("code"),
                "没有错误码时不得写入 null 占位: " + error);
    }

    @Test
    @DisplayName("Failed 的 traceId/sessionId 为空白时回退到流级取值")
    void failedFallsBackToStreamIdentifiers() throws Exception {
        dispatch(new ChatEvent.Failed("", "", "CHAT_FAILED", "失败"));

        Map<String, Object> payload = emitter.lastPayload();
        assertEquals("stream-trace", payload.get("traceId"));
        assertEquals("stream-session", payload.get("sessionId"));
    }

    // ── sendChatError 的错误码提取 ──────────────────────────────────────

    @Test
    @DisplayName("RagException 的错误码被写进载荷")
    void ragExceptionSuppliesCode() throws Exception {
        sendChatError.invoke(controller, emitter,
                new RagException(ErrorCode.MODEL_CAPABILITY_UNSUPPORTED, "不支持"),
                "trace-1", "session-1");

        Map<String, Object> payload = emitter.lastPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) payload.get("error");
        // RagException.getErrorCode() 返回字符串而非枚举：载荷最终要序列化成 JSON，
        // 写成枚举对象只是恰好能 toString 出同样的字面量，属于巧合而非契约。
        assertEquals("MODEL_CAPABILITY_UNSUPPORTED", error.get("code"));
        assertEquals("不支持", error.get("message"));
    }

    @Test
    @DisplayName("无消息的普通异常退回兜底文案，且不写 code")
    void plainExceptionFallsBackToDefaultMessage() throws Exception {
        sendChatError.invoke(controller, emitter, new RuntimeException(),
                "trace-1", "session-1");

        Map<String, Object> payload = emitter.lastPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) payload.get("error");
        assertEquals("Chat stream failed", error.get("message"));
        assertFalse(error.containsKey("code"),
                "非业务异常不应编造错误码: " + error);
    }

    @Test
    @DisplayName("traceId/sessionId 为 null 时载荷里不出现这两个键")
    void nullIdentifiersAreOmitted() throws Exception {
        sendChatError.invoke(controller, emitter,
                new RuntimeException("boom"), null, null);

        Map<String, Object> payload = new LinkedHashMap<>(
                emitter.lastPayload());
        assertFalse(payload.containsKey("traceId"), "null 标识不应写入: " + payload);
        assertFalse(payload.containsKey("sessionId"), "null 标识不应写入: " + payload);
    }
}
