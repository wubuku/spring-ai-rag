package com.springairag.core.chat;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.controller.RagChatController;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * /chat/stream 在**上游失败于 emitter 提交之后**时，客户端实际收到什么。
 *
 * <h2>为什么要有这条</h2>
 *
 * 2026-10-05 一次真实 provider 验收里，WebUI 的助手气泡写着 {@code Error: HTTP
 * 500}，而 {@code /chat/stream} 的失败路径此前没有任何测试钉过。当时的猜测
 * 是"上游 401 发生在流中途，客户端只拿得到一个笼统的 500"。
 * <strong>实测把这个猜测否掉了</strong>：先用一个只打印不断言的探针跑过，
 * 上游在任何事件发出之前失败时，响应是 <strong>200</strong>，失败以
 * {@code event:error} 帧送达，帧里带着机器可读的 {@code code}。
 *
 * <p>换句话说 {@code /chat/stream} 对流内失败<strong>不</strong>返回非 2xx。
 * 这条事实以前只存在于 controller 的实现里，没有任何测试保护它，而它恰恰是
 * 判断"客户端看到的 500 来自哪一段"的唯一依据——915 那次真实失败因此必然
 * 走的是 emitter 返回<strong>之前</strong>的那条路。
 *
 * <p>那条路归 {@code GlobalExceptionHandler} 管，本类不覆盖；这里只钉住已经
 * 测出来的那一半，不把没测过的写成结论。
 *
 * <p>测法沿用 {@link RagChatControllerStreamEventTypesTest} 的 standalone
 * MockMvc + {@code asyncDispatch} 夹具：assert 之前先读状态码，正是因为
 * {@code SseEmitter} 会把响应无条件提交成 200。
 */
class ChatStreamUpstreamFailureContractTest {

    private RagChatService ragChatService;
    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ragChatService = mock(RagChatService.class);
        var scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.unscoped());
        RagChatController controller = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                new RagSseProperties(),
                scopeResolver,
                mock(AuditLogService.class));
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .build();
    }

    /** 让上游在发出任何事件之前就失败，读回完整响应。 */
    private MvcResult streamWithFailingUpstream(Throwable failure) throws Exception {
        when(ragChatService.chatEvents(any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.error(failure));
        MvcResult started = mockMvc.perform(post("/rag/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "问题", "sessionId": "session-1"}
                                """))
                .andReturn();
        return mockMvc.perform(asyncDispatch(started)).andReturn();
    }

    private static String body(MvcResult result) throws Exception {
        return new String(
                result.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
    }

    /**
     * 流内上游失败：状态码是 200，失败以 error 帧送达，并带上错误码。
     *
     * <p>三个断言各自只声称一件事：状态码是 200（失败<strong>不</strong>走
     * 非 2xx）、失败确实以 error 事件送达（不是静默的 200 空流）、错误码
     * 可被客户端读到（不是一句人话）。
     */
    @Test
    void upstreamFailureBeforeAnyEvent_isReportedAs200WithAnErrorEventCarryingTheCode()
            throws Exception {
        MvcResult result = streamWithFailingUpstream(new RagException(
                ErrorCode.UNAUTHORIZED, "provider rejected the key"));
        String body = body(result);

        assertEquals(200, result.getResponse().getStatus(),
                () -> "stream failures must not turn into a non-2xx status:\n" + body);
        assertTrue(body.contains("event:error"),
                () -> "no error event in:\n" + body);
        assertTrue(body.contains("\"code\":\"UNAUTHORIZED\""),
                () -> "error event does not carry a machine-readable code:\n" + body);
    }

    /**
     * 对照：非 RagException 的上游异常同样以 error 帧送达，而不是变成 500。
     *
     * <p>这一条是上面那条的阴性对照——两条合起来才说明"是 200 + error 帧"
     * 是<strong>形状</strong>而不是"因为这次恰好是 RagException 才 200"。
     */
    @Test
    void aNonRagExceptionUpstreamFailureTakesTheSameSurface() throws Exception {
        MvcResult result = streamWithFailingUpstream(
                new IllegalStateException("boom"));
        String body = body(result);

        assertEquals(200, result.getResponse().getStatus(),
                () -> "stream failures must not turn into a non-2xx status:\n" + body);
        assertTrue(body.contains("event:error"),
                () -> "no error event in:\n" + body);
    }
}
