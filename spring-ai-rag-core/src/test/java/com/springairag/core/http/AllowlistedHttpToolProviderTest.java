package com.springairag.core.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.RagChatToolContextKeys;
import com.springairag.api.service.RagChatToolRequestContext;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.skill.RuntimeSkill;
import com.springairag.core.skill.RuntimeSkillCatalog;
import com.springairag.core.skill.RuntimeSkillLoadSession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetAddress;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AllowlistedHttpToolProviderTest {

    @Test
    void springCreatesProviderThroughProductionConstructor() {
        new ApplicationContextRunner()
                .withBean(RuntimeSkillCatalog.class,
                        () -> mock(RuntimeSkillCatalog.class))
                .withBean(RagChatProperties.class, RagChatProperties::new)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(AllowlistedHttpToolProvider.class)
                .run(context -> {
                    assertTrue(context.isRunning());
                    assertEquals(1, context.getBeansOfType(
                            AllowlistedHttpToolProvider.class).size());
                });
    }

    @Test
    void callsFixedEndpointOnlyAfterSkillLoadAndBoundsParameters() {
        RagChatProperties properties = properties();
        RuntimeSkillCatalog catalog = catalog();
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        AllowlistedHttpToolProvider provider = provider(
                properties,
                catalog,
                (request, timeout, maxBytes, addresses) -> {
                    captured.set(request);
                    return new AllowlistedHttpToolProvider.HttpResponseData(
                            200,
                            "application/json; charset=utf-8",
                            "{\"temperature\":21,\"city\":\"上海\"}"
                                    .getBytes(StandardCharsets.UTF_8));
                },
                host -> new InetAddress[] {
                        InetAddress.getByAddress(
                                host, new byte[] {93, (byte) 184, (byte) 216, 34})});

        ToolCallback callback = provider.getToolCallbacks().getFirst();
        RuntimeSkillLoadSession session = loadedSession();
        String result = callback.call(
                "{\"city\":\"上海\",\"units\":\"metric\"}",
                context(session, 10_000));

        assertTrue(result.contains("\"temperature\":21"));
        assertEquals(
                "https://weather.example.test/v1/forecast?city=%E4%B8%8A%E6%B5%B7&units=metric",
                captured.get().uri().toString());
        assertEquals("application/json",
                captured.get().headers().firstValue("Accept").orElseThrow());
    }

    @Test
    void rejectsCallsWithoutLoadedSkillBeforeTransport() {
        RagChatProperties properties = properties();
        RuntimeSkillCatalog catalog = catalog();
        AtomicInteger calls = new AtomicInteger();
        AllowlistedHttpToolProvider provider = provider(
                properties,
                catalog,
                (request, timeout, maxBytes, addresses) -> {
                    calls.incrementAndGet();
                    return new AllowlistedHttpToolProvider.HttpResponseData(
                            200, "application/json", "{}".getBytes());
                },
                publicResolver());

        String result = provider.getToolCallbacks().getFirst().call(
                "{\"city\":\"Shanghai\"}",
                context(new RuntimeSkillLoadSession(2, 2, 2_000), 10_000));

        assertEquals("{\"error\":\"skill_not_loaded\"}", result);
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsPrivateDnsTargetsAndUnknownArguments() {
        RagChatProperties properties = properties();
        RuntimeSkillCatalog catalog = catalog();
        AtomicInteger calls = new AtomicInteger();
        AllowlistedHttpToolProvider provider = provider(
                properties,
                catalog,
                (request, timeout, maxBytes, addresses) -> {
                    calls.incrementAndGet();
                    return new AllowlistedHttpToolProvider.HttpResponseData(
                            200, "application/json", "{}".getBytes());
                },
                host -> new InetAddress[] {
                        InetAddress.getByAddress(
                                host, new byte[] {127, 0, 0, 1})});

        ToolCallback callback = provider.getToolCallbacks().getFirst();
        assertEquals("{\"error\":\"network_target_rejected\"}",
                callback.call(
                        "{\"city\":\"Shanghai\"}",
                        context(loadedSession(), 10_000)));
        assertEquals("{\"error\":\"invalid_tool_input\"}",
                callback.call(
                        "{\"city\":\"Shanghai\",\"unexpected\":\"x\"}",
                        context(loadedSession(), 10_000)));
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsRedirectsOversizedBodiesAndDeepJson() {
        RagChatProperties properties = properties();
        RuntimeSkillCatalog catalog = catalog();
        AtomicReference<AllowlistedHttpToolProvider.HttpResponseData> response =
                new AtomicReference<>();
        AllowlistedHttpToolProvider provider = provider(
                properties,
                catalog,
                (request, timeout, maxBytes, addresses) -> response.get(),
                publicResolver());
        ToolCallback callback = provider.getToolCallbacks().getFirst();

        response.set(new AllowlistedHttpToolProvider.HttpResponseData(
                302, "application/json",
                "{\"location\":\"https://other.example.test\"}"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals("{\"error\":\"http_status_not_allowed\"}",
                callback.call(
                        "{\"city\":\"x\"}",
                        context(loadedSession(), 10_000)));

        response.set(new AllowlistedHttpToolProvider.HttpResponseData(
                200, "application/json",
                "12345678901234567890123456789012345678901234567890123456789012345"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals("{\"error\":\"response_too_large\"}",
                callback.call(
                        "{\"city\":\"x\"}",
                        context(loadedSession(), 10_000)));

        response.set(new AllowlistedHttpToolProvider.HttpResponseData(
                200, "application/json",
                "{\"a\":{\"b\":{\"c\":1}}}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("{\"error\":\"invalid_json_response\"}",
                callback.call(
                        "{\"city\":\"x\"}",
                        context(loadedSession(), 10_000)));
    }

    @Test
    void pinsValidatedDnsAndReservesTheCumulativeByteBudgetBeforeRead()
            throws Exception {
        RagChatProperties properties = properties();
        RuntimeSkillCatalog catalog = catalog();
        AtomicReference<InetAddress[]> pinned = new AtomicReference<>();
        List<Integer> transportLimits =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        byte[] firstBody = ("{\"value\":\"" + "x".repeat(30) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        AllowlistedHttpToolProvider provider = provider(
                properties,
                catalog,
                (request, timeout, maxBytes, addresses) -> {
                    pinned.set(addresses);
                    transportLimits.add(maxBytes);
                    return new AllowlistedHttpToolProvider.HttpResponseData(
                            200,
                            "application/json",
                            calls.getAndIncrement() == 0
                                    ? firstBody
                                    : "{}".getBytes(StandardCharsets.UTF_8));
                },
                publicResolver());
        HttpToolExecutionState state = new HttpToolExecutionState(80);
        ToolContext context = context(loadedSession(), state);
        ToolCallback callback = provider.getToolCallbacks().getFirst();

        callback.call("{\"city\":\"first\"}", context);
        callback.call("{\"city\":\"second\"}", context);

        assertArrayEquals(
                publicResolver().resolve("weather.example.test"),
                pinned.get());
        assertEquals(
                List.of(64, 80 - firstBody.length),
                transportLimits);
        assertEquals(firstBody.length + 2, state.responseBytes());
        assertEquals(0, state.reservedBytes());
    }

    @Test
    void rejectsEncodedPathEscapesAndRequiresPinnedHosts() throws Exception {
        RagChatProperties properties = properties();
        properties.getHttpTools().getEndpoints().getFirst()
                .setPath("/v1/%2e%2e/admin");
        assertThrows(IllegalStateException.class, properties::validate);

        properties.getHttpTools().getEndpoints().getFirst()
                .setPath("/v1/%252e%252e/admin");
        assertThrows(IllegalStateException.class, properties::validate);

        AllowlistedHttpToolProvider.PinnedDnsResolver resolver =
                new AllowlistedHttpToolProvider.PinnedDnsResolver();
        InetAddress[] validated = publicResolver().resolve(
                "weather.example.test");
        resolver.pin("weather.example.test", validated);
        assertArrayEquals(validated, resolver.resolve("weather.example.test"));
        assertThrows(
                java.net.UnknownHostException.class,
                () -> resolver.resolve("unvalidated.example.test"));
    }

    @Test
    void rejectsIpv6TranslationTunnelAndSpecialUseTargets() throws Exception {
        List<String> rejected = List.of(
                "64:ff9b::a9fe:a9fe",
                "64:ff9b:1::a9fe:a9fe",
                "100::1",
                "2001:0000:4136:e378:8000:63bf:3fff:fdd2",
                "2001:2::1",
                "2001:db8::1",
                "2002:a9fe:a9fe::1",
                "3fff::1");

        for (String address : rejected) {
            AtomicInteger calls = new AtomicInteger();
            AllowlistedHttpToolProvider provider = provider(
                    properties(),
                    catalog(),
                    (request, timeout, maxBytes, addresses) -> {
                        calls.incrementAndGet();
                        return new AllowlistedHttpToolProvider.HttpResponseData(
                                200, "application/json", "{}".getBytes());
                    },
                    host -> new InetAddress[] {
                            InetAddress.getByName(address)});

            String result = provider.getToolCallbacks().getFirst().call(
                    "{\"city\":\"Shanghai\"}",
                    context(loadedSession(), 10_000));

            assertEquals(
                    "{\"error\":\"network_target_rejected\"}",
                    result,
                    address);
            assertEquals(0, calls.get(), address);
        }
    }

    @Test
    void acceptsOrdinaryIpv6GlobalUnicastTarget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AllowlistedHttpToolProvider provider = provider(
                properties(),
                catalog(),
                (request, timeout, maxBytes, addresses) -> {
                    calls.incrementAndGet();
                    return new AllowlistedHttpToolProvider.HttpResponseData(
                            200,
                            "application/json",
                            "{}".getBytes(StandardCharsets.UTF_8));
                },
                host -> new InetAddress[] {
                        InetAddress.getByName("2606:4700:4700::1111")});

        String result = provider.getToolCallbacks().getFirst().call(
                "{\"city\":\"Shanghai\"}",
                context(loadedSession(), 10_000));

        assertTrue(result.contains("\"status\":200"));
        assertEquals(1, calls.get());
    }

    private RagChatProperties properties() {
        RagChatProperties properties = new RagChatProperties();
        RagChatProperties.HttpToolProperties http =
                properties.getHttpTools();
        http.setEnabled(true);
        http.setMaxTotalResponseBytes(128);

        RagChatProperties.HttpEndpointProperties endpoint =
                new RagChatProperties.HttpEndpointProperties();
        endpoint.setToolName("getWeather");
        endpoint.setSkillName("weather");
        endpoint.setCapability("weather.read");
        endpoint.setBaseUrl("https://weather.example.test");
        endpoint.setPath("/v1/forecast");
        endpoint.setMaxResponseBytes(64);
        endpoint.setMaxResultCharacters(4_000);
        endpoint.setMaxJsonDepth(2);
        endpoint.setMaxJsonNodes(20);
        endpoint.setMaxJsonArrayItems(4);
        endpoint.setQueryParameters(List.of(
                parameter("city", true, 64),
                parameter("units", false, 16)));
        http.setEndpoints(List.of(endpoint));
        properties.validate();
        return properties;
    }

    private RagChatProperties.HttpQueryParameterProperties parameter(
            String name,
            boolean required,
            int maxLength) {
        RagChatProperties.HttpQueryParameterProperties parameter =
                new RagChatProperties.HttpQueryParameterProperties();
        parameter.setName(name);
        parameter.setRequired(required);
        parameter.setMaxLength(maxLength);
        return parameter;
    }

    private RuntimeSkillCatalog catalog() {
        RuntimeSkillCatalog catalog = mock(RuntimeSkillCatalog.class);
        RuntimeSkill skill = mock(RuntimeSkill.class);
        when(skill.capabilities()).thenReturn(List.of("weather.read"));
        when(catalog.find("weather")).thenReturn(skill);
        return catalog;
    }

    private AllowlistedHttpToolProvider provider(
            RagChatProperties properties,
            RuntimeSkillCatalog catalog,
            AllowlistedHttpToolProvider.HttpTransport transport,
            AllowlistedHttpToolProvider.AddressResolver resolver) {
        return new AllowlistedHttpToolProvider(
                catalog, properties, new ObjectMapper(), transport, resolver);
    }

    private RuntimeSkillLoadSession loadedSession() {
        RuntimeSkillLoadSession session = new RuntimeSkillLoadSession(
                2, 2, 2_000);
        session.markLoaded("weather");
        return session;
    }

    private ToolContext context(
            RuntimeSkillLoadSession session,
            long totalResponseBytes) {
        return context(
                session,
                new HttpToolExecutionState(totalResponseBytes));
    }

    private ToolContext context(
            RuntimeSkillLoadSession session,
            HttpToolExecutionState state) {
        return new ToolContext(Map.of(
                RagChatToolContextKeys.REQUEST,
                new RagChatToolRequestContext(
                        "principal", "USER", false, "session",
                        null, ChatMode.AGENT, "test/model",
                        Instant.now().plusSeconds(10)),
                RuntimeSkillLoadSession.CONTEXT_KEY, session,
                HttpToolExecutionState.CONTEXT_KEY, state));
    }

    private AllowlistedHttpToolProvider.AddressResolver publicResolver() {
        return host -> new InetAddress[] {
                InetAddress.getByAddress(
                        host, new byte[] {93, (byte) 184, (byte) 216, 34})};
    }
}
