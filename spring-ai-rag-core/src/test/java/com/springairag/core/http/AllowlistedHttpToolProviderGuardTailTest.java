package com.springairag.core.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.skill.RuntimeSkill;
import com.springairag.core.skill.RuntimeSkillCatalog;
import com.springairag.core.skill.RuntimeSkillLoadSession;
import com.springairag.api.service.RagChatToolContextKeys;
import com.springairag.api.service.RagChatToolRequestContext;
import com.springairag.api.enums.ChatMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 白名单 HTTP 工具守卫长尾（Batch 591，JaCoCo 驱动）：SSRF 公网
 * 判定矩阵补侧（IPv4 保留段边界与 IPv6 非全局前缀）、请求级字节
 * 预算状态机的空值/重复结算/钳制语义、端点冻结校验（不健康目录、
 * 重复/空白工具名、未知 Skill、能力未声明）、凭证缺失、完整预算下
 * 响应超限、非 2xx 状态码与空白必填参数。
 */
class AllowlistedHttpToolProviderGuardTailTest {

    // ── publicAddress 矩阵补侧 ───────────────────────────────────────

    private static Object callback;
    private static Method publicAddress;

    private boolean isPublic(byte[] bytes) throws Exception {
        return (boolean) publicAddress.invoke(
                callback, InetAddress.getByAddress(bytes));
    }

    private boolean isPublic(String host) throws Exception {
        return (boolean) publicAddress.invoke(
                callback, InetAddress.getByName(host));
    }

    private static void createCallbackOnce() throws Exception {
        if (callback != null) {
            return;
        }
        RuntimeSkillCatalog catalog = mock(RuntimeSkillCatalog.class);
        RagChatProperties properties = new RagChatProperties();
        RagChatProperties.HttpEndpointProperties endpoint =
                new RagChatProperties.HttpEndpointProperties();
        endpoint.setToolName("probe");
        endpoint.setBaseUrl("https://example.test");
        AllowlistedHttpToolProvider provider = new AllowlistedHttpToolProvider(
                catalog, properties, new ObjectMapper());
        Class<?> callbackClass = Class.forName(
                "com.springairag.core.http.AllowlistedHttpToolProvider"
                        + "$EndpointCallback");
        Constructor<?> constructor = callbackClass.getDeclaredConstructor(
                AllowlistedHttpToolProvider.class,
                RagChatProperties.HttpEndpointProperties.class);
        constructor.setAccessible(true);
        callback = constructor.newInstance(provider, endpoint);
        publicAddress = callbackClass.getDeclaredMethod(
                "publicAddress", InetAddress.class);
        publicAddress.setAccessible(true);
    }

    private byte[] ipv4(int first, int second, int third, int fourth) {
        return new byte[] {(byte) first, (byte) second, (byte) third,
                (byte) fourth};
    }

    @Test
    void ipv4ReservedRangeBoundariesAreClassifiedCorrectly() throws Exception {
        createCallbackOnce();
        // 0/8（非全零）与 240/4 保留段。
        assertFalse(isPublic(ipv4(0, 1, 2, 3)));
        assertFalse(isPublic(ipv4(240, 1, 2, 3)));
        // 特殊段的兄弟段保持公网。
        assertTrue(isPublic(ipv4(169, 1, 1, 1)));
        assertTrue(isPublic(ipv4(172, 5, 0, 1)));
        assertTrue(isPublic(ipv4(192, 5, 0, 1)));
        assertTrue(isPublic(ipv4(100, 5, 0, 1)));
        assertTrue(isPublic(ipv4(198, 20, 5, 5)));
        assertTrue(isPublic(ipv4(198, 51, 101, 5)));
        assertTrue(isPublic(ipv4(203, 0, 114, 5)));
        // 100.64/10 与 192.0.0/24 保留段。
        assertFalse(isPublic(ipv4(100, 100, 0, 1)));
        assertFalse(isPublic(ipv4(192, 0, 0, 5)));
    }

    @Test
    void ipv6NonGlobalPrefixesAreClassifiedCorrectly() throws Exception {
        createCallbackOnce();
        // fe00::/10 非链路本地半段：落入全局单播掩码检查判非公网。
        assertFalse(isPublic("fe00::9"));
        // 2001:0100::/23（IETF 协议分配）非公网。
        assertFalse(isPublic("2001:100::9"));
        // ::8.8.8.8（前 10 字节全零的内嵌 IPv4）递归判定为公网。
        byte[] embedded = new byte[16];
        embedded[12] = 8;
        embedded[13] = 8;
        embedded[14] = 8;
        embedded[15] = 8;
        assertTrue(isPublic(embedded));
    }

    // ── HttpToolExecutionState 状态机 ────────────────────────────────

    @Test
    void reservationRejectsNonPositiveAndExhaustedRequests() {
        HttpToolExecutionState state = new HttpToolExecutionState(64);
        assertNull(state.reserveUpTo(0));
        assertNull(state.reserveUpTo(-5));

        state.commit(state.reserveUpTo(64), 64);
        assertNull(state.reserveUpTo(1));
        assertEquals(64, state.responseBytes());
        assertEquals(0, state.remainingBytes());
    }

