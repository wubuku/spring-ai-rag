package com.springairag.core.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TrustedProxyResolver} 的行为测试。
 *
 * <p>这些用例守的是本项目 API Key 加固计划 4.2 第 7 条攻击：
 * "通过假 {@code X-Forwarded-For} 绕过 pre-auth IP limiter"。
 * 计划把"直接信任 X-Forwarded-For"标为高风险，缓解措施是 trusted proxy
 * resolver；解析器落地之前，过滤器是无条件采信该头的。
 */
class TrustedProxyResolverTest {

    @Nested
    @DisplayName("默认不信任任何代理")
    class Defaults {

        @Test
        void emptyConfigurationTrustsNothing() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of());

            assertTrue(resolver.trustsNothing());
            assertFalse(resolver.isTrusted("10.0.0.1"));
            assertFalse(resolver.isTrusted("127.0.0.1"));
        }

        @Test
        void nullConfigurationTrustsNothing() {
            assertTrue(TrustedProxyResolver.of(null).trustsNothing());
        }

        @Test
        void blankEntriesAreIgnored() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of("  ", ""));

            assertTrue(resolver.trustsNothing());
        }

        @Test
        @DisplayName("无可信代理时完全忽略 X-Forwarded-For")
        void ignoresForwardedForWithoutTrustedProxies() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of());

            assertEquals("198.51.100.9",
                    resolver.resolveClientAddress("198.51.100.9", "1.2.3.4"));
        }
    }

    @Nested
    @DisplayName("可信代理判定")
    class Trust {

        @Test
        void exactAddress() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of("192.168.1.5"));

            assertTrue(resolver.isTrusted("192.168.1.5"));
            assertFalse(resolver.isTrusted("192.168.1.6"));
        }

        @Test
        void ipv4Cidr() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of("10.0.0.0/8"));

            assertTrue(resolver.isTrusted("10.0.0.1"));
            assertTrue(resolver.isTrusted("10.255.255.254"));
            assertFalse(resolver.isTrusted("11.0.0.1"));
        }

        @Test
        void nonByteAlignedPrefix() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of("192.168.0.0/20"));

            assertTrue(resolver.isTrusted("192.168.0.1"));
            assertTrue(resolver.isTrusted("192.168.15.254"));
            assertFalse(resolver.isTrusted("192.168.16.1"));
        }

        @Test
        void ipv6ExactAndCidr() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(
                    List.of("::1", "2001:db8::/32"));

            assertTrue(resolver.isTrusted("::1"));
            assertTrue(resolver.isTrusted("2001:db8:1234::9"));
            assertFalse(resolver.isTrusted("2001:db9::1"));
        }

        @Test
        @DisplayName("主机名不做 DNS 解析，一律当作不可信")
        void hostnamesAreNeverTrusted() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of("10.0.0.0/8"));

            // getByName would resolve it over DNS: a blocking, spoofable lookup
            // on the rate-limit path.
            assertFalse(resolver.isTrusted("evil.example.com"));
            assertFalse(resolver.isTrusted("localhost"));
        }

        @Test
        void bracketedIpv6WithPort() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of("::1"));

            assertTrue(resolver.isTrusted("[::1]"));
        }

        @Test
        void addressFamilyMismatchIsNotTrusted() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of("10.0.0.0/8"));

            assertFalse(resolver.isTrusted("::1"));
        }

        @Test
        void unparseableInputIsNotTrusted() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of("10.0.0.0/8"));

            assertFalse(resolver.isTrusted(null));
            assertFalse(resolver.isTrusted(""));
            assertFalse(resolver.isTrusted("[::1"));
        }

        @Test
        @DisplayName("形状像地址但解析失败的值按不可信处理")
        void addressShapedButUnparseableIsNotTrusted() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of("10.0.0.0/8"));

            // 全是数字和点，所以通过了字面量判别，但 999 不是合法八位组。
            assertFalse(resolver.isTrusted("10.0.0.999"));
            // 同理：带冒号所以走 IPv6 路径，却不是合法的 IPv6 字面量。
            assertFalse(resolver.isTrusted("not:an:address"));
        }
    }

    @Nested
    @DisplayName("配置错误在启动期就失败")
    class Configuration {

        @Test
        void nonNumericPrefixIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> TrustedProxyResolver.of(List.of("10.0.0.0/abc")));
        }

        @Test
        void outOfRangePrefixIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> TrustedProxyResolver.of(List.of("10.0.0.0/33")));
            assertThrows(IllegalArgumentException.class,
                    () -> TrustedProxyResolver.of(List.of("::1/129")));
        }

        @Test
        void negativePrefixIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> TrustedProxyResolver.of(List.of("10.0.0.0/-1")));
        }

        @Test
        void nonAddressPatternIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> TrustedProxyResolver.of(List.of("proxy.internal")));
        }

        @Test
        void nullEntryIsRejected() {
            List<String> withNull = new java.util.ArrayList<>();
            withNull.add("10.0.0.0/8");
            withNull.add(null);

            assertThrows(IllegalArgumentException.class,
                    () -> TrustedProxyResolver.of(withNull));
        }
    }

    @Nested
    @DisplayName("客户端地址解析")
    class Resolution {

        @Test
        @DisplayName("直连对端可信时，取链上最右的不可信地址")
        void takesTheRightmostUntrustedHop() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(
                    List.of("192.168.1.0/24", "10.0.0.0/8"));

            assertEquals("203.0.113.7",
                    resolver.resolveClientAddress("192.168.1.1", "203.0.113.7"));
            assertEquals("203.0.113.7",
                    resolver.resolveClientAddress("192.168.1.1", "198.51.100.1, 203.0.113.7"));
        }

        @Test
        @DisplayName("从右往左剥离可信代理，伪造的前置跳数永远取不到")
        void stripsTrustedProxiesFromTheRight() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(
                    List.of("192.168.1.0/24", "10.0.0.0/8"));

            // The attacker prepends 6.6.6.6; only the real right-most hop counts.
            assertEquals("8.8.8.8",
                    resolver.resolveClientAddress("192.168.1.1", "6.6.6.6, 8.8.8.8"));
        }

        @Test
        @DisplayName("对端不可信时头被完全忽略")
        void untrustedPeerIgnoresTheHeaderEntirely() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of("10.0.0.0/8"));

            assertEquals("198.51.100.9",
                    resolver.resolveClientAddress("198.51.100.9", "1.2.3.4"));
        }

        @Test
        @DisplayName("整条链都是可信代理时返回最左侧那个")
        void allTrustedChainReturnsTheLeftmostHop() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(
                    List.of("192.168.1.0/24", "10.0.0.0/8"));

            assertEquals("10.0.0.5",
                    resolver.resolveClientAddress("192.168.1.1", "10.0.0.5"));
        }

        @Test
        void blankHeaderFallsBackToPeer() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of("10.0.0.0/8"));

            assertEquals("10.0.0.1", resolver.resolveClientAddress("10.0.0.1", "   "));
            assertEquals("10.0.0.1", resolver.resolveClientAddress("10.0.0.1", null));
        }

        @Test
        void surroundingWhitespaceIsTrimmed() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of("10.0.0.0/8"));

            assertEquals("203.0.113.7",
                    resolver.resolveClientAddress(" 10.0.0.1 ", "  203.0.113.7 , 10.0.0.9 "));
        }

        @Test
        void blankPeerYieldsEmptyIdentifier() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of("10.0.0.0/8"));

            assertEquals("", resolver.resolveClientAddress(null, "1.2.3.4"));
            assertEquals("", resolver.resolveClientAddress("   ", "1.2.3.4"));
        }

        @Test
        @DisplayName("链里的空段被跳过，不会中断解析")
        void blankHopsAreSkipped() {
            TrustedProxyResolver resolver = TrustedProxyResolver.of(List.of("10.0.0.0/8"));

            // 某些代理会留下空段；跳过它而不是把它当成客户端。
            assertEquals("203.0.113.7",
                    resolver.resolveClientAddress("10.0.0.1", "203.0.113.7, , 10.0.0.9"));
            assertEquals("203.0.113.7",
                    resolver.resolveClientAddress("10.0.0.1", ", , 203.0.113.7"));
        }
    }
}
