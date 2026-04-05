package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP 代理配置
 *
 * <p>支持为 LLM API 调用配置 HTTP(S) 代理。
 * 默认禁用（noProxy=true），避免开发环境代理干扰。
 *
 * <p>配置示例：
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
     * 是否启用 HTTP 代理，默认 false（不启用）
     */
    private boolean enabled = false;

    /**
     * 代理主机地址
     */
    private String host = "127.0.0.1";

    /**
     * 代理端口
     */
    private int port = 7890;

    /**
     * 跳过代理的主机列表，支持通配符，多个用 | 分隔
     * 例如：localhost|127.0.0.1|*.internal|*.local
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
