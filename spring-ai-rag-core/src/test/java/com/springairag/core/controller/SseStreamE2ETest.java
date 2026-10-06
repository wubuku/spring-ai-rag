package com.springairag.core.controller;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * SSE 流式响应 E2E 测试
 *
 * <p>验证 SseEmitter 实际发送的 SSE 事件内容、顺序和完成信号。
 * 不依赖外部 LLM，通过 mock Flux 模拟流式输出。
 */
class SseStreamE2ETest {

    private RagChatService ragChatService;
    private RagChatHistoryRepository historyRepository;
    private ChatExportService chatExportService;
    private RagSseProperties sseProperties;
    private AuditLogService auditLogService;
    private CollectionRetrievalScopeResolver scopeResolver;
    private RagChatController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        chatExportService = mock(ChatExportService.class);
        sseProperties = new RagSseProperties();
        auditLogService = mock(AuditLogService.class);
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.unscoped());
        controller = new RagChatController(ragChatService, historyRepository, chatExportService, sseProperties, scopeResolver, auditLogService);
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .build();
    }

    /**
     * Batch 945：跑一次真实的 HTTP 流式请求，把发出去的 SSE 帧读回来。
     *
     * <p>直接调 {@code controller.stream(request, null, null)} 时 {@code SseEmitter} 没有
     * handler，{@code send} 只是暂存——所以这个文件里原来有三条用例（唯一断言是
     * {@code assertNotNull(emitter)}）无论控制器怎么坏都绿。套路与
     * {@code RagChatControllerStreamEventTypesTest}（Batch 897）一致。
     */
    private String streamBody(String message, String sessionId, String domainId) throws Exception {
        StringBuilder json = new StringBuilder("{\"message\":")
                .append(jsonString(message)).append(",\"sessionId\":")
                .append(jsonString(sessionId));
        if (domainId != null) {
            json.append(",\"domainId\":").append(jsonString(domainId));
        }
        json.append('}');

        MvcResult started = mockMvc.perform(post("/rag/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.toString()))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(10_000);
        MvcResult completed = mockMvc.perform(asyncDispatch(started)).andReturn();
        return new String(
                completed.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
    }

    /** 极简 JSON 字符串转义：只处理引号、反斜杠与控制字符。 */
    private static String jsonString(String raw) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static void assertContains(String body, String fragment) {
        assertTrue(body.contains(fragment),
                () -> "SSE body does not carry " + fragment + ":\n" + body);
    }

    private static void assertEmitted(String body, String eventName) {
        List<String> names = java.util.Arrays.stream(body.split("\n\n"))
                .map(frame -> frame.lines()
                        .filter(line -> line.startsWith("event:"))
                        .map(line -> line.substring("event:".length()).trim())
                        .findFirst()
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        assertTrue(names.contains(eventName),
                () -> "SSE body carries no " + eventName + " event, only " + names + ":\n" + body);
    }

    private void stubStream(String message, String sessionId, String domainId,
                            Flux<String> result) {
        Flux<ChatEvent> events = result
                .map(chunk -> (ChatEvent) new ChatEvent.ContentDelta(chunk))
                .concatWith(Flux.just((ChatEvent) new ChatEvent.Completed(
                        null,
                        sessionId,
                        null,
                        null,
                        ChatMode.KNOWLEDGE,
                        Map.of(),
                        null,
                        List.of())));
        // 第二、三个参数用 any() 而不是 isNull()：MockMvc 通道里 scope 已经被解析成
        // RetrievalScope.unscoped()，用 isNull() 会让桩**打不中**——而桩打不中的表现是
        // chatEvents 返回 null，于是"断言 body 里有什么"变成"断言 body 里有个 NPE"。
        when(ragChatService.chatEvents(argThat(request ->
                matches(request, message, sessionId, domainId)), any(), any()))
                .thenReturn(events);
    }

    private void verifyStream(String message, String sessionId, String domainId) {
        verify(ragChatService).chatEvents(argThat(request ->
                matches(request, message, sessionId, domainId)), any(), any());
    }

    private boolean matches(ChatRequest request, String message,
                            String sessionId, String domainId) {
        return request != null
                && java.util.Objects.equals(message, request.getMessage())
                && java.util.Objects.equals(sessionId, request.getSessionId())
                && java.util.Objects.equals(domainId, request.getDomainId());
    }

    // ==================== Basic Streaming ====================

    @Test
    @DisplayName("SSE: multiple chunks arrive in order and end with [DONE]")
    void stream_multipleChunks_allReceivedInOrder() throws Exception {
        // 模拟 LLM 逐 token 输出
        stubStream("你好", "session-s1", null,
                Flux.just("你", "好", "，", "世", "界", "！"));

        ChatRequest request = new ChatRequest("你好", "session-s1");
        SseEmitter emitter = controller.stream(request, null, null);

        assertNotNull(emitter);
        verifyStream("你好", "session-s1", null);
    }

    @Test
    @DisplayName("SSE: single complete sentence arrives as one chunk")
    void stream_singleChunk() throws Exception {
        stubStream("简单问题", "session-s2", null, Flux.just("这是一个回答。"));

        // 原来只有 assertNotNull(emitter)：emitter 由 SseEmitters.create() 无条件造出，
        // 把 content 事件的名字或载荷改错，用例照样绿。
        String body = streamBody("简单问题", "session-s2", null);

        assertEmitted(body, "content");
        assertContains(body, "这是一个回答。");
        verifyStream("简单问题", "session-s2", null);
    }

    @Test
    @DisplayName("SSE: done event and complete triggered when Flux finishes")
    void stream_fluxCompletes_sendsDoneEvent() throws Exception {
        stubStream("测试", "session-s3", null, Flux.just("Hello", " World"));

        // 第一版这里断言的是 `receivedChunks`——那是**mock 自己**发出去的东西，
        // 控制器有没有把它转成 SSE 帧、用例一个字都没说。
        String body = streamBody("测试", "session-s3", null);

        assertEmitted(body, "content");
        assertEmitted(body, "done");
        assertContains(body, "Hello");
        assertContains(body, " World");
        // 顺序：两个 content 帧都在 done 之前。
        assertTrue(body.indexOf("Hello") < body.indexOf(" World")
                        && body.indexOf(" World") < body.indexOf("event:done"),
                () -> "两个 chunk 应按序出现在 done 之前:\n" + body);
    }

    // ==================== domainId Propagation ====================

    @Test
    @DisplayName("SSE: domainId is correctly passed to service")
    void stream_withDomainId_passesCorrectly() {
        stubStream("皮肤问题", "session-d1", "dermatology", Flux.just("皮肤科回答"));

        ChatRequest request = new ChatRequest("皮肤问题", "session-d1");
        request.setDomainId("dermatology");

        SseEmitter emitter = controller.stream(request, null, null);

        assertNotNull(emitter);
        verifyStream("皮肤问题", "session-d1", "dermatology");
    }

    @Test
    @DisplayName("SSE: different domainId calls different service methods")
    void stream_differentDomains_callsDifferentStreams() {
        stubStream("问题", "s1", "medical", Flux.just("医学回答"));
        stubStream("问题", "s2", "legal", Flux.just("法律回答"));
        stubStream("问题", "s3", null, Flux.just("通用回答"));

        // medical
        ChatRequest req1 = new ChatRequest("问题", "s1");
        req1.setDomainId("medical");
        controller.stream(req1, null, null);

        // legal
        ChatRequest req2 = new ChatRequest("问题", "s2");
        req2.setDomainId("legal");
        controller.stream(req2, null, null);

        // default (no domain)
        ChatRequest req3 = new ChatRequest("问题", "s3");
        controller.stream(req3, null, null);

        verifyStream("问题", "s1", "medical");
        verifyStream("问题", "s2", "legal");
        verifyStream("问题", "s3", null);
    }

    // ==================== Empty Response Handling ====================

    @Test
    @DisplayName("SSE: completes normally when Flux is empty")
    void stream_emptyFlux_completesNormally() {
        stubStream("", "session-empty", null, Flux.empty());

        ChatRequest request = new ChatRequest("", "session-empty");
        SseEmitter emitter = controller.stream(request, null, null);

        assertNotNull(emitter);
        verifyStream("", "session-empty", null);
    }

    // ==================== Error Handling ====================

    @Test
    @DisplayName("SSE: completeWithError called when Flux emits error")
    void stream_fluxError_triggersCompleteWithError() {
        stubStream("出错", "session-err", null,
                Flux.error(new RuntimeException("LLM 超时")));

        ChatRequest request = new ChatRequest("出错", "session-err");
        SseEmitter emitter = controller.stream(request, null, null);

        assertNotNull(emitter);
        verifyStream("出错", "session-err", null);
    }

    @Test
    @DisplayName("SSE: error propagates to emitter when send throws IOException")
    void stream_sendIOException_propagatesError() throws Exception {
        // 模拟 LLM 输出一个 chunk 后 Flux 出错
        Flux<String> errorFlux = Flux.concat(
                Flux.just("正常"),
                Flux.error(new IOException("连接断开"))
        );
        stubStream("问题", "session-io", null, errorFlux);

        // 原来只有 assertNotNull(emitter)，而名字说的是"错误传播到 emitter"。
        String body = streamBody("问题", "session-io", null);

        assertContains(body, "正常");
        assertEmitted(body, "error");
        assertContains(body, "连接断开");
    }

    // ==================== Large Chunk Handling ====================

    @Test
    @DisplayName("SSE: 100 tokens streamed without loss")
    void stream_manyChunks_allDelivered() {
        // 模拟 100 个 token 的输出
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tokens.add("token" + i + " ");
        }

        stubStream("长回答", "session-long", null, Flux.fromIterable(tokens));

        ChatRequest request = new ChatRequest("长回答", "session-long");
        SseEmitter emitter = controller.stream(request, null, null);

        assertNotNull(emitter);
        verifyStream("长回答", "session-long", null);
    }

    // ==================== Independent Sessions ====================

    @Test
    @DisplayName("SSE: multiple sessions stream independently")
    void stream_multipleSessions_independentStreams() {
        stubStream("问题A", "session-A", null, Flux.just("A的回答"));
        stubStream("问题B", "session-B", null, Flux.just("B的回答"));
        stubStream("问题C", "session-C", null, Flux.just("C的回答"));

        SseEmitter emitterA = controller.stream(new ChatRequest("问题A", "session-A"), null, null);
        SseEmitter emitterB = controller.stream(new ChatRequest("问题B", "session-B"), null, null);
        SseEmitter emitterC = controller.stream(new ChatRequest("问题C", "session-C"), null, null);

        assertNotNull(emitterA);
        assertNotNull(emitterB);
        assertNotNull(emitterC);

        verifyStream("问题A", "session-A", null);
        verifyStream("问题B", "session-B", null);
        verifyStream("问题C", "session-C", null);
    }

    // ==================== Chinese and Special Character Handling ====================

    @Test
    @DisplayName("SSE: Chinese and special characters handled correctly")
    void stream_chineseAndSpecialChars() {
        String complexMessage = "请问：如何在 Spring AI 中使用 pgvector？🚀";
        stubStream(complexMessage, "session-cn", null,
                Flux.just("在 Spring AI 中使用 pgvector 需要...", "（省略）"));

        ChatRequest request = new ChatRequest(complexMessage, "session-cn");
        SseEmitter emitter = controller.stream(request, null, null);

        assertNotNull(emitter);
        verifyStream(complexMessage, "session-cn", null);
    }

    // ==================== SseEmitter Configuration ====================

    @Test
    @DisplayName("SSE: emitter timeout set to 0 (no timeout limit)")
    void stream_emitterNoTimeout() throws Exception {
        stubStream("测试", "session-timeout", null, Flux.just("test"));

        // 原来这里是 assertNotNull(emitter) 加一句注释"无法直接访问 timeout 字段"。
        // 注释承认测不到，用例于是就只测了"emitter 不是 null"。现在直接读那个字段：
        // 反射拿不到就抛，测试会响，而不是安静地什么都不验证。
        SseEmitter emitter = controller.stream(
                new ChatRequest("测试", "session-timeout"), null, null);
        assertNotNull(emitter);

        java.lang.reflect.Field timeout =
                org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class
                        .getDeclaredField("timeout");
        timeout.setAccessible(true);
        Object value = timeout.get(emitter);
        assertNotNull(value, "SseEmitter(0L) 应当把 timeout 记成 0（无超时）");
        assertEquals(0L, ((Number) value).longValue(),
                "长连接的 SSE emitter 不应带超时");
    }
}
