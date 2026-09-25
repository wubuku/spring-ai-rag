package com.springairag.core.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.skill.RuntimeSkillCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * AllowlistedHttpToolProvider 传输层长尾（Batch 638，JaCoCo 驱
 * 动）：resolvePublicTarget 对空白 / null 主机返回 null、HttpTransport
 * 默认 close() 空实现可调用、readBounded 对 read()==0 的流继续读
 * 取而不是终止。
 */
class AllowlistedHttpToolProviderTransportTailTest {

    private static Object callback;
    private static AllowlistedHttpToolProvider.HttpTransport transport;

    @BeforeAll
    static void createFixtures() throws Exception {
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
        transport = (AllowlistedHttpToolProvider.HttpTransport)
                new NoopTransport();
    }

    /** execute 抛 UnsupportedOperationException 的最小传输实现。 */
    static final class NoopTransport
            implements AllowlistedHttpToolProvider.HttpTransport {
        @Override
        public AllowlistedHttpToolProvider.HttpResponseData execute(
                java.net.http.HttpRequest request,
                java.time.Duration timeout,
                int maxResponseBytes,
                java.net.InetAddress[] validatedAddresses)
                throws java.io.IOException, InterruptedException,
                java.util.concurrent.TimeoutException {
            throw new java.io.IOException("unused");
        }
    }

    private Object resolvePublicTarget(String host) throws Exception {
        Method method = callback.getClass().getDeclaredMethod(
                "resolvePublicTarget", String.class);
        method.setAccessible(true);
        return method.invoke(callback, host);
    }

    @Test
    void resolvePublicTargetReturnsNullForBlankOrNullHost() throws Exception {
        assertNull(resolvePublicTarget(null));
        assertNull(resolvePublicTarget("   "));
    }

    @Test
    void httpTransportDefaultCloseIsNoOp() {
        assertDoesNotThrow(transport::close);
    }

    @Test
    void readBoundedContinuesAfterZeroLengthRead() throws Exception {
        InputStream stream = new InputStream() {
            private int phase;

            @Override
            public int read() {
                return -1;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (phase == 0) {
                    phase = 1;
                    return 0;
                }
                if (phase == 1) {
                    phase = 2;
                    buffer[offset] = 'A';
                    return 1;
                }
                if (phase == 2) {
                    phase = 3;
                    buffer[offset] = 'B';
                    return 1;
                }
                return -1;
            }
        };

        Method method = Class.forName(
                        "com.springairag.core.http.AllowlistedHttpToolProvider"
                                + "$PinnedDnsHttpTransport")
                .getDeclaredMethod("readBounded", InputStream.class, int.class);
        method.setAccessible(true);
        byte[] body = (byte[]) method.invoke(
                newRealTransport(), stream, 16);

        assertArrayEquals(new byte[]{'A', 'B'}, body);
    }

    private Object newRealTransport() throws Exception {
        Class<?> type = Class.forName(
                "com.springairag.core.http.AllowlistedHttpToolProvider"
                        + "$PinnedDnsHttpTransport");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
