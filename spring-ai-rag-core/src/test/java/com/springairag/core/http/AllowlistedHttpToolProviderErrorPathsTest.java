package com.springairag.core.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.RagChatToolContextKeys;
import com.springairag.api.service.RagChatToolRequestContext;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.skill.RuntimeSkill;
import com.springairag.core.skill.RuntimeSkillCatalog;
import com.springairag.core.skill.RuntimeSkillLoadSession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EndpointCallback#call 的错误路径与预算纪律（Batch 303）：
 * 参数校验失败、URI 拒绝、DNS 拒绝、执行状态缺失/耗尽、传输异常
 * 映射、响应体与结果预算、内容类型与 JSON 限额、凭证头与截止时间。
 */
class AllowlistedHttpToolProviderErrorPathsTest {

    // ── 服务端上下文缺失 ────────────────────────────────────────────

    @Test
    void singleArgCallThrowsWithoutServerContext() {
        ToolCallback callback = callback();

        assertThrows(IllegalStateException.class, () -> callback.call("{}"));
    }

    @Test
    void missingRequestKeyThrowsIllegalState() {
        ToolContext context = new ToolContext(Map.of(
                HttpToolExecutionState.CONTEXT_KEY,
                new HttpToolExecutionState(4_000)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> callback().call("{}", context));

        assertTrue(error.getMessage().contains("Missing server-owned HTTP tool"));
    }

    @Test
    void missingSessionKeyReportsSkillNotLoaded() {
        AtomicInteger calls = new AtomicInteger();
        AllowlistedHttpToolProvider provider = provider(
                (request, timeout, maxBytes, addresses) -> {
                    calls.incrementAndGet();
                    return ok();
                });
        ToolContext context = new ToolContext(Map.of(
                RagChatToolContextKeys.REQUEST, requestContext(null),
                HttpToolExecutionState.CONTEXT_KEY,
                new HttpToolExecutionState(4_000)));

        assertEquals("{\"error\":\"skill_not_loaded\"}",
                provider.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context));
        assertEquals(0, calls.get());
    }

    // ── 查询参数校验 ────────────────────────────────────────────────

    @Test
    void blankOrNonTextualRequiredParameterReportsMissing() {
        ToolCallback callback = callback();

        assertEquals("{\"error\":\"missing_query_parameter\"}",
                callback.call("{\"units\":\"metric\"}", context()));
        assertEquals("{\"error\":\"missing_query_parameter\"}",
                callback.call("{\"city\":null}", context()));
        assertEquals("{\"error\":\"missing_query_parameter\"}",
                callback.call("{\"city\":42}", context()));
    }

    @Test
    void oversizedOrControlCharacterParameterRejected() {
        ToolCallback callback = callback();

        assertEquals("{\"error\":\"query_parameter_rejected\"}",
                callback.call("{\"city\":\"" + "x".repeat(65) + "\"}",
                        context()));
        assertEquals("{\"error\":\"query_parameter_rejected\"}",
                callback.call("{\"city\":\"Shang\\nhai\"}", context()));
    }

    @Test
    void malformedRequestUriReportsRequestUriRejected() {
        ToolCallback callback = callbackWithComponent(
                endpoint -> endpoint.setPath("/v1/bad path"));

        assertEquals("{\"error\":\"request_uri_rejected\"}",
                callback.call("{\"city\":\"Shanghai\"}", context()));
    }

    @Test
    void unresolvableOrEmptyDnsReportsNetworkTargetRejected() {
        AtomicInteger calls = new AtomicInteger();
        AllowlistedHttpToolProvider unknownHost = provider(
                (request, timeout, maxBytes, addresses) -> {
                    calls.incrementAndGet();
                    return ok();
                },
                host -> {
                    throw new UnknownHostException(host);
                });
        assertEquals("{\"error\":\"network_target_rejected\"}",
                unknownHost.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context()));

