package com.springairag.core.util;

import com.springairag.core.config.RagProxyProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProxySelectorFactory}.
 */
class ProxySelectorFactoryTest {

    private RagProxyProperties disabledProxy;
    private RagProxyProperties enabledProxy;

    /**
     * installDefault 改的是 JVM 全局默认选择器。这两个用例必须把它装上，
     * 所以先把原值存下来，测完还原——否则会顺着 surefire 的同一个 JVM
     * 漏给后面随机排队的测试。
     */
    private ProxySelector originalDefault;

    @BeforeEach
    void setUp() {
        originalDefault = ProxySelector.getDefault();
        disabledProxy = new RagProxyProperties();
        disabledProxy.setEnabled(false);

        enabledProxy = new RagProxyProperties();
        enabledProxy.setEnabled(true);
        enabledProxy.setHost("proxy.example.com");
        enabledProxy.setPort(8080);
        enabledProxy.setNoProxyHosts("");
    }

    @AfterEach
    void restoreDefault() {
        ProxySelector.setDefault(originalDefault);
    }

    @Test
    void create_disabledProxy_returnsNoProxySelector() {
        ProxySelector selector = ProxySelectorFactory.create(disabledProxy);
        List<Proxy> proxies = selector.select(URI.create("http://example.com"));

        // ProxySelector.of(null) returns NO_PROXY (DIRECT)
        assertNotNull(selector);
        assertEquals(1, proxies.size());
        assertEquals(Proxy.NO_PROXY, proxies.get(0));
    }

    @Test
    void create_enabledProxy_noNoProxy_returnsProxyForAllHosts() throws Exception {
        ProxySelector selector = ProxySelectorFactory.create(enabledProxy);

        List<Proxy> proxies = selector.select(URI.create("http://example.com"));
        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
        InetSocketAddress addr = (InetSocketAddress) proxies.get(0).address();
        assertEquals("proxy.example.com", addr.getHostName());
        assertEquals(8080, addr.getPort());
    }

    @Test
    void create_enabledProxy_exactNoProxyMatch_bypassesProxy() throws Exception {
        enabledProxy.setNoProxyHosts("example.com|localhost");
        ProxySelector selector = ProxySelectorFactory.create(enabledProxy);

        List<Proxy> proxies = selector.select(URI.create("http://example.com"));
        assertEquals(1, proxies.size());
        assertEquals(Proxy.NO_PROXY, proxies.get(0));
    }

    @Test
    void create_enabledProxy_wildcardNoProxyMatch_usesProxy() throws Exception {
        enabledProxy.setNoProxyHosts("*.example.com");
        ProxySelector selector = ProxySelectorFactory.create(enabledProxy);

        // Wildcard *.example.com matches subdomains - returns proxy for the domain
        // (wildcard suffix means "ends with", so it routes through proxy)
        List<Proxy> proxies = selector.select(URI.create("http://www.example.com"));
        assertEquals(1, proxies.size());
        // Wildcard returns the proxy (not NO_PROXY)
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
    }

    @Test
    void create_enabledProxy_wildcardNoProxyNonMatch_usesProxy() throws Exception {
        enabledProxy.setNoProxyHosts("*.example.com");
        ProxySelector selector = ProxySelectorFactory.create(enabledProxy);

        // Different domain does not match
        List<Proxy> proxies = selector.select(URI.create("http://other.com"));
        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
    }

    @Test
    void create_enabledProxy_hostOutsideNoProxyList_usesProxy() throws Exception {
        enabledProxy.setNoProxyHosts("allowed.com");
        ProxySelector selector = ProxySelectorFactory.create(enabledProxy);

        // 名字原先叫 noProxyHostInList_usesProxy，读起来像"在 noProxy 名单里
        // 却仍然走代理"，可这里请求的是 blocked.com——恰恰不在名单里。
        // 行为本身是对的（不在名单 → 走代理），错的是名字会和上面那条
        // exactNoProxyMatch_bypassesProxy 正好读反。Batch 953 改名。
        List<Proxy> proxies = selector.select(URI.create("http://blocked.com"));
        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
    }

    @Test
    void create_enabledProxy_nullHostInUri_returnsProxy() throws Exception {
        // URI with no host (e.g., file://) should not match noProxy rules
        enabledProxy.setNoProxyHosts("example.com");
        ProxySelector selector = ProxySelectorFactory.create(enabledProxy);

        List<Proxy> proxies = selector.select(new URI("file:///local/path"));
        // 原先只断言 size==1，可 Proxy.NO_PROXY 同样满足——那样这条用例
        // 就和上面「命中 noProxy 就绕过」的那条分不开了。
        // 名字写着 returnsProxy，就得验到代理本身。
        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
        assertEquals("proxy.example.com",
                ((InetSocketAddress) proxies.get(0).address()).getHostName());
    }

    @Test
    void installDefault_disabledProxy_installsTheDirectSelector() {
        ProxySelector selector = ProxySelectorFactory.installDefault(disabledProxy);

        // 名字原先叫 doesNotThrow，断言只有 assertNotNull(selector)——
        // 而这个方法存在的全部意义就是把选择器装成 JVM 默认值。
        // 不验 ProxySelector.getDefault() 的话，"装上了"这件事没人看着。
        assertSame(selector, ProxySelector.getDefault());
        assertEquals(Proxy.NO_PROXY,
                ProxySelector.getDefault().select(URI.create("http://example.com")).get(0));
    }

    @Test
    void installDefault_enabledProxy_installsTheProxySelector() throws Exception {
        ProxySelector selector = ProxySelectorFactory.installDefault(enabledProxy);

        assertSame(selector, ProxySelector.getDefault());
        List<Proxy> proxies =
                ProxySelector.getDefault().select(URI.create("http://example.com"));
        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
        assertEquals(8080,
                ((InetSocketAddress) proxies.get(0).address()).getPort());
    }
}
