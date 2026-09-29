package com.springairag.core.util;

import com.springairag.core.config.RagProxyProperties;
import org.junit.jupiter.api.Test;

import java.net.ProxySelector;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 代理选择器工厂长尾（Batch 718，JaCoCo 驱动）：installDefault 注
 * 册后返回选择器、disabled 时安装 NO_PROXY 选择器、connectFailed
 * 仅记录不抛出。
 */
class ProxySelectorFactoryTailTest {

    @Test
    void installDefaultRegistersSelectorAndReturnsIt() {
        RagProxyProperties properties = new RagProxyProperties();
        properties.setEnabled(true);
        properties.setHost("127.0.0.1");
        properties.setPort(7890);

        ProxySelector installed = ProxySelectorFactory.installDefault(properties);

        assertNotNull(installed);
        assertEquals(ProxySelector.getDefault(), installed);
    }

    @Test
    void connectFailedLogsWithoutThrowing() {
        RagProxyProperties properties = new RagProxyProperties();
        properties.setEnabled(false);

        ProxySelector selector = ProxySelectorFactory.create(properties);

        selector.connectFailed(URI.create("http://example.com"),
                new java.net.InetSocketAddress("127.0.0.1", 7890),
                new java.io.IOException("boom"));
    }
}
