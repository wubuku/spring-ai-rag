package com.springairag.core.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import com.springairag.core.ratelimit.RateLimitObservability;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端地证明：伪造 {@code X-Forwarded-For} 拿不到新的计数窗口。
 *
 * <p>本项目的 API Key 加固计划把"通过假 {@code X-Forwarded-For} 绕过 pre-auth IP
 * limiter"列为 4.2 的第 7 条必须防御的攻击，风险表把"直接信任 X-Forwarded-For"
 * 标为高风险、缓解措施写的是 trusted proxy resolver。解析器落地之前，
 * 过滤器无条件采信该头——下面 {@code forgedForwardedForCannotMintAWindow} 描述的
 * 就是修复前那个可利用的绕过。
 */
@DisplayName("RateLimitFilter — 客户端地址不可伪造")
class RateLimitFilterTrustedProxyTest {

    private static final int LIMIT = 3;
    private static final String PEER = "203.0.113.7";

    @BeforeEach
    void setUp() {
        // 每次都用新实例：窗口表是实例状态。
    }

    private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/rag/chat");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    private static int call(RateLimitFilter filter, String remoteAddr, String forwardedFor)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request(remoteAddr, forwardedFor), response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    @DisplayName("每次换一个伪造的 X-Forwarded-For，仍然被同一个窗口限住")
    void forgedForwardedForCannotMintAWindow() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, LIMIT);

        // 修复前：这三行各自拿到一个全新的 identifier，计数器永远停在 1。
        for (int i = 1; i <= LIMIT; i++) {
            assertEquals(200, call(filter, PEER, "10.0.0." + i),
                    "forged hop " + i + " should still be inside the limit");
        }
        assertEquals(429, call(filter, PEER, "10.0.0.99"),
                "the limit must be reachable, not reset by a forged header");
    }

    @Test
    @DisplayName("没有可信代理时只有对端地址参与计数")
    void untrustedPeerSharesOneWindow() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, LIMIT);

        call(filter, PEER, "1.1.1.1");
        call(filter, PEER, "2.2.2.2");
        call(filter, PEER, null);

        assertEquals(1, filter.getWindows().size(),
                "only the peer address should have a window");
    }

    @Test
    @DisplayName("配置了可信代理后，多级链按最右不可信跳数归并")
    void trustedProxySplitsRealClientsApart() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, LIMIT, "ip", Map.of(), "local", null,
                RateLimitObservability.noop(),
                TrustedProxyResolver.of(List.of("10.0.0.0/8")));

        // 同一个代理，不同的真实客户端：应当各自计数。
        call(filter, "10.0.0.1", "203.0.113.1");
        call(filter, "10.0.0.1", "203.0.113.2");

        assertEquals(2, filter.getWindows().size(),
                "two distinct real clients behind a trusted proxy need two windows");
    }

    @Test
    @DisplayName("未登记的对端即使声明 X-Forwarded-For 也不被采信")
    void unregisteredPeerIsNotAProxy() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, LIMIT, "ip", Map.of(), "local", null,
                RateLimitObservability.noop(),
                TrustedProxyResolver.of(List.of("10.0.0.0/8")));

        // 对端 203.0.113.7 不在可信集合里，它声明的链一律作废。
        call(filter, PEER, "203.0.113.1");
        call(filter, PEER, "203.0.113.2");

        assertEquals(1, filter.getWindows().size(),
                "an untrusted peer must not be able to create separate clients");
    }

    @Test
    @DisplayName("过期的窗口条目会被回收，不会无限增长")
    void expiredWindowsAreReclaimed() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, LIMIT);

        // 直接铺满超过清扫阈值的过期条目：走真实请求要打一万多次，
        // 而这里要验证的是"阈值之上会发生清扫"这件事本身。
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        for (int i = 0; i < 10_001; i++) {
            filter.getWindows().put("stale-" + i, new RateLimitFilter.WindowState(stale));
        }
        assertTrue(filter.getWindows().size() > 10_000, "fixture should exceed the sweep threshold");

        // 任意一次请求都会先做一次机会式清扫。
        call(filter, "198.51.100.250", null);

        assertTrue(filter.getWindows().size() <= 2,
                "expired entries must be dropped rather than kept for the process lifetime; left="
                        + filter.getWindows().size());
    }

    @Test
    @DisplayName("未过期且仍在使用中的窗口不会被误删")
    void liveWindowsSurviveTheSweep() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, LIMIT);
        long now = System.currentTimeMillis();

        for (int i = 0; i < 10_001; i++) {
            filter.getWindows().put("live-" + i, new RateLimitFilter.WindowState(now));
        }
        int before = filter.getWindows().size();

        call(filter, "198.51.100.1", null);

        // 新请求会新增一个窗口（该客户端此前不在表里），但一条在用条目都不能少。
        assertEquals(before + 1, filter.getWindows().size(),
                "a fresh window must not be swept away");
        for (int i = 0; i < 10_001; i++) {
            if (!filter.getWindows().containsKey("live-" + i)) {
                throw new AssertionError("live entry live-" + i + " was swept");
            }
        }
    }
}
