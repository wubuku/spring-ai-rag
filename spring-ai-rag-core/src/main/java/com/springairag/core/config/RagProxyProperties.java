package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP Proxy Configuration
 *
 * <p>Supports configuring HTTP(S) proxy for LLM API calls.
 * Disabled by default (noProxy=true) to avoid dev environment proxy interference.
 *
 * <p>Configuration example:
 * <pre>
 * rag:
 *   proxy:
 *     enabled: true
 *     host: 127.0.0.1
 *     port: 7890
 *     no-proxy-hosts: localhost|127.0.0.1|*.internal
 * </pre>
 */
@ConfigurationProperties(prefix = "rag.proxy")
public class RagProxyProperties {

    /**
     * Whether to enable HTTP proxy, default false (disabled).
     */
    private boolean enabled = false;

    /**
     * Proxy host address.
     */
    private String host = "127.0.0.1";

    /**
     * Proxy port.
     */
    private int port = 7890;

    /**
     * Hosts to skip proxy for, supports wildcards, multiple separated by |.
     * Example: localhost|127.0.0.1|*.internal|*.local
     */
    private String noProxyHosts = "localhost|127.0.0.1|::1";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getNoProxyHosts() {
        return noProxyHosts;
    }

    public void setNoProxyHosts(String noProxyHosts) {
        this.noProxyHosts = noProxyHosts;
    }
}
