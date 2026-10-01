package com.springairag.core.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.skill.RuntimeSkillCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@code publicAddress} 的补集边界（Batch 762）。
 *
 * <p>Batch 419 的 {@code AllowlistedHttpToolProviderPublicAddressTailTest} 已经覆盖了
 * 每一类保留网段的<strong>范围内</strong>代表值。缺口在另一侧：没有任何测试断言
 * <strong>紧邻范围外</strong>的地址是公网的。
 *
 * <p>这正是 SSRF 守卫最容易被悄悄改坏的方向。黑名单是一条 {@code ||} 链，每一条都
 * 带范围判断；把 {@code second <= 31} 收紧成 {@code <= 30}、或者从链里删掉
 * {@code 100.64.0.0/10}（CGNAT，RFC 6598），现有测试全部照常通过——因为它们只测了
 * 范围内的点，没人测过边界外一步。
 *
 * <p>因此本类的组织方式是<strong>成对</strong>断言：每个保留段给一个域内点和一个
 * 紧邻域外点，两者的期望相反。任何把边界挪动一个网段、或删掉整条规则的改动，
 * 都会在这里失败。
 *
 * <p>刻意不用 {@code @Nested}：Surefire 的 {@code -Dtest=} 过滤器匹配不到嵌套类
 * （{@code XxxTest$Nested}），会静默报 "Tests run: 0" 而一个用例都没跑。分组改用
 * {@code @DisplayName} 前缀，与本包其余 9 个测试类的扁平风格保持一致。
 */
class AllowlistedHttpToolProviderAddressBoundaryTest {

    private static Object callback;
    private static Method publicAddress;

    @BeforeAll
    static void createCallback() throws Exception {
        RuntimeSkillCatalog catalog = mock(RuntimeSkillCatalog.class);
        RagChatProperties properties = new RagChatProperties();
        RagChatProperties.HttpEndpointProperties endpoint =
                new RagChatProperties.HttpEndpointProperties();
        endpoint.setToolName("probe");
        endpoint.setBaseUrl("https://example.test");
        AllowlistedHttpToolProvider provider = new AllowlistedHttpToolProvider(
                catalog, properties, new ObjectMapper());
        Class<?> callbackClass = Class.forName(
                "com.springairag.core.http.AllowlistedHttpToolProvider$EndpointCallback");
        Constructor<?> constructor = callbackClass.getDeclaredConstructor(
                AllowlistedHttpToolProvider.class,
                RagChatProperties.HttpEndpointProperties.class);
        constructor.setAccessible(true);
        callback = constructor.newInstance(provider, endpoint);
        publicAddress = callbackClass.getDeclaredMethod(
                "publicAddress", InetAddress.class);
        publicAddress.setAccessible(true);
    }

    private static boolean isPublic(String host) throws Exception {
        return (boolean) publicAddress.invoke(
                callback, InetAddress.getByName(host));
    }

    private static boolean isPublic(byte[] bytes) throws Exception {
        return (boolean) publicAddress.invoke(
                callback, InetAddress.getByAddress(bytes));
    }

    private static void assertInside(String rejected) throws Exception {
        assertFalse(isPublic(rejected),
                () -> rejected + " 在保留网段内，必须判定为非公网");
    }

    private static void assertOutside(String accepted) throws Exception {
        assertTrue(isPublic(accepted),
                () -> accepted + " 在保留网段外，必须判定为公网");
    }

    // ==================== IPv4 第一字节分段 ====================

    @ParameterizedTest(name = "0.0.0.0/8 是全零保留段：{0}")
    @ValueSource(strings = {"0.1.2.3", "0.255.255.255"})
    @DisplayName("[IPv4] 0/8 段内")
    void zeroSlash8IsReserved(String host) throws Exception {
        // 0.0.0.0 本身被 isAnyLocalAddress() 提前拦下，这条规则管的是整个 0/8。
        assertInside(host);
    }

