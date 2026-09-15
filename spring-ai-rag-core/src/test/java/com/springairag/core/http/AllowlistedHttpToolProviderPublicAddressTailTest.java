package com.springairag.core.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.skill.RuntimeSkillCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * AllowlistedHttpToolProvider 的 SSRF 公网地址守卫（Batch 419）：
 * publicAddress 对 IPv4 特殊网段、IPv6 ULA/链路本地/组播/文档
 * 前缀、NAT64/IPv4 内嵌地址的判定矩阵。
 */
class AllowlistedHttpToolProviderPublicAddressTailTest {

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
        Constructor<?> constructor =
                callbackClass.getDeclaredConstructor(
                        AllowlistedHttpToolProvider.class,
                        RagChatProperties.HttpEndpointProperties.class);
        constructor.setAccessible(true);
        callback = constructor.newInstance(provider, endpoint);
        publicAddress = callbackClass.getDeclaredMethod(
                "publicAddress", InetAddress.class);
        publicAddress.setAccessible(true);
    }

    private boolean isPublic(String host) throws Exception {
        return (boolean) publicAddress.invoke(
                callback, InetAddress.getByName(host));
    }

    private boolean isPublic(byte[] bytes) throws Exception {
        return (boolean) publicAddress.invoke(
                callback, InetAddress.getByAddress(bytes));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8", "1.1.1.1", "2600::1", "2620:fe::fe"})
    void globalUnicastAddressesArePublic(String host) throws Exception {
        assertTrue(isPublic(host));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "10.1.2.3", "172.16.0.9", "172.31.255.254",
            "192.168.7.7", "169.254.3.3", "100.64.0.8",
            "192.0.0.9", "192.0.2.9", "192.88.99.9",
            "198.18.0.9", "198.19.0.9", "198.51.100.9", "203.0.113.9",
            "0.0.0.0", "224.0.0.5"})
    void specialUseIpv4RangesAreNotPublic(String host) throws Exception {
        assertFalse(isPublic(host));
    }

    @Test
    void ipv6SpecialRangesAreNotPublic() throws Exception {
        assertFalse(isPublic("::1"));
        assertFalse(isPublic("fc00::5"));
        assertFalse(isPublic("fd12:3456::5"));
        assertFalse(isPublic("fe80::9"));
        assertFalse(isPublic("ff02::1"));
        assertFalse(isPublic("2001:db8::9"));
        assertFalse(isPublic("2002:c000:201::"));
        assertFalse(isPublic("4000::9"));
    }

    @Test
    void nat64AndIPv4EmbeddedAddressesDelegateToIpv4Checks()
            throws Exception {
        // 既有语义：64:ff9b::/96 内嵌地址因 byte1=0x64 不满足
        // IPv4 内嵌条件（要求前 10 字节全零），按普通全局单播
        // 检查 first=0x00 → 判非公网。
        byte[] nat64 = new byte[16];
        nat64[0] = 0x00;
        nat64[1] = 0x64;
        nat64[2] = (byte) 0xff;
        nat64[3] = (byte) 0x9b;
        nat64[12] = 8;
        nat64[13] = 8;
        nat64[14] = 8;
        nat64[15] = 8;
        assertFalse(isPublic(nat64));

        // ::ffff:127.0.0.1 内嵌回环 → 非公网。
        byte[] embeddedLoopback = new byte[16];
        embeddedLoopback[10] = (byte) 0xff;
        embeddedLoopback[11] = (byte) 0xff;
        embeddedLoopback[12] = 127;
        assertEquals(false, isPublic(embeddedLoopback));
    }

    @Test
    void documentationPrefixTailOutsideConfiguredBitsIsPublic()
            throws Exception {
        // 既有语义：3fff:ffff:: 不落在 hasPrefix(20, 0x3f,0xff,0x00)
        // 的文档前缀内 → 判为公网（潜在防护缺口，入档待专项处理）。
        assertTrue(isPublic("3fff:ffff::9"));
    }

    @Test
    void nullAddressIsNotPublic() throws Exception {
        assertEquals(false, (boolean) publicAddress.invoke(callback,
                (InetAddress) null));
    }
}
