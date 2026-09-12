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

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * publicAddress/hasPrefix/resolvePublicTarget 地址守卫矩阵
 * （Batch 305）：IPv4/IPv6 保留段、组播、链路本地、IPv4 嵌入、
 * 特殊用途前缀逐段拒绝；公网单播放行。
 */
class AllowlistedHttpToolProviderNetworkGuardTest {

    private static final List<String> REJECTED_IPV4 = List.of(
            "0.0.0.1",            // 未指定地址
            "10.1.2.3",           // 私有段
            "100.64.0.1",         // CGN 起始
            "100.127.255.255",    // CGN 结束
            "169.254.1.1",        // 链路本地
            "172.16.0.1",         // 私有段起始
            "172.31.255.255",     // 私有段结束
            "192.0.0.2",          // IETF 协议分配
            "192.0.2.1",          // TEST-NET-1
            "192.88.99.1",        // 6to4 中继
            "192.168.1.1",        // 私有段
            "198.18.0.1",         // 基准测试起始
            "198.19.255.255",     // 基准测试结束
            "198.51.100.1",       // TEST-NET-2
            "203.0.113.1",        // TEST-NET-3
            "224.0.0.1",          // 组播
            "239.255.255.255",    // 组播结束
            "255.255.255.255");   // 受限广播

    private static final List<String> REJECTED_IPV6 = List.of(
            "::2",                 // 未指定网段（非环回路径）
            "fc00::1",             // ULA fd 前逐字节分支
            "fd12:3456::1",        // ULA
            "fe80::1",             // 链路本地
            "ff02::1",             // 组播
            "2001:1::1",           // 2001:0000/23 特殊用途
            "3fff:0::1");          // 3fff:0000/20 残余分支

    @Test
    void rejectsEveryReservedIpv4Range() throws Exception {
        for (String address : REJECTED_IPV4) {
            assertRejected(address);
        }
    }

    @Test
    void rejectsEveryReservedIpv6Range() throws Exception {
        for (String address : REJECTED_IPV6) {
            assertRejected(address);
        }
    }

    @Test
    void rejectsLoopbackAndIpv4MappedLoopback() throws Exception {
        assertRejected("127.0.0.1");
        // IPv4 映射地址以原始 16 字节构造（getByName 会归一化为 IPv4）。
        assertRejectedRaw(new byte[] {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) 0xff, (byte) 0xff, 127, 0, 0, 1});
        assertRejectedRaw(new byte[] {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10});
    }

    @Test
    void acceptsIpv4MappedPublicTargetAndOrdinaryPublicRanges()
            throws Exception {
        assertAcceptedRaw(new byte[] {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) 0xff, (byte) 0xff, 93, (byte) 184, (byte) 216, 34});
        assertAccepted("8.8.8.8");
        assertAccepted("2620:fe::fe");
    }

    @Test
    void rejectsNullAddressElementAndNullResolvedArray() throws Exception {
        // 解析结果数组含 null 元素 → 逐地址校验拒绝。
        assertRejectedWith(new InetAddress[] {null});
        // 解析返回 null → 整体拒绝。
        AtomicInteger calls = new AtomicInteger();
        AllowlistedHttpToolProvider provider = provider(
                (request, timeout, maxBytes, addresses) -> {
                    calls.incrementAndGet();
                    return ok();
                },
                host -> null);
        assertEquals("{\"error\":\"network_target_rejected\"}",
                provider.getToolCallbacks().getFirst().call(
                        "{\"city\":\"Shanghai\"}", context()));
        assertEquals(0, calls.get());
    }

    // ── fixture ─────────────────────────────────────────────────────

    private void assertRejected(String address) throws Exception {
        assertRejectedWith(new InetAddress[] {
                InetAddress.getByName(address)});
    }

    private void assertRejectedRaw(byte[] bytes) throws Exception {
        assertRejectedWith(new InetAddress[] {
                InetAddress.getByAddress(bytes)});
    }

    private void assertRejectedWith(InetAddress[] addresses) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AllowlistedHttpToolProvider provider = provider(
                (request, timeout, maxBytes, validated) -> {
                    calls.incrementAndGet();
                    return ok();
                },
                host -> addresses);

        String result = provider.getToolCallbacks().getFirst().call(
                "{\"city\":\"Shanghai\"}", context());

        assertEquals("{\"error\":\"network_target_rejected\"}",
                result, addresses[0] + " 应被拒绝");
        assertEquals(0, calls.get(), addresses[0] + " 不应触达传输层");
    }

    private void assertAccepted(String address) throws Exception {
        assertAcceptedRaw(InetAddress.getByName(address).getAddress());
    }

    private void assertAcceptedRaw(byte[] bytes) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AllowlistedHttpToolProvider provider = provider(
                (request, timeout, maxBytes, validated) -> {
                    calls.incrementAndGet();
                    return ok();
                },
                host -> new InetAddress[] {
                        InetAddress.getByAddress(bytes)});

        String result = provider.getToolCallbacks().getFirst().call(
                "{\"city\":\"Shanghai\"}", context());

        assertTrue(result.contains("\"status\":200"),
                InetAddress.getByAddress(bytes) + " 应被放行");
        assertEquals(1, calls.get());
    }

    private AllowlistedHttpToolProvider provider(
            AllowlistedHttpToolProvider.HttpTransport transport,
            AllowlistedHttpToolProvider.AddressResolver resolver) {
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
                parameter("city", true, 64)));
        http.setEndpoints(List.of(endpoint));
        properties.validate();

        RuntimeSkillCatalog catalog = mock(RuntimeSkillCatalog.class);
        RuntimeSkill skill = mock(RuntimeSkill.class);
        when(skill.capabilities()).thenReturn(List.of("weather.read"));
        when(catalog.find("weather")).thenReturn(skill);

        return new AllowlistedHttpToolProvider(
                catalog, properties, new ObjectMapper(), transport, resolver);
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

    private ToolContext context() {
        return new ToolContext(Map.of(
                RagChatToolContextKeys.REQUEST,
                new RagChatToolRequestContext(
                        "principal", "USER", false, "session",
                        null, ChatMode.AGENT, "test/model",
                        Instant.now().plusSeconds(10)),
                RuntimeSkillLoadSession.CONTEXT_KEY, loadedSession(),
                HttpToolExecutionState.CONTEXT_KEY,
                new HttpToolExecutionState(4_000)));
    }

    private AllowlistedHttpToolProvider.HttpResponseData ok() {
        return new AllowlistedHttpToolProvider.HttpResponseData(
                200, "application/json",
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
