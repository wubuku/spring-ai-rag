package com.springairag.core.chat;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.controller.RagChatController;
import com.springairag.core.repository.RagChatHistoryRepository;

import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RagChatController.stream 未键控 SSE 长尾（Batch 502，JaCoCo 驱
 * 动，测试置于 core.chat 包以构造 package-private 的 ChatEvent 记
 * 录）：内容增量 + Completed 完成链、Failed 事件终止、错误传播经
 * sendChatError、空白 sessionId 自动补齐。
 *
 * <h2>Batch 950：三条用例原来证明不了它们标题里写的事</h2>
 *
 * {@code unkeyedStreamEmitsContentDeltaThenCompleted}、
 * {@code streamErrorIsMappedToChatErrorEvent} 与
 * {@code failedEventTerminatesStreamWithoutEmitterComplete} 的唯一断言都是
 * {@code assertNotNull(emitter)}，而 {@code stream} 无条件
 * {@code SseEmitters.create()} 就把 emitter return 了。于是：
 * <ul>
 *   <li>把 {@code ContentDelta} 的事件名或载荷改错——第一条照样绿，可它叫
 *       "EmitsContentDeltaThenCompleted"；</li>
 *   <li>把 {@code sendChatError} 整个删掉——第二条照样绿，可它叫
 *       "ErrorIsMappedToChatErrorEvent"；</li>
 *   <li>把 {@code Failed} 的 code/message 丢掉——第三条照样绿。</li>
 * </ul>
 *
 * <p>没有容器时 {@code SseEmitter} 没有 handler，{@code send} 只是暂存，
 * 所以只能拿到"不是 null"。这里改走 standalone MockMvc + {@code asyncDispatch}
 * 读回真实响应体——与 {@code SseStreamE2ETest}（Batch 945 / 949）、
 * {@code RagChatControllerSseLifecycleTailTest}（Batch 945）同一套路。
 *
 * <h3>一个不可观测、不再声称覆盖的点</h3>
 *
 * 原名 {@code failedEventTerminatesStreamWithoutEmitterComplete} 说的是
 * "Failed 分支自己不调 {@code emitter.complete()}"。这句话在响应体层面<b>没有
 * 任何可观测差异</b>：{@code Failed} 分支确实只做了
 * {@code terminal.compareAndSet(false, true)} 与 {@code heartbeat.stop()}，
 * 但它调用的 {@code sendChatError} 末尾就会 {@code emitter.complete()}。是
 * 哪一层调的，响应体里看不出来。用例已改名为 {@code
 * failedEventEmitsErrorFrameAndTerminates}，只声称它真正能验的那部分。
 */
class RagChatControllerStreamEventsTest {

