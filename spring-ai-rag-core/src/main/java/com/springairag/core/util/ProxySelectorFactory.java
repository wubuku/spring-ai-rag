package com.springairag.core.util;

import com.springairag.core.config.RagProxyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

/**
 * Factory for creating JVM {@link ProxySelector} instances based on {@link RagProxyProperties}.
 *
 * <p>Handles two scenarios:
 * <ul>
 *   <li>Proxy disabled: creates a no-op selector that bypasses all proxies</li>
 *   <li>Proxy enabled: creates a selector that routes requests through the configured proxy,
 *       with support for wildcard domain suffixes in noProxyHosts (e.g., {@code *.local})</li>
 * </ul>
 */
public final class ProxySelectorFactory {

    private static final Logger log = LoggerFactory.getLogger(ProxySelectorFactory.class);

    private ProxySelectorFactory() {}

    /**
     * Creates and installs the JVM-wide default ProxySelector based on the given properties.
     *
     * @param properties proxy configuration (never null)
     * @return the installed ProxySelector, or the existing default if installation failed
     */
    public static ProxySelector installDefault(RagProxyProperties properties) {
        ProxySelector selector = create(properties);
        try {
            ProxySelector.setDefault(selector);
            if (properties.isEnabled()) {
                log.info("JVM proxy installed: {}:{}, noProxy={}",
                        properties.getHost(), properties.getPort(), properties.getNoProxyHosts());
            } else {
                log.info("JVM proxy disabled (rag.proxy.enabled=false), NO_PROXY selector active");
            }
            return selector;
        } catch (SecurityException | NullPointerException e) {
            log.warn("Failed to install ProxySelector: {}", e.getMessage());
            return ProxySelector.getDefault();
        }
    }

    /**
     * Creates a ProxySelector based on the given properties.
     *
     * @param properties proxy configuration (never null)
     * @return a ProxySelector instance
     */
    public static ProxySelector create(RagProxyProperties properties) {
        if (!properties.isEnabled()) {
            return ProxySelector.of(null); // NO_PROXY — bypass all proxies
        }

        Proxy proxyHost = new Proxy(
                Proxy.Type.HTTP,
                new InetSocketAddress(properties.getHost(), properties.getPort()));

        String[] noProxyArray = properties.getNoProxyHosts().split("\\|");

        return new ConfigurableProxySelector(proxyHost, noProxyArray);
    }

    /**
     * Configurable ProxySelector that routes requests through a given proxy,
     * honouring wildcard domain suffixes in noProxyHosts.
     */
    private static final class ConfigurableProxySelector extends ProxySelector {

        private final Proxy proxyHost;
        private final String[] noProxyArray;

        ConfigurableProxySelector(Proxy proxyHost, String[] noProxyArray) {
            this.proxyHost = proxyHost;
            this.noProxyArray = noProxyArray;
        }

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri.getHost();
            if (host != null) {
                for (String np : noProxyArray) {
                    if (np.startsWith("*.")) {
                        // Wildcard suffix: *.example.com matches www.example.com
                        String domain = np.substring(2);
                        if (host.endsWith(domain)) {
                            return List.of(proxyHost);
                        }
                    } else if (host.equals(np)) {
                        // Exact match: bypass proxy
                        return List.of(Proxy.NO_PROXY);
                    }
                }
            }
            return List.of(proxyHost);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException e) {
            log.warn("Proxy connection failed for {}: {}", uri, e.getMessage());
        }
    }
}