    @Test
    void commitAndReleaseIgnoreNullAndDoubleSettledReservations() {
        HttpToolExecutionState state = new HttpToolExecutionState(64);
        state.commit(null, 10);
        state.release(null);

        HttpToolExecutionState.ResponseReservation reservation =
                state.reserveUpTo(32);
        state.commit(reservation, 32);
        state.commit(reservation, 32);
        state.release(reservation);
        assertEquals(32, state.responseBytes());
        assertEquals(0, state.reservedBytes());

        HttpToolExecutionState fresh = new HttpToolExecutionState(64);
        HttpToolExecutionState.ResponseReservation hold = fresh.reserveUpTo(16);
        fresh.release(hold);
        fresh.release(hold);
        assertEquals(0, fresh.reservedBytes());
        assertEquals(64, fresh.remainingBytes());
    }

    @Test
    void commitClampsActualBytesToReservationMaximum() {
        HttpToolExecutionState state = new HttpToolExecutionState(64);
        HttpToolExecutionState.ResponseReservation small = state.reserveUpTo(8);
        state.commit(small, 500);
        assertEquals(8, state.responseBytes());

        HttpToolExecutionState tiny = new HttpToolExecutionState(0);
        assertEquals(1, tiny.maxResponseBytes());
    }

    // ── 端点冻结校验与策略投影 ───────────────────────────────────────

    private RuntimeSkillCatalog catalogWithCapability(
            List<String> capabilities, boolean healthy) {
        RuntimeSkillCatalog catalog = mock(RuntimeSkillCatalog.class);
        RuntimeSkill skill = mock(RuntimeSkill.class);
        when(skill.capabilities()).thenReturn(capabilities);
        when(catalog.find("weather")).thenReturn(skill);
        when(catalog.snapshot()).thenReturn(new RuntimeSkillCatalog.Snapshot(
                1L, "digest", healthy, Map.of()));
        return catalog;
    }

    private RagChatProperties.HttpEndpointProperties endpoint(
            String toolName, String skillName, String capability) {
        RagChatProperties.HttpEndpointProperties endpoint =
                new RagChatProperties.HttpEndpointProperties();
        endpoint.setToolName(toolName);
        endpoint.setSkillName(skillName);
        endpoint.setCapability(capability);
        endpoint.setBaseUrl("https://weather.example.test");
        return endpoint;
    }

    @Test
    void unhealthySkillSnapshotDisablesAllEndpoints() {
        RagChatProperties properties = new RagChatProperties();
        properties.getHttpTools().setEnabled(true);
        properties.getHttpTools().setEndpoints(List.of(
                endpoint("getWeather", "weather", "weather.read")));

        AllowlistedHttpToolProvider provider = new AllowlistedHttpToolProvider(
                catalogWithCapability(List.of("weather.read"), false),
                properties, new ObjectMapper());

        assertTrue(provider.getToolCallbacks().isEmpty());
        assertTrue(provider.getToolPolicies().isEmpty());
    }

    @Test
    void disabledHttpToolsProjectNoPolicies() {
        RagChatProperties properties = new RagChatProperties();
        properties.getHttpTools().setEnabled(false);
        AllowlistedHttpToolProvider provider = new AllowlistedHttpToolProvider(
                catalogWithCapability(List.of(), true),
                properties, new ObjectMapper());

        assertTrue(provider.getToolPolicies().isEmpty());
        assertTrue(provider.getToolCallbacks().isEmpty());
    }

    @Test
    void freezeRejectsDuplicateBlankAndUnknownSkillEndpoints() {
        // 冻结校验发生在构造期，非法配置直接拒绝构建。
        RagChatProperties duplicated = new RagChatProperties();
        duplicated.getHttpTools().setEnabled(true);
        duplicated.getHttpTools().setEndpoints(List.of(
                endpoint("getWeather", "weather", "weather.read"),
                endpoint("getWeather", "weather", "weather.read")));
        assertThrows(IllegalStateException.class,
                () -> new AllowlistedHttpToolProvider(
                        catalogWithCapability(List.of("weather.read"), true),
                        duplicated, new ObjectMapper()));

        RagChatProperties blank = new RagChatProperties();
        blank.getHttpTools().setEnabled(true);
        blank.getHttpTools().setEndpoints(
                List.of(endpoint(null, "weather", "weather.read")));
        assertThrows(IllegalStateException.class,
                () -> new AllowlistedHttpToolProvider(
                        catalogWithCapability(List.of("weather.read"), true),
                        blank, new ObjectMapper()));

        RagChatProperties unknownSkill = new RagChatProperties();
        unknownSkill.getHttpTools().setEnabled(true);
        unknownSkill.getHttpTools().setEndpoints(
                List.of(endpoint("getWeather", "nope", "weather.read")));
        assertThrows(IllegalStateException.class,
                () -> new AllowlistedHttpToolProvider(
                        catalogWithCapability(List.of("weather.read"), true),
                        unknownSkill, new ObjectMapper()));

        RagChatProperties missingCapability = new RagChatProperties();
        missingCapability.getHttpTools().setEnabled(true);
        missingCapability.getHttpTools().setEndpoints(
                List.of(endpoint("getWeather", "weather", "weather.write")));
        assertThrows(IllegalStateException.class,
                () -> new AllowlistedHttpToolProvider(
                        catalogWithCapability(List.of("weather.read"), true),
                        missingCapability, new ObjectMapper()));
    }