        AllowlistedHttpToolProvider emptyAnswers = provider(
                (request, timeout, maxBytes, addresses) -> {
                    calls.incrementAndGet();
                    return ok();
                },
                host -> new InetAddress[0]);
        assertEquals("{\"error\":\"network_target_rejected\"}",
                emptyAnswers.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context()));
        assertEquals(0, calls.get());
    }

    // ── 响应字节预算 ────────────────────────────────────────────────

    @Test
    void missingExecutionStateReportsBudgetExhausted() {
        AtomicInteger calls = new AtomicInteger();
        AllowlistedHttpToolProvider provider = provider(
                (request, timeout, maxBytes, addresses) -> {
                    calls.incrementAndGet();
                    return ok();
                });
        ToolContext context = new ToolContext(Map.of(
                RagChatToolContextKeys.REQUEST, requestContext(null),
                RuntimeSkillLoadSession.CONTEXT_KEY, loadedSession()));

        assertEquals("{\"error\":\"http_response_budget_exhausted\"}",
                provider.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context));
        assertEquals(0, calls.get());
    }

    @Test
    void exhaustedBudgetReportsBudgetExhaustedWithoutTransport() {
        AtomicInteger calls = new AtomicInteger();
        AllowlistedHttpToolProvider provider = provider(
                (request, timeout, maxBytes, addresses) -> {
                    calls.incrementAndGet();
                    return ok();
                });
        HttpToolExecutionState state = new HttpToolExecutionState(64);
        state.commit(state.reserveUpTo(64), 64);

        assertEquals("{\"error\":\"http_response_budget_exhausted\"}",
                provider.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context(state)));
        assertEquals(0, calls.get());
    }

    @Test
    void responseTooLargeUnderPartialBudgetReportsBudgetExhausted()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AllowlistedHttpToolProvider provider = provider(
                (request, timeout, maxBytes, addresses) -> {
                    calls.incrementAndGet();
                    throw responseTooLargeException();
                });
        HttpToolExecutionState state = partiallyConsumedState();

        assertEquals("{\"error\":\"http_response_budget_exhausted\"}",
                provider.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context(state)));
        assertEquals(1, calls.get());
        // 失败后预留全额结转为已用，无悬挂预留。
        assertEquals(0, state.reservedBytes());
    }

    @Test
    void oversizedBodyUnderPartialBudgetReportsBudgetExhausted() {
        byte[] oversized = new byte[55];
        AllowlistedHttpToolProvider provider = provider(
                (request, timeout, maxBytes, addresses) ->
                        new AllowlistedHttpToolProvider.HttpResponseData(
                                200, "application/json", oversized));
        HttpToolExecutionState state = partiallyConsumedState();

        assertEquals("{\"error\":\"http_response_budget_exhausted\"}",
                provider.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context(state)));
        // 超限后整个预留被结转为已用（40 预存 + 40 本次预留）。
        assertEquals(80, state.responseBytes());
        assertEquals(0, state.reservedBytes());
    }

    // ── 传输异常映射 ────────────────────────────────────────────────

    @Test
    void transportTimeoutsReleaseBudgetAndReportHttpTimeout() {
        AtomicInteger calls = new AtomicInteger();
        AllowlistedHttpToolProvider httpTimeout = provider(
                (request, timeout, maxBytes, addresses) -> {
                    calls.incrementAndGet();
                    throw new HttpTimeoutException("timed out");
                });
        HttpToolExecutionState httpTimeoutState = new HttpToolExecutionState(4_000);
        assertEquals("{\"error\":\"http_timeout\"}",
                httpTimeout.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context(httpTimeoutState)));
        assertEquals(0, httpTimeoutState.reservedBytes());

        AllowlistedHttpToolProvider generalTimeout = provider(
                (request, timeout, maxBytes, addresses) -> {
                    calls.incrementAndGet();
                    throw new TimeoutException("blocked");
                });
        HttpToolExecutionState generalState = new HttpToolExecutionState(4_000);
        assertEquals("{\"error\":\"http_timeout\"}",
                generalTimeout.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context(generalState)));
        assertEquals(0, generalState.reservedBytes());
        assertEquals(2, calls.get());
    }

    @Test
    void interruptedTransportReportsHttpInterruptedAndRestoresFlag() {
        AllowlistedHttpToolProvider provider = provider(
                (request, timeout, maxBytes, addresses) -> {
                    throw new InterruptedException("stop");
                });
        HttpToolExecutionState state = new HttpToolExecutionState(4_000);

        assertEquals("{\"error\":\"http_interrupted\"}",
                provider.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context(state)));
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted(); // 清除标记，避免污染后续测试。
        assertEquals(0, state.reservedBytes());
    }

    @Test
    void connectFailuresReportHttpUnavailable() {
        AllowlistedHttpToolProvider provider = provider(
                (request, timeout, maxBytes, addresses) -> {
                    throw new ConnectException("refused");
                });
        HttpToolExecutionState state = new HttpToolExecutionState(4_000);

        assertEquals("{\"error\":\"http_unavailable\"}",
                provider.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context(state)));
        assertEquals(0, state.reservedBytes());
    }

    @Test
    void ioAndRuntimeFailuresAndNullResponseReportHttpFailed() {
        AllowlistedHttpToolProvider ioFailure = provider(
                (request, timeout, maxBytes, addresses) -> {
                    throw new SocketTimeoutException("read");
                });
        HttpToolExecutionState ioState = new HttpToolExecutionState(4_000);
        assertEquals("{\"error\":\"http_failed\"}",
                ioFailure.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context(ioState)));
        assertEquals(0, ioState.reservedBytes());

        AllowlistedHttpToolProvider runtimeFailure = provider(
                (request, timeout, maxBytes, addresses) -> {
                    throw new IllegalStateException("broken client");
                });
        HttpToolExecutionState runtimeState = new HttpToolExecutionState(4_000);
        assertEquals("{\"error\":\"http_failed\"}",
                runtimeFailure.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context(runtimeState)));
        assertEquals(0, runtimeState.reservedBytes());

        AllowlistedHttpToolProvider nullResponse = provider(
                (request, timeout, maxBytes, addresses) -> null);
        HttpToolExecutionState nullState = new HttpToolExecutionState(4_000);
        assertEquals("{\"error\":\"http_failed\"}",
                nullResponse.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context(nullState)));
        assertEquals(0, nullState.reservedBytes());
    }

    // ── 响应体处理 ──────────────────────────────────────────────────

    @Test
    void nullOrBlankContentTypeRejected() {
        ToolCallback callback = returning(
                new AllowlistedHttpToolProvider.HttpResponseData(
                        200, null, "{\"a\":1}".getBytes(StandardCharsets.UTF_8)));

        assertEquals("{\"error\":\"response_content_type_rejected\"}",
                callback.call("{\"city\":\"x\"}", context()));

        ToolCallback blankCallback = returning(
                new AllowlistedHttpToolProvider.HttpResponseData(
                        200, "   ", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)));

        assertEquals("{\"error\":\"response_content_type_rejected\"}",
                blankCallback.call("{\"city\":\"x\"}", context()));
    }

    @Test
    void textContentTypeReturnedAsText() {
        ToolCallback callback = callbackWithComponent(
                endpoint -> endpoint.setResponseContentTypes(
                        List.of("application/json", "text/plain")),
                transportReturning(
                        new AllowlistedHttpToolProvider.HttpResponseData(
                                200, "text/plain; charset=utf-8",
                                "上海晴 21 度".getBytes(StandardCharsets.UTF_8))));

        String result = callback.call("{\"city\":\"Shanghai\"}", context());

        assertTrue(result.contains("\"status\":200"));
        assertTrue(result.contains("text/plain"));
        assertTrue(result.contains("上海晴 21 度"));
    }

    @Test
    void emptyOrNullBodySucceedsWithEmptyText() {
        ToolCallback emptyBody = returning(
                new AllowlistedHttpToolProvider.HttpResponseData(
                        200, "application/pdf", new byte[0]));

        String emptyResult = emptyBody.call("{\"city\":\"x\"}", context());
        assertTrue(emptyResult.contains("\"status\":200"));
        assertTrue(emptyResult.contains("\"body\":\"\""));

        ToolCallback nullBody = returning(
                new AllowlistedHttpToolProvider.HttpResponseData(
                        200, "application/pdf", null));

        String nullResult = nullBody.call("{\"city\":\"x\"}", context());
        assertTrue(nullResult.contains("\"body\":\"\""));
    }

    @Test
    void resultBudgetExhaustedReportsError() {
        ToolCallback callback = callbackWithComponent(
                endpoint -> endpoint.setMaxResultCharacters(10),
                transportReturning(
                        new AllowlistedHttpToolProvider.HttpResponseData(
                                200, "application/json",
                                "{\"a\":1}".getBytes(StandardCharsets.UTF_8))));

        assertEquals("{\"error\":\"http_result_budget_exhausted\"}",
                callback.call("{\"city\":\"x\"}", context()));
    }

    @Test
    void jsonArrayOverflowAndNodeOverflowRejected() {
        ToolCallback callback = returning(
                new AllowlistedHttpToolProvider.HttpResponseData(
                        200, "application/json",
                        "[1,2,3,4,5]".getBytes(StandardCharsets.UTF_8)));

        // 数组 5 项 > maxJsonArrayItems=4。
        assertEquals("{\"error\":\"invalid_json_response\"}",
                callback.call("{\"city\":\"x\"}", context()));

        // 节点总数 1 + 25 > maxJsonNodes=20；响应上限需放宽，
        // 否则先被响应字节预算拦截。
        ToolCallback wideCallback = callbackWithComponent(
                endpoint -> endpoint.setMaxResponseBytes(4_096),
                transportReturning(
                        new AllowlistedHttpToolProvider.HttpResponseData(
                                200, "application/json",
                                wideJson(25).getBytes(StandardCharsets.UTF_8))));
        assertEquals("{\"error\":\"invalid_json_response\"}",
                wideCallback.call("{\"city\":\"x\"}", context()));
    }

    // ── 凭证与截止时间 ──────────────────────────────────────────────

    @Test
    void credentialHeaderAttachedWhenEnvPresent() {
        assumeTrue(
                System.getenv("PATH") != null && !System.getenv("PATH").isBlank(),
                "PATH 环境变量需存在");
        AtomicReference<String> credentialHeader = new AtomicReference<>();
        ToolCallback callback = callbackWithComponent(
                endpoint -> {
                    endpoint.setCredentialEnv("PATH");
                    endpoint.setCredentialHeader("X-Api-Credential");
                },
                (request, timeout, maxBytes, addresses) -> {
                    credentialHeader.set(
                            request.headers().firstValue("X-Api-Credential")
                                    .orElse(null));
                    return ok();
                });

        String result = callback.call("{\"city\":\"Shanghai\"}", context());

        assertTrue(result.contains("\"status\":200"));
        assertEquals(System.getenv("PATH"), credentialHeader.get());
    }

    @Test
    void deadlineClampsTimeoutAndNullDeadlineKeepsConfigured() {
        AtomicReference<Duration> firstTimeout = new AtomicReference<>();
        AllowlistedHttpToolProvider clamped = provider(
                (request, timeout, maxBytes, addresses) -> {
                    firstTimeout.set(timeout);
                    return ok();
                });
        ToolContext deadlineContext = new ToolContext(Map.of(
                RagChatToolContextKeys.REQUEST,
                requestContext(Instant.now().plusMillis(1_500)),
                RuntimeSkillLoadSession.CONTEXT_KEY, loadedSession(),
                HttpToolExecutionState.CONTEXT_KEY,
                new HttpToolExecutionState(4_000)));
        clamped.getToolCallbacks().getFirst().call(
                "{\"city\":\"Shanghai\"}", deadlineContext);
        // 默认 timeoutMs=5000，被剩余时间收敛。
        assertTrue(firstTimeout.get().toMillis() <= 1_500,
                "超时应当被截止时间收敛: " + firstTimeout.get());
        assertTrue(firstTimeout.get().toMillis() > 0);

        AtomicReference<Duration> unclamped = new AtomicReference<>();
        AllowlistedHttpToolProvider configured = provider(
                (request, timeout, maxBytes, addresses) -> {
                    unclamped.set(timeout);
                    return ok();
                });
        configured.getToolCallbacks().getFirst().call(
                "{\"city\":\"Shanghai\"}", context());
        assertEquals(Duration.ofMillis(5_000), unclamped.get());
    }

    // ── 序列化失败 ──────────────────────────────────────────────────

    @Test
    void serializeFailureThrowsIllegalState() throws Exception {
        ObjectMapper real = new ObjectMapper();
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AtomicInteger serializations = new AtomicInteger();
        // 首次调用发生在构造期 definition() 的 schema 序列化，必须放行；
        // 结果序列化为第二次调用，注入失败。
        when(objectMapper.writeValueAsString(any()))
                .thenAnswer(invocation -> {
                    if (serializations.getAndIncrement() == 0) {
                        return real.writeValueAsString(invocation.getArgument(0));
                    }
                    throw new IllegalStateException("boom");
                });
        when(objectMapper.readTree(anyString()))
                .thenAnswer(invocation ->
                        real.readTree((String) invocation.getArgument(0)));
        // 空 application/json 响应：跳过响应体解析，直达结果序列化。
        AllowlistedHttpToolProvider provider = new AllowlistedHttpToolProvider(
                catalog(), properties(), objectMapper,
                transportReturning(
                        new AllowlistedHttpToolProvider.HttpResponseData(
                                200, "application/json", new byte[0])),
                publicResolver());

        assertThrows(IllegalStateException.class,
                () -> provider.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context()));
    }

    // ── fixture ─────────────────────────────────────────────────────

    private ToolCallback callback() {
        return callbackWithComponent(endpoint -> { }, transportReturning(ok()));
    }

    private ToolCallback returning(
            AllowlistedHttpToolProvider.HttpResponseData response) {
        return callbackWithComponent(endpoint -> { },
                transportReturning(response));
    }

    private ToolCallback callbackWithComponent(
            java.util.function.Consumer<RagChatProperties.HttpEndpointProperties>
                    tweak) {
        return callbackWithComponent(tweak, transportReturning(ok()));
    }

    private ToolCallback callbackWithComponent(
            java.util.function.Consumer<RagChatProperties.HttpEndpointProperties>
                    tweak,
            AllowlistedHttpToolProvider.HttpTransport transport) {
        RagChatProperties properties = properties();
        tweak.accept(properties.getHttpTools().getEndpoints().getFirst());
        return new AllowlistedHttpToolProvider(
                catalog(), properties, new ObjectMapper(), transport,
                publicResolver())
                .getToolCallbacks().getFirst();
    }

    private AllowlistedHttpToolProvider provider(
            AllowlistedHttpToolProvider.HttpTransport transport) {
        return new AllowlistedHttpToolProvider(
                catalog(), properties(), new ObjectMapper(), transport,
                publicResolver());
    }

    private AllowlistedHttpToolProvider provider(
            AllowlistedHttpToolProvider.HttpTransport transport,
            AllowlistedHttpToolProvider.AddressResolver resolver) {
        return new AllowlistedHttpToolProvider(
                catalog(), properties(), new ObjectMapper(), transport,
                resolver);
    }

    private AllowlistedHttpToolProvider.HttpTransport transportReturning(
            AllowlistedHttpToolProvider.HttpResponseData response) {
        return (request, timeout, maxBytes, addresses) -> response;
    }

    private String wideJson(int keys) {
        StringBuilder wide = new StringBuilder("{");
        for (int index = 1; index <= keys; index++) {
            if (index > 1) {
                wide.append(',');
            }
            wide.append("\"k").append(index).append("\":1");
        }
        return wide.append('}').toString();
    }

    private RagChatProperties properties() {
        RagChatProperties properties = new RagChatProperties();
        RagChatProperties.HttpToolProperties http = properties.getHttpTools();
        http.setEnabled(true);
        http.setMaxTotalResponseBytes(128);

        RagChatProperties.HttpEndpointProperties endpoint =
                new RagChatProperties.HttpEndpointProperties();
        endpoint.setToolName("getWeather");
        endpoint.setSkillName("weather");
        endpoint.setCapability("weather.read");
        endpoint.setBaseUrl("https://weather.example.test");
        endpoint.setPath("/v1/forecast");
        endpoint.setMaxResponseBytes(64);
        endpoint.setMaxResultCharacters(4_000);
        endpoint.setMaxJsonDepth(2);
        endpoint.setMaxJsonNodes(20);
        endpoint.setMaxJsonArrayItems(4);
        endpoint.setQueryParameters(List.of(
                parameter("city", true, 64),
                parameter("units", false, 16)));
        http.setEndpoints(List.of(endpoint));
        properties.validate();
        return properties;
    }

    private RagChatProperties.HttpQueryParameterProperties parameter(
            String name, boolean required, int maxLength) {
        RagChatProperties.HttpQueryParameterProperties parameter =
                new RagChatProperties.HttpQueryParameterProperties();
        parameter.setName(name);
        parameter.setRequired(required);
        parameter.setMaxLength(maxLength);
        return parameter;
    }

    private RuntimeSkillCatalog catalog() {
        RuntimeSkillCatalog catalog = mock(RuntimeSkillCatalog.class);
        RuntimeSkill skill = mock(RuntimeSkill.class);
        when(skill.capabilities()).thenReturn(List.of("weather.read"));
        when(catalog.find("weather")).thenReturn(skill);
        return catalog;
    }

    private AllowlistedHttpToolProvider.AddressResolver publicResolver() {
        return host -> new InetAddress[] {
                InetAddress.getByAddress(
                        host, new byte[] {93, (byte) 184, (byte) 216, 34})};
    }

    private RuntimeSkillLoadSession loadedSession() {
        RuntimeSkillLoadSession session =
                new RuntimeSkillLoadSession(2, 2, 2_000);
        session.markLoaded("weather");
        return session;
    }

    private RagChatToolRequestContext requestContext(Instant deadline) {
        return new RagChatToolRequestContext(
                "principal", "USER", false, "session",
                null, ChatMode.AGENT, "test/model", deadline);
    }

    private ToolContext context() {
        return context(new HttpToolExecutionState(4_000));
    }

    private ToolContext context(HttpToolExecutionState state) {
        return new ToolContext(Map.of(
                RagChatToolContextKeys.REQUEST, requestContext(null),
                RuntimeSkillLoadSession.CONTEXT_KEY, loadedSession(),
                HttpToolExecutionState.CONTEXT_KEY, state));
    }

    private HttpToolExecutionState partiallyConsumedState() {
        HttpToolExecutionState state = new HttpToolExecutionState(80);
        state.commit(state.reserveUpTo(40), 40);
        return state;
    }

    private AllowlistedHttpToolProvider.HttpResponseData ok() {
        return new AllowlistedHttpToolProvider.HttpResponseData(
                200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
    }

    private IOException responseTooLargeException() {
        try {
            Class<?> type = Class.forName(
                    "com.springairag.core.http.AllowlistedHttpToolProvider"
                            + "$ResponseTooLargeException");
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (IOException) constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "无法实例化 ResponseTooLargeException", e);
        }
    }
}
