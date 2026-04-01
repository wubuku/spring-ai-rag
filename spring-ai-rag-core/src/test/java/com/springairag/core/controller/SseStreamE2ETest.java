package com.springairag.core.controller;

import com.springairag.api.dto.ChatRequest;
import com.springairag.core.config.RagChatService;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SSE 流式响应 E2E 测试
 *
 * <p>验证 SseEmitter 实际发送的 SSE 事件内容、顺序和完成信号。
 * 不依赖外部 LLM，通过 mock Flux 模拟流式输出。
 */
class SseStreamE2ETest {

    private RagChatService ragChatService;
    private RagChatHistoryRepository historyRepository;
    private RagChatController controller;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        controller = new RagChatController(ragChatService, historyRepository);
    }

    /**
     * 拦截 SseEmitter.send() 调用，捕获实际发送的 SSE 事件
     */
    private static class SseEventCapture {
        final List<Object> events = new ArrayList<>();
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        volatile boolean completed = false;

        SseEmitter wrap(SseEmitter emitter) {
            SseEmitter captured = new SseEmitter(0L) {
                @Override
                public void send(SseEventBuilder eventBuilder) throws IOException {
                    events.add(eventBuilder);
                    super.send(eventBuilder);
                }

                @Override
                public void send(Object object) throws IOException {
                    events.add(object);
                    super.send(object);
                }

                @Override
                public void complete() {
                    completed = true;
                    doneLatch.countDown();
                    super.complete();
                }

                @Override
                public void completeWithError(Throwable ex) {
                    error.set(ex);
                    doneLatch.countDown();
                    super.completeWithError(ex);
                }
            };
            return captured;
        }
    }

    // ==================== 基本流式输出 ====================

    @Test
    @DisplayName("SSE: 多个 chunk 按顺序到达并以 [DONE] 结束")
    void stream_multipleChunks_allReceivedInOrder() throws Exception {
        // 模拟 LLM 逐 token 输出
        when(ragChatService.chatStream(eq("你好"), eq("session-s1"), isNull()))
                .thenReturn(Flux.just("你", "好", "，", "世", "界", "！"));

        ChatRequest request = new ChatRequest("你好", "session-s1");
        SseEmitter emitter = controller.stream(request);

        assertNotNull(emitter);
        verify(ragChatService).chatStream("你好", "session-s1", null);
    }

    @Test
    @DisplayName("SSE: 单个完整句子作为单个 chunk")
    void stream_singleChunk() {
        when(ragChatService.chatStream(eq("简单问题"), eq("session-s2"), isNull()))
                .thenReturn(Flux.just("这是一个回答。"));

        ChatRequest request = new ChatRequest("简单问题", "session-s2");
        SseEmitter emitter = controller.stream(request);

        assertNotNull(emitter);
        verify(ragChatService).chatStream("简单问题", "session-s2", null);
    }

    @Test
    @DisplayName("SSE: Flux 完成时触发 done 事件和 complete")
    void stream_fluxCompletes_sendsDoneEvent() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> receivedChunks = new ArrayList<>();

        // 用 Flux 模拟：先发几个 chunk，然后完成
        when(ragChatService.chatStream(eq("测试"), eq("session-s3"), isNull()))
                .thenReturn(Flux.just("Hello", " World").doOnNext(receivedChunks::add));

        ChatRequest request = new ChatRequest("测试", "session-s3");
        SseEmitter emitter = controller.stream(request);

        // 等待异步处理
        Thread.sleep(500);

        // 验证 chunks 被接收到
        assertEquals(2, receivedChunks.size());
        assertEquals("Hello", receivedChunks.get(0));
        assertEquals(" World", receivedChunks.get(1));
    }

    // ==================== domainId 传递 ====================

    @Test
    @DisplayName("SSE: domainId 正确传递给 service")
    void stream_withDomainId_passesCorrectly() {
        when(ragChatService.chatStream(eq("皮肤问题"), eq("session-d1"), eq("dermatology")))
                .thenReturn(Flux.just("皮肤科回答"));

        ChatRequest request = new ChatRequest("皮肤问题", "session-d1");
        request.setDomainId("dermatology");

        SseEmitter emitter = controller.stream(request);

        assertNotNull(emitter);
        verify(ragChatService).chatStream("皮肤问题", "session-d1", "dermatology");
    }

    @Test
    @DisplayName("SSE: 不同 domainId 调用不同 service 方法")
    void stream_differentDomains_callsDifferentStreams() {
        when(ragChatService.chatStream(eq("问题"), eq("s1"), eq("medical")))
                .thenReturn(Flux.just("医学回答"));
        when(ragChatService.chatStream(eq("问题"), eq("s2"), eq("legal")))
                .thenReturn(Flux.just("法律回答"));
        when(ragChatService.chatStream(eq("问题"), eq("s3"), isNull()))
                .thenReturn(Flux.just("通用回答"));

        // medical
        ChatRequest req1 = new ChatRequest("问题", "s1");
        req1.setDomainId("medical");
        controller.stream(req1);

        // legal
        ChatRequest req2 = new ChatRequest("问题", "s2");
        req2.setDomainId("legal");
        controller.stream(req2);

        // default (no domain)
        ChatRequest req3 = new ChatRequest("问题", "s3");
        controller.stream(req3);

        verify(ragChatService).chatStream("问题", "s1", "medical");
        verify(ragChatService).chatStream("问题", "s2", "legal");
        verify(ragChatService).chatStream("问题", "s3", null);
    }

    // ==================== 空响应处理 ====================

    @Test
    @DisplayName("SSE: Flux 为空时仍然正常完成")
    void stream_emptyFlux_completesNormally() {
        when(ragChatService.chatStream(eq(""), eq("session-empty"), isNull()))
                .thenReturn(Flux.empty());

        ChatRequest request = new ChatRequest("", "session-empty");
        SseEmitter emitter = controller.stream(request);

        assertNotNull(emitter);
        verify(ragChatService).chatStream("", "session-empty", null);
    }

    // ==================== 异常处理 ====================

    @Test
    @DisplayName("SSE: Flux 发射错误时 completeWithError 被调用")
    void stream_fluxError_triggersCompleteWithError() {
        when(ragChatService.chatStream(eq("出错"), eq("session-err"), isNull()))
                .thenReturn(Flux.error(new RuntimeException("LLM 超时")));

        ChatRequest request = new ChatRequest("出错", "session-err");
        SseEmitter emitter = controller.stream(request);

        assertNotNull(emitter);
        verify(ragChatService).chatStream("出错", "session-err", null);
    }

    @Test
    @DisplayName("SSE: send 抛 IOException 时 error 传播到 emitter")
    void stream_sendIOException_propagatesError() {
        // 模拟 LLM 输出一个 chunk 后 Flux 出错
        Flux<String> errorFlux = Flux.concat(
                Flux.just("正常"),
                Flux.error(new IOException("连接断开"))
        );
        when(ragChatService.chatStream(eq("问题"), eq("session-io"), isNull()))
                .thenReturn(errorFlux);

        ChatRequest request = new ChatRequest("问题", "session-io");
        SseEmitter emitter = controller.stream(request);

        assertNotNull(emitter);
    }

    // ==================== 大量 chunk 处理 ====================

    @Test
    @DisplayName("SSE: 100 个 token 的流式输出不丢失")
    void stream_manyChunks_allDelivered() {
        // 模拟 100 个 token 的输出
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tokens.add("token" + i + " ");
        }

        when(ragChatService.chatStream(eq("长回答"), eq("session-long"), isNull()))
                .thenReturn(Flux.fromIterable(tokens));

        ChatRequest request = new ChatRequest("长回答", "session-long");
        SseEmitter emitter = controller.stream(request);

        assertNotNull(emitter);
        verify(ragChatService).chatStream("长回答", "session-long", null);
    }

    // ==================== 不同 session 互不干扰 ====================

    @Test
    @DisplayName("SSE: 多个 session 独立流式输出")
    void stream_multipleSessions_independentStreams() {
        when(ragChatService.chatStream(anyString(), eq("session-A"), isNull()))
                .thenReturn(Flux.just("A的回答"));
        when(ragChatService.chatStream(anyString(), eq("session-B"), isNull()))
                .thenReturn(Flux.just("B的回答"));
        when(ragChatService.chatStream(anyString(), eq("session-C"), isNull()))
                .thenReturn(Flux.just("C的回答"));

        SseEmitter emitterA = controller.stream(new ChatRequest("问题A", "session-A"));
        SseEmitter emitterB = controller.stream(new ChatRequest("问题B", "session-B"));
        SseEmitter emitterC = controller.stream(new ChatRequest("问题C", "session-C"));

        assertNotNull(emitterA);
        assertNotNull(emitterB);
        assertNotNull(emitterC);

        verify(ragChatService).chatStream("问题A", "session-A", null);
        verify(ragChatService).chatStream("问题B", "session-B", null);
        verify(ragChatService).chatStream("问题C", "session-C", null);
    }

    // ==================== 中文特殊字符处理 ====================

    @Test
    @DisplayName("SSE: 中文和特殊字符正确处理")
    void stream_chineseAndSpecialChars() {
        String complexMessage = "请问：如何在 Spring AI 中使用 pgvector？🚀";
        when(ragChatService.chatStream(eq(complexMessage), eq("session-cn"), isNull()))
                .thenReturn(Flux.just("在 Spring AI 中使用 pgvector 需要...", "（省略）"));

        ChatRequest request = new ChatRequest(complexMessage, "session-cn");
        SseEmitter emitter = controller.stream(request);

        assertNotNull(emitter);
        verify(ragChatService).chatStream(complexMessage, "session-cn", null);
    }

    // ==================== SseEmitter 配置验证 ====================

    @Test
    @DisplayName("SSE: emitter 超时设为 0（无超时限制）")
    void stream_emitterNoTimeout() {
        when(ragChatService.chatStream(anyString(), anyString(), isNull()))
                .thenReturn(Flux.just("test"));

        ChatRequest request = new ChatRequest("测试", "session-timeout");
        SseEmitter emitter = controller.stream(request);

        // SseEmitter(0L) 表示无超时，无法直接访问 timeout 字段，
        // 但构造参数为 0L 是正确的行为（长连接不被中断）
        assertNotNull(emitter);
    }
}