    @ParameterizedTest(name = "0/8 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"1.0.0.0", "1.255.255.255"})
    @DisplayName("[IPv4] 0/8 段外紧邻")
    void justOutsideZeroSlash8(String host) throws Exception {
        assertOutside(host);
    }

    @ParameterizedTest(name = "10/8 私网：{0}")
    @ValueSource(strings = {"10.0.0.1", "10.255.255.255"})
    @DisplayName("[IPv4] 10/8 段内")
    void tenSlash8IsPrivate(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "10/8 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"9.255.255.255", "11.0.0.0"})
    @DisplayName("[IPv4] 10/8 段外紧邻")
    void justOutsideTenSlash8(String host) throws Exception {
        assertOutside(host);
    }

    @ParameterizedTest(name = "127/8 回环：{0}")
    @ValueSource(strings = {"127.0.0.2", "127.255.255.255"})
    @DisplayName("[IPv4] 127/8 段内")
    void loopbackSlash8IsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "127/8 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"126.255.255.255", "128.0.0.0"})
    @DisplayName("[IPv4] 127/8 段外紧邻")
    void justOutsideLoopbackSlash8(String host) throws Exception {
        assertOutside(host);
    }

    @ParameterizedTest(name = "224/4 组播与保留：{0}")
    @ValueSource(strings = {"224.0.0.0", "239.255.255.255", "255.255.255.255"})
    @DisplayName("[IPv4] 224/4 段内")
    void multicastAndAboveIsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "224/4 之下紧邻：{0} 必须公网")
    @ValueSource(strings = {"223.255.255.255", "200.0.0.1"})
    @DisplayName("[IPv4] 224/4 段外紧邻")
    void justBelowMulticastRange(String host) throws Exception {
        // 200.0.0.0 顺带钉住"224 起跳"这条边界，而不只是 223 这一格。
        assertOutside(host);
    }

    // ==================== IPv4 链路本地与 RFC 1918 私网 ====================

    @ParameterizedTest(name = "169.254/16 链路本地：{0}")
    @ValueSource(strings = {"169.254.0.1", "169.254.255.255"})
    @DisplayName("[IPv4] 169.254/16 段内")
    void linkLocalIsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "169.254/16 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"169.253.255.255", "169.255.0.0"})
    @DisplayName("[IPv4] 169.254/16 段外紧邻")
    void justOutsideLinkLocal(String host) throws Exception {
        assertOutside(host);
    }

    @ParameterizedTest(name = "172.16/12 私网：{0}")
    @ValueSource(strings = {"172.16.0.0", "172.31.255.255"})
    @DisplayName("[IPv4] 172.16/12 段内")
    void private172Slash12IsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "172.16/12 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"172.15.255.255", "172.32.0.0"})
    @DisplayName("[IPv4] 172.16/12 段外紧邻")
    void justOutsidePrivate172Slash12(String host) throws Exception {
        // RFC 1918 把 172.16.0.0–172.31.255.255 划给私网，172.15 与 172.32
        // 都在范围外。把 <= 31 收紧成 <= 30 或放宽成 <= 32 都会在这里暴露。
        assertOutside(host);
    }

    @ParameterizedTest(name = "192.168/16 私网：{0}")
    @ValueSource(strings = {"192.168.0.0", "192.168.255.255"})
    @DisplayName("[IPv4] 192.168/16 段内")
    void private192Slash16IsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "192.168/16 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"192.167.255.255", "192.169.0.0"})
    @DisplayName("[IPv4] 192.168/16 段外紧邻")
    void justOutsidePrivate192Slash16(String host) throws Exception {
        assertOutside(host);
    }

    // ==================== IPv4 特殊用途段（易被误当作公网） ====================

    @ParameterizedTest(name = "100.64/10 CGNAT：{0}")
    @ValueSource(strings = {"100.64.0.0", "100.127.255.255", "100.100.1.1"})
    @DisplayName("[IPv4] 100.64/10 CGNAT 段内")
    void cgnatIsReserved(String host) throws Exception {
        // RFC 6598：运营商级 NAT。这一段能路由、能出公网，是 SSRF 的经典绕过口。
        assertInside(host);
    }

    @ParameterizedTest(name = "100.64/10 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"100.63.255.255", "100.128.0.0"})
    @DisplayName("[IPv4] 100.64/10 CGNAT 段外紧邻")
    void justOutsideCgnat(String host) throws Exception {
        assertOutside(host);
    }

    @ParameterizedTest(name = "192.0.0/24 IETF 协议保留：{0}")
    @ValueSource(strings = {"192.0.0.1", "192.0.0.255"})
    @DisplayName("[IPv4] 192.0.0/24 段内")
    void ietfProtocolAssignmentsAreReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "192.0.0/24 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"192.0.1.0"})
    @DisplayName("[IPv4] 192.0.0/24 段外紧邻")
    void justOutsideIetfProtocolAssignments(String host) throws Exception {
        assertOutside(host);
    }

    @ParameterizedTest(name = "192.0.2/24 TEST-NET-1：{0}")
    @ValueSource(strings = {"192.0.2.0", "192.0.2.255"})
    @DisplayName("[IPv4] 192.0.2/24 TEST-NET-1 段内")
    void testNet1IsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "192.0.2/24 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"192.0.1.255", "192.0.3.0"})
    @DisplayName("[IPv4] 192.0.2/24 段外紧邻")
    void justOutsideTestNet1(String host) throws Exception {
        assertOutside(host);
    }

    @ParameterizedTest(name = "192.88.99/24 6to4 中继：{0}")
    @ValueSource(strings = {"192.88.99.0", "192.88.99.255"})
    @DisplayName("[IPv4] 192.88.99/24 段内")
    void sixToFourRelayIsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "192.88.99/24 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"192.88.98.255", "192.88.100.0"})
    @DisplayName("[IPv4] 192.88.99/24 段外紧邻")
    void justOutsideSixToFourRelay(String host) throws Exception {
        assertOutside(host);
    }

    @ParameterizedTest(name = "198.18/15 基准测试：{0}")
    @ValueSource(strings = {"198.18.0.0", "198.19.255.255"})
    @DisplayName("[IPv4] 198.18/15 段内")
    void benchmarkingRangeIsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "198.18/15 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"198.17.255.255", "198.20.0.0"})
    @DisplayName("[IPv4] 198.18/15 段外紧邻")
    void justOutsideBenchmarkingRange(String host) throws Exception {
        assertOutside(host);
    }

    @ParameterizedTest(name = "198.51.100/24 TEST-NET-2：{0}")
    @ValueSource(strings = {"198.51.100.0", "198.51.100.255"})
    @DisplayName("[IPv4] 198.51.100/24 TEST-NET-2 段内")
    void testNet2IsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "198.51.100/24 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"198.51.99.255", "198.51.101.0"})
    @DisplayName("[IPv4] 198.51.100/24 段外紧邻")
    void justOutsideTestNet2(String host) throws Exception {
        assertOutside(host);
    }

    @ParameterizedTest(name = "203.0.113/24 TEST-NET-3：{0}")
    @ValueSource(strings = {"203.0.113.0", "203.0.113.255"})
    @DisplayName("[IPv4] 203.0.113/24 TEST-NET-3 段内")
    void testNet3IsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "203.0.113/24 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"203.0.112.255", "203.0.114.0"})
    @DisplayName("[IPv4] 203.0.113/24 段外紧邻")
    void justOutsideTestNet3(String host) throws Exception {
        assertOutside(host);
    }

    // ==================== IPv6 保留段 ====================

    @ParameterizedTest(name = "fc00::/7 唯一本地：{0}")
    @ValueSource(strings = {"fc00::", "fdff:ffff::1", "fd00::1"})
    @DisplayName("[IPv6] fc00::/7 ULA 段内")
    void uniqueLocalIsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "fc00::/7 之外的 0xfb–0xfe 仍非公网：{0}")
    @ValueSource(strings = {"fbff:ffff::1", "fe00::1", "fe7f:ffff::1", "fec0::1"})
    @DisplayName("[IPv6] ULA 段外仍被全局性规则兜住")
    void nonGlobalUnicastIsReservedRegardlessOfLocalRule(String host)
            throws Exception {
        // fc00::/7 之外并没有一条"变成公网"的逃逸路径：这些首字节根本不在
        // 2000::/3 全局单播空间内，会被兜底规则拦下。断言这一点比断言某个具体
        // 网段更重要——它说明删除 fc00::/7 那条规则不会让 0xfb 漏出去。
        assertInside(host);
    }

    @ParameterizedTest(name = "2000::/3 全局单播：{0} 必须公网")
    @ValueSource(strings = {"2000::1", "3fff:ffff::1"})
    @DisplayName("[IPv6] 2000::/3 全局单播段内")
    void globalUnicastRangeIsPublic(String host) throws Exception {
        assertOutside(host);
    }

    @ParameterizedTest(name = "2000::/3 之外紧邻：{0} 必须非公网")
    @ValueSource(strings = {"1fff::1", "4000::1"})
    @DisplayName("[IPv6] 2000::/3 段外紧邻")
    void justOutsideGlobalUnicastRange(String host) throws Exception {
        // 首字节 0x1f 与 0x40 各自贴着 2000::/3 的两侧。
        assertInside(host);
    }

    @ParameterizedTest(name = "fe80::/10 链路本地：{0}")
    @ValueSource(strings = {"fe80::1", "febf:ffff::1"})
    @DisplayName("[IPv6] fe80::/10 段内")
    void linkLocalIpv6IsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "ff00::/8 组播：{0}")
    @ValueSource(strings = {"ff00::", "ffff::1"})
    @DisplayName("[IPv6] ff00::/8 组播段内")
    void multicastIpv6IsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "2001:db8::/32 文档段：{0}")
    @ValueSource(strings = {"2001:db8::", "2001:db8:ffff::1"})
    @DisplayName("[IPv6] 2001:db8::/32 段内")
    void documentationPrefixIsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "2001:db8::/32 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"2001:db9::1", "2001:db7:ffff::1"})
    @DisplayName("[IPv6] 2001:db8::/32 段外紧邻")
    void justOutsideDocumentationPrefix(String host) throws Exception {
        assertOutside(host);
    }

    @ParameterizedTest(name = "2001:0000::/23 Teredo：{0}")
    @ValueSource(strings = {"2001::1", "2001:1ff:ffff::1"})
    @DisplayName("[IPv6] 2001:0000::/23 段内")
    void teredoPrefixIsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "2001:0000::/23 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"2001:200::1", "2001:ffff::1"})
    @DisplayName("[IPv6] 2001:0000::/23 段外紧邻")
    void justOutsideTeredoPrefix(String host) throws Exception {
        // /23 止于 2001:01ff。把剩余位数从 23 改成 24 或 22 都会在这里暴露。
        assertOutside(host);
    }

    @ParameterizedTest(name = "2002::/16 6to4：{0}")
    @ValueSource(strings = {"2002::1", "2002:ffff:ffff::1"})
    @DisplayName("[IPv6] 2002::/16 段内")
    void sixToFourIpv6IsReserved(String host) throws Exception {
        assertInside(host);
    }

    @ParameterizedTest(name = "2002::/16 之外紧邻：{0} 必须公网")
    @ValueSource(strings = {"2001:ffff::1", "2003::1"})
    @DisplayName("[IPv6] 2002::/16 段外紧邻")
    void justOutsideSixToFourIpv6(String host) throws Exception {
        assertOutside(host);
    }

    // ==================== IPv4 内嵌与 NAT64 递归判定 ====================

    private static byte[] mapped(int... last4) {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        for (int index = 0; index < 4; index++) {
            bytes[12 + index] = (byte) last4[index];
        }
        return bytes;
    }

    @Test
    @DisplayName("[内嵌] ::ffff:10.0.0.1 递归判为非公网")
    void embeddedPrivateIsRejected() throws Exception {
        // 缺这段就会让 ::ffff:10.0.0.1 这类写法绕过守卫。
        assertFalse(isPublic(mapped(10, 0, 0, 1)));
        assertFalse(isPublic(mapped(192, 168, 1, 1)));
        assertFalse(isPublic(mapped(172, 16, 0, 1)));
    }

    @Test
    @DisplayName("[内嵌] ::ffff:100.100.1.1 递归命中 CGNAT 规则")
    void embeddedCgnatIsRejected() throws Exception {
        assertFalse(isPublic(mapped(100, 100, 1, 1)));
    }

    @Test
    @DisplayName("[内嵌] ::ffff:224.0.0.1 递归命中组播规则")
    void embeddedMulticastIsRejected() throws Exception {
        assertFalse(isPublic(mapped(224, 0, 0, 1)));
    }

    @Test
    @DisplayName("[内嵌] ::ffff:8.8.8.8 判为公网")
    void embeddedPublicIsAccepted() throws Exception {
        assertTrue(isPublic(mapped(8, 8, 8, 8)));
    }

    @Test
    @DisplayName("[内嵌] ::ffff:172.15.0.1 递归后落在私网段外，判为公网")
    void embeddedJustOutsidePrivateIsAccepted() throws Exception {
        // 内嵌与直接书写走同一条 IPv4 规则，边界行为也必须一致。
        assertTrue(isPublic(mapped(172, 15, 0, 1)));
    }

    @Test
    @DisplayName("[内嵌] ::a.b.c.d（不补 0xffff）不触发递归")
    void nonMappedEmbeddedDoesNotRecurse() throws Exception {
        // 前 10 字节全零、10/11 字节既不是 0000 也不是 ffff → 不是内嵌形式。
        byte[] bytes = new byte[16];
        bytes[12] = (byte) 8;
        bytes[13] = 8;
        bytes[14] = 8;
        bytes[15] = 8;
        assertTrue(isPublic(bytes));
    }
}
