package com.springairag.core.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.skill.RuntimeSkill;
import com.springairag.core.skill.RuntimeSkillCatalog;
import com.springairag.core.skill.RuntimeSkillLoadSession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AllowlistedHttpToolProvider.EndpointCallback 私有工具长尾（Batch
 * 546，JaCoCo 驱动）：parseInput 的空白归一/非对象/未知字段拒绝、
 * endpointUri 的斜杠归一与查询编码及 https/fragment 强制、hasPrefix
 * 的长度与前缀守卫、validateJson 的深度/节点/数组上限、skillSession
 * 与 state 的缺失上下文回退。
 */
class AllowlistedHttpToolProviderCallbackHelperTailTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Object callback() {
        RagChatProperties properties = properties();
        AllowlistedHttpToolProvider provider = new AllowlistedHttpToolProvider(
                catalog(), properties, MAPPER,
                (request, timeout, maxBytes, addresses) -> ok(),
                publicResolver());
        ToolCallback toolCallback = provider.getToolCallbacks().getFirst();
        return toolCallback;
    }

    private AllowlistedHttpToolProvider.HttpResponseData ok() {
        return new AllowlistedHttpToolProvider.HttpResponseData(
                200, "application/json", "{}".getBytes());
    }

    private RuntimeSkillCatalog catalog() {
        RuntimeSkillCatalog catalog = mock(RuntimeSkillCatalog.class);
        RuntimeSkill skill = mock(RuntimeSkill.class);
        when(skill.capabilities()).thenReturn(List.of("weather.read"));
        when(catalog.find("weather")).thenReturn(skill);
        return catalog;
    }

    private AllowlistedHttpToolProvider.AddressResolver publicResolver() {
        return host -> new InetAddress[] {
                InetAddress.getByAddress(
                        host, new byte[] {93, (byte) 184, (byte) 216, 34})};
    }

    private RagChatProperties properties() {
        RagChatProperties properties = new RagChatProperties();
        RagChatProperties.HttpToolProperties http = properties.getHttpTools();
        http.setEnabled(true);
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
        return properties;
    }


    private RagChatProperties.HttpQueryParameterProperties parameter(
            String name, boolean required, int maxLength) {
        var parameter = new RagChatProperties.HttpQueryParameterProperties();
        parameter.setName(name);
        parameter.setRequired(required);
        parameter.setMaxLength(maxLength);
        return parameter;
    }

    private RagChatProperties.HttpEndpointProperties endpoint(
            String baseUrl, String path) {
        var endpoint = new RagChatProperties.HttpEndpointProperties();
        endpoint.setBaseUrl(baseUrl);
        endpoint.setPath(path);
        return endpoint;
    }

    private Object invoke(Object target, String name,
                          Class<?>[] params, Object... args)
            throws Exception {
        var method = target.getClass().getDeclaredMethod(name, params);
        method.setAccessible(true);
        if ("hasPrefix".equals(name)) {
            System.out.println("DEBUG args=" + args.length
                    + " types=" + java.util.Arrays.toString(
                            java.util.Arrays.stream(args)
                                    .map(a -> a == null ? "null"
                                            : a.getClass().getSimpleName())
                                    .toArray()));
            if (args.length == 3 && args[2] instanceof int[] p) {
                System.out.println("DEBUG prefix.len=" + p.length);
            }
        }
        return method.invoke(target, args);
    }

    @Test
    void parseInputNormalizesBlankAndRejectsNonObjectAndUnknownField()
            throws Exception {
        Object callback = callback();
        var params = new Class<?>[]{String.class};

        JsonNode blank = (JsonNode) invoke(callback, "parseInput",
                params, new Object[]{null});
        assertEquals(0, blank.size());
        assertEquals("{}", invoke(callback, "parseInput", params, "  ").toString());

        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invoke(callback, "parseInput", params, "[1,2]"));
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invoke(callback, "parseInput", params,
                        "{\"unknown\":1}"));

        JsonNode valid = (JsonNode) invoke(callback, "parseInput",
                params, "{\"city\":\"sf\"}");
        assertEquals("sf", valid.get("city").asText());
    }

    @Test
    void parseInputWrapsMalformedJsonInIllegalArgument() throws Exception {
        Object callback = callback();
        var params = new Class<?>[]{String.class};
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invoke(callback, "parseInput", params, "{broken"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void endpointUriNormalizesSlashesAndEncodesQuery() throws Exception {
        Object callback = callback();
        var params = new Class<?>[]{
                RagChatProperties.HttpEndpointProperties.class, Map.class};

        URI joined = (URI) invoke(callback, "endpointUri", params,
                endpoint("https://weather.example.test", "v1/forecast"),
                Map.of("city", "san francisco"));
        assertEquals("https://weather.example.test/v1/forecast"
                + "?city=san%20francisco", joined.toString());

        URI deduped = (URI) invoke(callback, "endpointUri", params,
                endpoint("https://weather.example.test/", "/v1/forecast"),
                Map.of());
        assertEquals("https://weather.example.test/v1/forecast",
                deduped.toString());

        var fragment = endpoint("https://weather.example.test/base#frag",
                "v1/forecast");
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invoke(callback, "endpointUri", params,
                        fragment, Map.of()));
    }

    @Test
    void hasPrefixGuardsLengthAndByteMismatch() throws Exception {
        Object callback = callback();
        var params = new Class<?>[]{byte[].class, int.class, int[].class};

        // int 前缀数组元素按无符号比较：必须写 0xb8 而非 (byte) 0xb8(-72)。
        assertEquals(Boolean.TRUE, invoke(callback, "hasPrefix", params,
                new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8}, 32,
                new int[]{0x20, 0x01, 0x0d, 0xb8}));
        assertEquals(Boolean.FALSE, invoke(callback, "hasPrefix", params,
                new byte[]{0x20, 0x01}, 32,
                new int[]{0x20, 0x01, 0x0d, (byte) 0xb8}));
        assertEquals(Boolean.FALSE, invoke(callback, "hasPrefix", params,
                new byte[]{0x20, 0x01, 0x00, (byte) 0xb8}, 32,
                new int[]{0x20, 0x01, 0x0d, (byte) 0xb8}));
    }

    @Test
    void validateJsonEnforcesDepthNodesAndArrayLimits() throws Exception {
        Object callback = callback();
        var limitsClass = Class.forName(
                "com.springairag.core.http.AllowlistedHttpToolProvider"
                        + "$JsonLimits");
        Constructor<?> limitsCtor = limitsClass.getDeclaredConstructor();
        limitsCtor.setAccessible(true);
        var params = new Class<?>[]{JsonNode.class, int.class, limitsClass};

        JsonNode deep = MAPPER.readTree("{\"a\":{\"b\":{\"c\":1}}}");
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invoke(callback, "validateJson", params,
                        deep, 0, limitsCtor.newInstance()));

        JsonNode wide = MAPPER.readTree(wideJson(30));
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invoke(callback, "validateJson", params,
                        wide, 0, limitsCtor.newInstance()));

        JsonNode bigArray = MAPPER.readTree("[1,2,3,4,5]");
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invoke(callback, "validateJson", params,
                        bigArray, 0, limitsCtor.newInstance()));

        JsonNode fine = MAPPER.readTree(
                "{\"a\":{\"b\":1},\"arr\":[1,2]}");
        invoke(callback, "validateJson", params,
                fine, 0, limitsCtor.newInstance());
    }

    private String wideJson(int keys) {
        StringBuilder wide = new StringBuilder("{");
        for (int index = 1; index <= keys; index++) {
            if (index > 1) {
                wide.append(',');
            }
            wide.append("\"k").append(index).append("\":1");
        }
        return wide.append('}').toString();
    }

    @Test
    void skillSessionAndStateReturnNullOnMissingContext() throws Exception {
        Object callback = callback();
        var contextType = org.springframework.ai.chat.model.ToolContext.class;

        assertNull(invoke(callback, "skillSession",
                new Class<?>[]{contextType}, new Object[]{null}));
        assertNull(invoke(callback, "state",
                new Class<?>[]{contextType}, new Object[]{null}));

        var emptyContext = new org.springframework.ai.chat.model.ToolContext(
                Map.of());
        assertNull(invoke(callback, "skillSession",
                new Class<?>[]{contextType}, emptyContext));
        assertNull(invoke(callback, "state",
                new Class<?>[]{contextType}, emptyContext));
    }
}
