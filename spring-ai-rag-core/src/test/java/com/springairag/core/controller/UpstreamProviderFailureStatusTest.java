package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游 provider 认证失败时，客户端实际收到什么状态码。
 *
 * <h2>这个类回答的是 2026-10-05 那个悬案</h2>
 *
 * 那次真实 provider 验收里，后端日志写着
 * {@code ERROR GlobalExceptionHandler - Request failed: 401 - {"code":30014…}}
 * 而 WebUI 收到的是 {@code Error: HTTP 500}。日志里那个 401 读起来像是
 * "后端知道是 401、在翻译过程中丢了"，于是我按这个假设追了很久。
 * <strong>它是错的，而且错在第一环</strong>：那一行里根本没有状态码，
 * 只有异常消息——而 Spring AI 的 {@code NonTransientAiException} 消息就是
 * {@code 401 - {…}}，因为 provider 回的就是 401。日志格式是
 * {@code "Request failed: {}"}，只有一个占位符，于是 401 落在了状态码的位置上。
 *
 * <p>事实是：{@code NonTransientAiException} 不是 {@code RagException}，
 * 所以它落在 {@code GlobalExceptionHandler} 的兜底分支里，而那个分支
 * <strong>无条件</strong>返回 500。后端从头到尾没有"决定"过它是 401。
 *
 * <p>为什么这值得钉住：那句 {@code assertThrows} 级别的断言证明不了客户端
 * 拿到什么——异常离开 controller 之后，状态码由异常处理器决定，而单元测试
 * 从不经过它。keyed SSE 分支的重抛早已由
 * {@code RagChatControllerKeyedSseTest#streamFailsOperationWhenPreparedChainFails}
 * 钉住，缺的是这最后一段。
 *
 * <p>状态码维持 500 是有意的：一个被拒的是 <strong>provider 的</strong>凭据，
 * 不是调用方的，回 401 会告诉一个 API 客户端"你的 key 坏了"——而它没坏。
 * 上游失败该不该走 502/503 是契约问题，记在账本上等拍板，不在这里决定。
 */
class UpstreamProviderFailureStatusTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;
    private ch.qos.logback.core.read.ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger handlerLogger;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        ((MockHttpServletRequest) request).setRequestURI("/api/v1/rag/chat/stream");
        handlerLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        handlerLogger.detachAppender(appender);
        appender.stop();
    }

    /** Spring AI 对 provider 回的 401 造出来的那个异常。 */
    private static RuntimeException providerRejectedTheKey() {
        return new RuntimeException(
                "401 - {\"code\":30014,\"data\":null,\"message\":\"Token is invalid.\"}",
                null);
    }

    @Test
    void aProvider401_answers500_becauseThisBranchNeverDecidedItWasA401() {
        ResponseEntity<ErrorResponse> response =
                handler.handleException(providerRejectedTheKey(), request);
        ErrorResponse body = response.getBody();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(body);
        assertEquals("INTERNAL_ERROR", body.getError());
        // 关键的一条：那个 401 是**消息的一部分**，不是状态。
        assertFalse(String.valueOf(body.getStatus()).startsWith("401"),
                () -> "the detail leaked into the status: " + body.getStatus());
    }

    /**
     * 日志必须自己说出状态码。
     *
     * <p>这条不是断言格式好看——是断言它<strong>不能再被读成另一种东西</strong>。
     * 原来的 {@code "Request failed: {}"} 让 provider 的 401 正好落在状态码该在的
     * 位置上，而这就是那批误诊断的起点。状态码被点名之后，detail 里再出现数字
     * 就只是 detail 了。
     */
    @Test
    void theLogLineNamesTheStatusSoTheDetailCannotBeMistakenForIt() {
        handler.handleException(providerRejectedTheKey(), request);

        String errorLine = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected an ERROR line"));

        assertTrue(errorLine.contains("status=500"),
                () -> "the log does not name the status: " + errorLine);
        assertTrue(errorLine.contains("detail=401 - {"),
                () -> "the provider's own status is not carried as detail: " + errorLine);
    }
}
