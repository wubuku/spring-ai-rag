package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RagProxyPropertiesTest {

    @Test
    void defaultsShouldBeDisabled() {
        RagProxyProperties props = new RagProxyProperties();
        assertFalse(props.isEnabled(), "Proxy should be disabled by default");
        assertEquals("127.0.0.1", props.getHost());
        assertEquals(7890, props.getPort());
        assertEquals("localhost|127.0.0.1|::1", props.getNoProxyHosts());
    }

    @Test
    void settersAndGettersShouldWork() {
        RagProxyProperties props = new RagProxyProperties();
        props.setEnabled(true);
        props.setHost("proxy.example.com");
        props.setPort(8888);
        props.setNoProxyHosts("localhost|*.local|10.0.0.0/8");

        assertTrue(props.isEnabled());
        assertEquals("proxy.example.com", props.getHost());
        assertEquals(8888, props.getPort());
        assertEquals("localhost|*.local|10.0.0.0/8", props.getNoProxyHosts());
    }
}