    @Test
    void nullEndpointListFreezesToEmptyRegistry() {
        RagChatProperties properties = new RagChatProperties();
        properties.getHttpTools().setEnabled(true);
        properties.getHttpTools().setEndpoints(null);

        AllowlistedHttpToolProvider provider = new AllowlistedHttpToolProvider(
                catalogWithCapability(List.of(), true),
                properties, new ObjectMapper());

        assertTrue(provider.getToolCallbacks().isEmpty());
    }

    // ── EndpointCallback 执行路径 ────────────────────────────────────

    private AllowlistedHttpToolProvider provider(
            AllowlistedHttpToolProvider.HttpTransport transport) {
        return new AllowlistedHttpToolProvider(
                catalogWithCapability(List.of("weather.read"), true),
                properties(), new ObjectMapper(), transport,
                host -> new InetAddress[] {InetAddress.getByAddress(
                        host, new byte[] {93, (byte) 184, (byte) 216, 34})});
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
        endpoint.setQueryParameters(List.of(parameter("city", true, 64)));
        http.setEndpoints(List.of(endpoint));
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

    private RuntimeSkillLoadSession loadedSession() {
        RuntimeSkillLoadSession session =
                new RuntimeSkillLoadSession(2, 2, 2_000);
        session.markLoaded("weather");
        return session;
    }

    private ToolContext context(HttpToolExecutionState state) {
        return new ToolContext(Map.of(
                RagChatToolContextKeys.REQUEST,
                new RagChatToolRequestContext(
                        "principal", "USER", false, "session",
                        null, ChatMode.AGENT, "test/model", null),
                RuntimeSkillLoadSession.CONTEXT_KEY, loadedSession(),
                HttpToolExecutionState.CONTEXT_KEY, state));
    }

    private AllowlistedHttpToolProvider.HttpResponseData response(
            int statusCode, String contentType, byte[] body) {
        return new AllowlistedHttpToolProvider.HttpResponseData(
                statusCode, contentType, body);
    }

    @Test
    void blankRequiredParameterReportsMissing() {
        AllowlistedHttpToolProvider provider = provider(
                (request, timeout, maxBytes, addresses) -> response(
                        200, "application/json", "{}".getBytes()));

        assertEquals("{\"error\":\"missing_query_parameter\"}",
                provider.getToolCallbacks().getFirst().call(
                        "{\"city\":\"   \"}", context(
                                new HttpToolExecutionState(4_000))));
    }

    @Test
    void missingCredentialEnvReportsCredentialUnavailable() {
        RagChatProperties properties = properties();
        RagChatProperties.HttpEndpointProperties endpoint =
                properties.getHttpTools().getEndpoints().getFirst();
        endpoint.setCredentialEnv("RAG_TEST_DEFINITELY_UNSET_ENV_591");
        endpoint.setCredentialHeader("X-Api-Key");
        AllowlistedHttpToolProvider provider =
                new AllowlistedHttpToolProvider(
                        catalogWithCapability(List.of("weather.read"), true),
                        properties, new ObjectMapper(),
                        (request, timeout, maxBytes, addresses) -> response(
                                200, "application/json", "{}".getBytes()),
                        host -> new InetAddress[] {InetAddress.getByAddress(
                                host,
                                new byte[] {93, (byte) 184, (byte) 216, 34})});

        assertEquals("{\"error\":\"credential_unavailable\"}",
                provider.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context(
                                new HttpToolExecutionState(4_000))));
    }

    @Test
    void responseTooLargeWithFullBudgetReportsResponseTooLarge() {
        AllowlistedHttpToolProvider provider = provider(
                (request, timeout, maxBytes, addresses) -> {
                    throw responseTooLargeException();
                });

        // 预算与端点上限一致 → transportLimit == max → 直接判超限。
        assertEquals("{\"error\":\"response_too_large\"}",
                provider.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context(
                                new HttpToolExecutionState(64))));
    }

    @Test
    void non2xxStatusCodesReportStatusNotAllowed() {
        AllowlistedHttpToolProvider provider = provider(
                (request, timeout, maxBytes, addresses) -> response(
                        500, "application/json", "{\"e\":1}".getBytes()));

        assertEquals("{\"error\":\"http_status_not_allowed\"}",
                provider.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context(
                                new HttpToolExecutionState(4_000))));
        assertEquals("{\"error\":\"http_status_not_allowed\"}",
                provider.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context(
                                new HttpToolExecutionState(4_000))));
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