    private RagChatService ragChatService;
    private RagChatController controller;
    private MockMvc mockMvc;
    private MockHttpServletRequest httpRequest;
    private final MockHttpServletResponse httpResponse =
            new MockHttpServletResponse();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ragChatService = mock(RagChatService.class);
        var scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.unscoped());
        controller = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                new RagSseProperties(),
                scopeResolver,
                mock(AuditLogService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        httpRequest = new MockHttpServletRequest("POST", "/ask/stream");
    }

    /**
     * 跑一次真实的 HTTP 流式请求，把发出去的 SSE 帧读回来。
     *
     * <p>流可能在 {@code perform} 返回之前就同步收尾（源是 {@code Flux.empty()}
     * 之类），这时 MockMvc 已经把请求结算掉、{@code isAsyncStarted()} 为 false，
     * 再去 {@code asyncDispatch} 会报 "Async not started"。两种形态读的是同一个
     * 响应体，所以只判断一次（与 {@code SseStreamE2ETest#streamBody} 同）。
     */
    private String streamBody(String message, String sessionId) throws Exception {
        MvcResult started = mockMvc.perform(post("/rag/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message
                                + "\",\"sessionId\":\"" + sessionId + "\"}"))
                .andReturn();
        MvcResult completed = started;
        if (started.getRequest().isAsyncStarted()) {
            started.getAsyncResult(10_000);
            completed = mockMvc.perform(asyncDispatch(started)).andReturn();
        }
        return new String(
                completed.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
    }

    private static List<String> eventNames(String body) {
        return java.util.Arrays.stream(body.split("\n\n"))
                .map(frame -> frame.lines()
                        .filter(line -> line.startsWith("event:"))
                        .map(line -> line.substring("event:".length()).trim())
                        .findFirst()
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static void assertEmitted(String body, String eventName) {
        List<String> names = eventNames(body);
        assertTrue(names.contains(eventName),
                () -> "响应体里没有 " + eventName + " 事件，只有 " + names + "：\n" + body);
    }

    private static void assertNoEvent(String body, String eventName) {
        List<String> names = eventNames(body);
        assertFalse(names.contains(eventName),
                () -> "响应体里不该有 " + eventName + " 事件，却有 " + names + "：\n" + body);
    }

    /** 取出指定事件的 data 载荷原文。 */
    private static String dataOf(String body, String eventName) {
        for (String frame : body.split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : frame.lines().toList()) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()));
                }
            }
            if (eventName.equals(name) && data.length() > 0) {
                return data.toString().trim();
            }
        }
        throw new AssertionError("响应体里没有 " + eventName + " 事件：\n" + body);
    }

    /** 取出所有 content 事件的增量文本，保持出现顺序。 */
    private static List<String> contentChunks(String body) {
        List<String> chunks = new ArrayList<>();
        for (String frame : body.split("\n\n")) {
            String name = null;
            String data = null;
            for (String line : frame.lines().toList()) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data = line.substring("data:".length()).trim();
                }
            }
            if (!"content".equals(name) || data == null) {
                continue;
            }
            try {
                chunks.add(new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(data).at("/choices/0/delta/content").asText());
            } catch (Exception error) {
                throw new AssertionError("content 帧不是合法 JSON：" + data, error);
            }
        }
        return chunks;
    }

    @Test
    void unkeyedStreamEmitsContentDeltaThenCompleted() throws Exception {
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.just(
                        new ChatEvent.ContentDelta("你好"),
                        new ChatEvent.Completed(
                                "trace-1", "session-1", null, null,
                                ChatMode.PLAIN, Map.of(), "STOP",
                                List.of(), Map.of())));

        String body = streamBody("问题", "session-1");

        assertEquals(List.of("你好"), contentChunks(body),
                () -> "content 增量不对：\n" + body);
        assertEmitted(body, "done");
        // done 载荷带上本会话的 sessionId 与 traceId。
        assertTrue(dataOf(body, "done").contains("\"sessionId\":\"session-1\""),
                () -> "done 载荷没有 sessionId：\n" + body);
        assertTrue(dataOf(body, "done").contains("\"traceId\":\"trace-1\""),
                () -> "done 载荷没有 traceId：\n" + body);
        verify(ragChatService).chatEvents(
                any(ChatRequest.class), any(), isNull());
    }

    @Test
    void streamErrorIsMappedToChatErrorEvent() throws Exception {
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.error(
                        new IllegalStateException("stream blew up")));

        String body = streamBody("问题", "session-1");

        assertEmitted(body, "error");
        String payload = dataOf(body, "error");
        assertTrue(payload.contains("stream blew up"),
                () -> "error 载荷丢了原始消息：\n" + body);
        // 失败流不该同时报成功。
        assertNoEvent(body, "done");
        assertNoEvent(body, "content");
        verify(ragChatService).chatEvents(
                any(ChatRequest.class), any(), isNull());
    }

    @Test
    void failedEventEmitsErrorFrameAndTerminates() throws Exception {
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.just(
                        new ChatEvent.Failed(
                                "trace-1", "session-1",
                                "INTERNAL_ERROR", "failed mid-stream")));

        String body = streamBody("问题", "session-1");

        assertEmitted(body, "error");
        String payload = dataOf(body, "error");
        assertTrue(payload.contains("INTERNAL_ERROR"),
                () -> "error 载荷丢了 code：\n" + body);
        assertTrue(payload.contains("failed mid-stream"),
                () -> "error 载荷丢了 message：\n" + body);
        // Failed 是终态，成功帧不该跟着来。
        assertNoEvent(body, "done");
        verify(ragChatService).chatEvents(
                any(ChatRequest.class), any(), isNull());
    }

    @Test
    void blankSessionIdIsAutoGeneratedInPlace() {
        ChatRequest request = new ChatRequest("问题", "  ");
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.empty());

        controller.stream(request, httpRequest, httpResponse);

        assertNotNull(request.getSessionId());
        assertTrue(request.getSessionId().length() >= 1
                && !request.getSessionId().isBlank());
    }

}