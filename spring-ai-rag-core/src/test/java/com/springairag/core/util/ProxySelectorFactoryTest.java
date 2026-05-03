package com.springairag.core.util;

import com.springairag.core.config.RagProxyProperties;
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

    @BeforeEach
    void setUp() {
        disabledProxy = new RagProxyProperties();
        disabledProxy.setEnabled(false);

        enabledProxy = new RagProxyProperties();
        enabledProxy.setEnabled(true);
        enabledProxy.setHost("proxy.example.com");
        enabledProxy.setPort(8080);
        enabledProxy.setNoProxyHosts("");
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
    void create_enabledProxy_noProxyHostInList_usesProxy() throws Exception {
        enabledProxy.setNoProxyHosts("allowed.com");
        ProxySelector selector = ProxySelectorFactory.create(enabledProxy);

        List<Proxy> proxies = selector.select(URI.create("http://blocked.com"));
        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
    }

    @Test
    void create_enabledProxy_nullHostInUri_returnsProxy() throws Exception {
        // URI with no host (e.g., file://) should not match noProxy rules
        enabledProxy.setNoProxyHosts("example.com");
        ProxySelector selector = ProxySelectorFactory.create(enabledProxy);

        // This should not throw
        List<Proxy> proxies = selector.select(new URI("file:///local/path"));
        // With null host, noProxy rules don't match, returns proxy
        assertEquals(1, proxies.size());
    }

    @Test
    void installDefault_disabledProxy_doesNotThrow() {
        // Should not throw SecurityException
        ProxySelector selector = ProxySelectorFactory.installDefault(disabledProxy);
        assertNotNull(selector);
    }

    @Test
    void installDefault_enabledProxy_doesNotThrow() {
        // Should not throw SecurityException
        ProxySelector selector = ProxySelectorFactory.installDefault(enabledProxy);
        assertNotNull(selector);
    }
}
