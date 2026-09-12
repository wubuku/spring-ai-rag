package com.springairag.core.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PinnedDnsHttpTransport 真实 socket 行为（Batch 306）：经本地
 * HttpServer 验证响应映射、响应体上限、超时降级与头部透传；
 * PinnedDnsResolver 的固定/解析/规范化分支。
 */
class AllowlistedHttpToolProviderPinnedTransportTest {

    private HttpServer server;
    private AtomicReference<String> seenAccept = new AtomicReference<>();
    private volatile byte[] responseBody = "{}".getBytes();
    private volatile String responseContentType = "application/json";
    private volatile int responseStatus = 200;
    private volatile long responseDelayMs;

    private AllowlistedHttpToolProvider.HttpTransport transport;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/forecast", exchange -> {
            seenAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            if (responseDelayMs > 0) {
                try {
                    Thread.sleep(responseDelayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = responseBody;
            if (responseContentType != null) {
                exchange.getResponseHeaders()
                        .add("Content-Type", responseContentType);
            }
            exchange.sendResponseHeaders(responseStatus,
                    body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            }
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        transport = newRealTransport();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (transport != null) {
            transport.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    // ── execute 响应映射 ────────────────────────────────────────────

    @Test
    void executeMapsStatusContentTypeAndBody() throws Exception {
        responseBody = "{\"temperature\":21}".getBytes();

        AllowlistedHttpToolProvider.HttpResponseData response = execute(4_096);

        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.contentType());
        assertEquals("{\"temperature\":21}",
                new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("application/json", seenAccept.get());
    }

    @Test
    void executeWithoutEntityOrContentTypeReturnsEmptyDefaults()
            throws Exception {
        responseStatus = 204;
        responseContentType = null;
        responseBody = new byte[0];

        AllowlistedHttpToolProvider.HttpResponseData response = execute(4_096);

        assertEquals(204, response.statusCode());
        assertEquals("", response.contentType());
        assertEquals(0, response.body().length);
    }

    @Test
    void headersAreForwardedToEndpoint() throws Exception {
        HttpRequest request = request(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .build();
        transport.execute(request, Duration.ofSeconds(5), 4_096,
                addresses());

        assertEquals("application/json", seenAccept.get());
    }

    // ── readBounded 上限纪律 ────────────────────────────────────────

    @Test
    void readBoundedAllowsBodyExactlyAtLimit() throws Exception {
        responseBody = new byte[32];

        AllowlistedHttpToolProvider.HttpResponseData response = execute(32);

        assertEquals(32, response.body().length);
    }

    @Test
    void readBoundedThrowsResponseTooLargeWhenExceeded() throws Exception {
        responseBody = new byte[33];

        IOException error = assertThrows(IOException.class,
                () -> execute(32));

        assertTrue(error.getClass().getName()
                .endsWith("ResponseTooLargeException"),
                "应抛出 ResponseTooLargeException: " + error);
    }

    @Test
    void readTimeoutIsNormalizedToTimeoutExceptionWithCause() {
        responseBody = new byte[16];
        responseDelayMs = 2_000;

        TimeoutException error = assertThrows(TimeoutException.class,
                () -> execute(4_096, Duration.ofMillis(300)));

        assertTrue(error.getCause() instanceof IOException,
                "超时异常应保留底层 IO 原因");
    }

    // ── PinnedDnsResolver ───────────────────────────────────────────

    @Test
    void resolverPinsNormalizesAndCopies() throws Exception {
        AllowlistedHttpToolProvider.PinnedDnsResolver resolver =
                new AllowlistedHttpToolProvider.PinnedDnsResolver();
        InetAddress[] validated = new InetAddress[] {
                InetAddress.getByAddress(
                        new byte[] {93, (byte) 184, (byte) 216, 34})};

        // 大写主机名固定、小写解析 → 键归一化。
        resolver.pin("WEATHER.Example.test", validated);
        InetAddress[] resolved = resolver.resolve("weather.example.test");
        assertArrayEquals(validated, resolved);
        // 解析结果为防御性副本，改写不影响后续解析。
        resolved[0] = InetAddress.getByAddress(new byte[] {1, 2, 3, 4});
        assertArrayEquals(validated, resolver.resolve("weather.example.test"));
        assertEquals("weather.example.test",
                resolver.resolveCanonicalHostname("weather.example.test"));
    }

    @Test
    void resolverRejectsInvalidPinsAndUnpinnedHosts() throws Exception {
        AllowlistedHttpToolProvider.PinnedDnsResolver resolver =
                new AllowlistedHttpToolProvider.PinnedDnsResolver();
        InetAddress[] validated = new InetAddress[] {
                InetAddress.getByAddress(
                        new byte[] {93, (byte) 184, (byte) 216, 34})};

        // pin 守卫：null/空白主机、null/空地址数组。
        assertThrows(java.net.UnknownHostException.class,
                () -> resolver.pin(null, validated));
        assertThrows(java.net.UnknownHostException.class,
                () -> resolver.pin("   ", validated));
        assertThrows(java.net.UnknownHostException.class,
                () -> resolver.pin("weather.example.test", null));
        assertThrows(java.net.UnknownHostException.class,
                () -> resolver.pin("weather.example.test", new InetAddress[0]));
        // 解析未固定主机与 null 主机均拒绝。
        assertThrows(java.net.UnknownHostException.class,
                () -> resolver.resolve("unpinned.example.test"));
        assertThrows(java.net.UnknownHostException.class,
                () -> resolver.resolve(null));
    }

    // ── fixture ─────────────────────────────────────────────────────

    private AllowlistedHttpToolProvider.HttpTransport newRealTransport()
            throws Exception {
        Class<?> type = Class.forName(
                "com.springairag.core.http.AllowlistedHttpToolProvider"
                        + "$PinnedDnsHttpTransport");
        java.lang.reflect.Constructor<?> constructor =
                type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (AllowlistedHttpToolProvider.HttpTransport)
                constructor.newInstance();
    }

    private AllowlistedHttpToolProvider.HttpResponseData execute(int maxBytes)
            throws Exception {
        return execute(maxBytes, Duration.ofSeconds(5));
    }

    private AllowlistedHttpToolProvider.HttpResponseData execute(
            int maxBytes, Duration timeout) throws Exception {
        return transport.execute(
                request(timeout).header("Accept", "application/json").build(),
                timeout,
                maxBytes,
                addresses());
    }

    private HttpRequest.Builder request(Duration timeout) {
        return HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
                        + "/v1/forecast"));
    }

    private InetAddress[] addresses() throws Exception {
        return new InetAddress[] {
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1})};
    }
}
