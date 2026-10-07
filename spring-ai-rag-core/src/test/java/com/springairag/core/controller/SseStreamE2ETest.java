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

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 跑一次真实的 HTTP 流式请求，把发出去的 SSE 帧读回来。
     *
     * <p>直接调 {@code controller.stream(request, null, null)} 时 {@code SseEmitter} 没有
     * handler，{@code send} 只是暂存——所以这个文件里原来有三条用例（唯一断言是
     * {@code assertNotNull(emitter)}）无论控制器怎么坏都绿。套路与
     * {@code RagChatControllerStreamEventTypesTest}（Batch 897）一致。
     *
     * <p>Batch 949 补一条：流可能在 {@code perform} 返回之前就同步收尾（源是
     * {@code Flux.empty()} 之类），MockMvc 这时已经把请求结算掉，
     * {@code isAsyncStarted()} 为 false，再去 {@code asyncDispatch} 就会失败。
     * 两种形态都读同一个响应体，所以这里只做一次判断。
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

    /**
     * Batch 949：按帧解析出的事件名序列。
     *
     * <p>不按子串找——Batch 897 抓过一次：{@code "event:tool_startX".contains
     * ("event:tool_start")} 成立，事件名改错了用例照样绿。{@code event:} 那一行
     * 不带收尾标记，必须解析。
     */
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

    private static void assertContains(String body, String fragment) {
        assertTrue(body.contains(fragment),
                () -> "SSE body does not carry " + fragment + ":\n" + body);
    }

    private static void assertNoContains(String body, String fragment) {
        assertFalse(body.contains(fragment),
                () -> "SSE body must not carry " + fragment + ":\n" + body);
    }

    private static void assertEmitted(String body, String eventName) {
        List<String> names = eventNames(body);
        assertTrue(names.contains(eventName),
                () -> "SSE body carries no " + eventName + " event, only " + names + ":\n" + body);
    }

    private static void assertNoEvent(String body, String eventName) {
        List<String> names = eventNames(body);
        assertFalse(names.contains(eventName),
                () -> "SSE body must not carry a " + eventName + " event, but has " + names + ":\n" + body);
    }

    /**
     * 按帧解析出所有 {@code content} 事件的增量文本，保持出现顺序。
     *
     * <p>Batch 949：**不能**拿原文子串去匹配。第一版就是这么写的，结果被两件事
     * 同时打脸：
     * <ul>
     *   <li>Jackson 把非 BMP 字符（🚀）写成两个 U+XXXX 的代理对转义。
     *       那仍然是合法 JSON、客户端解码后还是 🚀，但原文子串永远匹配不上——
     *       用例红了，而线上其实没坏。（这里刻意不写反斜杠加 u 的转义字面量：
     *       Java 的 Unicode 转义在词法分析之前生效，注释里写那种形式会让整个
     *       文件编译失败。）</li>
     *   <li>反过来，只验"某个子串出现过"也钉不住顺序，更钉不住"不多发"。</li>
     * </ul>
     *
     * <p>所以这里把每帧的 {@code data:} 当 JSON 解析，沿
     * {@code choices[0].delta.content} 取值。调用方直接拿它和期望列表做
     * {@code assertEquals} —— 顺序、条数、内容一次全钉住，而且对转义免疫。
     */
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
                chunks.add(MAPPER.readTree(data)
                        .at("/choices/0/delta/content").asText());
            } catch (Exception error) {
                throw new AssertionError("content 帧不是合法 JSON：" + data, error);
            }
        }
        return chunks;
    }

    private static long countEvents(String body, String eventName) {
        return eventNames(body).stream().filter(eventName::equals).count();
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

        // Batch 949：原来是直调 controller.stream(...) 再 assertNotNull(emitter)。
        // 用例名和 @DisplayName 都说的是"多块**按序**到达并以 done 收尾"，而顺序
        // 与收尾一个字都没验：把事件映射改错、把 concatMap 换成会乱序的算子，它
        // 照样绿。改走 HTTP 通道逐帧读回来。
        String body = streamBody("你好", "session-s1", null);

        // 顺序、条数、内容一次全钉住：把 concatMap 换成会乱序的算子、或者多发/
        // 少发一块，用例都会红。子串匹配做不到这一点。
        assertEquals(List.of("你", "好", "，", "世", "界", "！"),
                contentChunks(body), () -> "content 增量不对：\n" + body);
        assertEmitted(body, "done");
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
    void stream_withDomainId_passesCorrectly() throws Exception {
        stubStream("皮肤问题", "session-d1", "dermatology", Flux.just("皮肤科回答"));

        // Batch 949：原来直调 controller.stream(...) 再 assertNotNull(emitter)。
        // 改走 HTTP 之后 domainId 不只"到了 service"（那是 verifyStream 一直在做的），
        // 还得真的能驱动一次完整的 scope 解析与出帧。
        String body = streamBody("皮肤问题", "session-d1", "dermatology");

        assertEquals(List.of("皮肤科回答"), contentChunks(body),
                () -> "content 增量不对：\n" + body);
        assertEmitted(body, "done");
        assertContains(body, "\"sessionId\":\"session-d1\"");
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
    void stream_emptyFlux_completesNormally() throws Exception {
        // 第一版这里给的是空消息 ""，把"零增量"和"用户消息为空白"混成了一个
        // 维度：后者会在参数校验那一层就短路掉，压根走不到流式分支，响应体是
        // 空的，"completes normally" 无从谈起。消息必须非空，隔离出来的才是
        // 真正要验的东西——源不发任何增量时，流仍要正常收尾。
        stubStream("问题", "session-empty", null, Flux.empty());

        // 没有 content 帧、没有 error 帧，但**必须有** done 帧：少了 done
        // 就是流没正常收尾，而原来这条用例只有 assertNotNull(emitter)。
        String body = streamBody("问题", "session-empty", null);

        assertEquals(List.of(), contentChunks(body),
                () -> "空流不该有 content 增量：\n" + body);
        assertNoEvent(body, "content");
        assertNoEvent(body, "error");
        assertEmitted(body, "done");
        verifyStream("问题", "session-empty", null);
    }

    // ==================== Error Handling ====================

    @Test
    @DisplayName("SSE: error event emitted when Flux errors")
    void stream_fluxError_triggersCompleteWithError() throws Exception {
        stubStream("出错", "session-err", null,
                Flux.error(new RuntimeException("LLM 超时")));

        // Batch 949：原来只有 assertNotNull(emitter)，而用例名说的是
        // "completeWithError 被调用"。Flux.error 之后 concatWith 的 Completed
        // 不会到达，所以这里既该有 error 帧、也**不该**有 done 帧。
        String body = streamBody("出错", "session-err", null);

        assertEmitted(body, "error");
        assertContains(body, "LLM 超时");
        assertNoEvent(body, "done");
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
    void stream_manyChunks_allDelivered() throws Exception {
        // 模拟 100 个 token 的输出
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tokens.add("token" + i + " ");
        }

        stubStream("长回答", "session-long", null, Flux.fromIterable(tokens));

        // Batch 949：@DisplayName 写的是 "100 tokens streamed without loss"，
        // 原来只有 assertNotNull(emitter) —— "无损"完全没验。现在同时钉住
        // 三件事：每块都在、次序没乱、content 帧数恰好 100（不多不少）。
        String body = streamBody("长回答", "session-long", null);

        assertEquals(tokens, contentChunks(body),
                () -> "100 块增量与发出的不一致；body 长度 " + body.length());
        assertEmitted(body, "done");
        verifyStream("长回答", "session-long", null);
    }

    // ==================== Independent Sessions ====================

    @Test
    @DisplayName("SSE: multiple sessions stream independently")
    void stream_multipleSessions_independentStreams() throws Exception {
        stubStream("问题A", "session-A", null, Flux.just("A的回答"));
        stubStream("问题B", "session-B", null, Flux.just("B的回答"));
        stubStream("问题C", "session-C", null, Flux.just("C的回答"));

        // Batch 949：原来是三句 assertNotNull(emitterX)。"独立"要靠内容来证：
        // 每个响应体里只该出现自己那一份答案与 sessionId。
        String a = streamBody("问题A", "session-A", null);
        String b = streamBody("问题B", "session-B", null);
        String c = streamBody("问题C", "session-C", null);

        assertContains(a, "A的回答");
        assertNoContains(a, "B的回答");
        assertNoContains(a, "C的回答");
        assertContains(b, "B的回答");
        assertNoContains(b, "A的回答");
        assertNoContains(b, "C的回答");
        assertContains(c, "C的回答");
        assertNoContains(c, "A的回答");
        assertNoContains(c, "B的回答");

        // done 帧各自带自己的 sessionId。
        assertContains(a, "\"sessionId\":\"session-A\"");
        assertContains(b, "\"sessionId\":\"session-B\"");
        assertContains(c, "\"sessionId\":\"session-C\"");
    }

    // ==================== Chinese and Special Character Handling ====================

    @Test
    @DisplayName("SSE: Chinese and special characters survive the wire as UTF-8")
    void stream_chineseAndSpecialChars() throws Exception {
        String complexMessage = "请问：如何在 Spring AI 中使用 pgvector？🚀";
        stubStream(complexMessage, "session-cn", null,
                Flux.just("在 Spring AI 中使用 pgvector 需要...", "（省略）🚀"));

        // Batch 949：@DisplayName 说 "handled correctly"，原来只有
        // assertNotNull(emitter)。现在这条同时守住**出站**编码：全角括号、中文、
        // 以及 4 字节的补充平面 emoji 都必须原样落到响应体里。
        //
        // 先自检字面量：码点比对。否则响应体与断言同时被编码问题改坏时 contains
        // 会成立，用例变绿——这正是 Batch 948 在 /v1/chat/completions 上踩过的坑。
        assertEquals("🚀", new String(Character.toChars(0x1F680)),
                "本用例的 emoji 字面量被改动了");
        assertEquals("（省略）", new String(new char[] {0xFF08, 0x7701, 0x7565, 0xFF09}),
                "本用例的全角括号字面量被改动了");

        String body = streamBody(complexMessage, "session-cn", null);

        assertEquals(List.of("在 Spring AI 中使用 pgvector 需要...", "（省略）🚀"),
                contentChunks(body),
                () -> "非 ASCII / 补充平面字符没能原样往返：\n" + body);
        // 上一条是"解码之后"的对不对；这一条钉的是"线上真的是 UTF-8 字节"。
        // 全角括号在响应体里以原字符出现，说明编码链没把它写成 `?`（Batch 948
        // 在 /v1/chat/completions 上实测到的就是那种形态），也没被 Jackson 全量
        // 转义成 U+XXXX 形式。emoji 则相反：Jackson 会把非 BMP 字符写成两个
        // U+XXXX 的代理对序列，那是合法 JSON、解码后仍是 🚀，所以由上一条负责。
        // （这里刻意不写反斜杠加 u 的转义字面量：Java 的 Unicode 转义在词法分析
        // 之前就生效，注释里写那种形式会让整个文件编译失败。）
        assertContains(body, "（省略）");
        assertContains(body, "\"sessionId\":\"session-cn\"");
        // U+FFFD 是编码链路走错时的典型产物，这里不该出现。
        assertNoContains(body, "\uFFFD");
        // 入站方向：带 emoji 的请求消息必须原样到达 service（桩按消息匹配，打不中
        // 就意味着 chatEvents 返回 null，后面的帧断言会变成断言一个 NPE）。
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
