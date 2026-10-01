package com.springairag.core.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.skill.RuntimeSkill;
import com.springairag.core.skill.RuntimeSkillCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 装配与输入路径的补集分支（Batch 762）。
 *
 * <p>同包其余 8 个测试类已覆盖各条错误路径的<strong>典型触发方式</strong>，但把若干
 * 组合固定死了，于是紧邻的组合从未被走过。本类只补那几处真实空白，并刻意不重复
 * 已有覆盖——重复的测试会让回归信号变钝，却不增加任何防护。
 *
 * <p>刻意不用 {@code @Nested}：Surefire 的 {@code -Dtest=} 匹配不到嵌套类
 * （{@code XxxTest$Nested}），会静默报 "Tests run: 0" 而一个用例都没执行。
 */
class AllowlistedHttpToolProviderAssemblyBranchTest {

    // ==================== ::a.b.c.d 旧式内嵌形式 ====================

    /**
     * 构造一个 IPv4 内嵌地址：前 10 字节全零，第 10/11 字节为给定形式，
     * 后 4 字节是 IPv4 地址。
     */
    private static byte[] embedded(byte[] marker, int a, int b, int c, int d) {
        byte[] bytes = new byte[16];
        bytes[10] = marker[0];
        bytes[11] = marker[1];
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        return bytes;
    }

    private static boolean classify(Object callback, Method publicAddress,
                                     byte[] bytes) throws Exception {
        return (boolean) publicAddress.invoke(
                callback, InetAddress.getByAddress(bytes));
    }

    @Test
    @DisplayName("::a.b.c.d（0x0000 标记）同样递归进 IPv4 规则")
    void ipv4CompatibleMarkerRecurses() throws Exception {
        // 既有测试只覆盖了 ::ffff:a.b.c.d（0xffff 标记）。0x0000 标记是老式
        // IPv4-compatible 地址，走的是内嵌判定的另一半分支，两者期望必须一致，
        // 否则同一条 SSRF 规则会因为写法不同给出不同答案。
        Object callback = callbackFor();
        Method publicAddress = publicAddressMethod();
        byte[] zero = {0x00, 0x00};
        byte[] mapped = {(byte) 0xff, (byte) 0xff};

        assertFalse(classify(callback, publicAddress,
                embedded(zero, 10, 0, 0, 1)), "::10.0.0.1 必须递归命中 10/8");
        assertFalse(classify(callback, publicAddress,
                embedded(zero, 127, 0, 0, 1)), "::127.0.0.1 必须递归命中回环");
        assertTrue(classify(callback, publicAddress,
                embedded(zero, 8, 8, 8, 8)), "::8.8.8.8 必须递归命中公网");

        // 两种标记对同一 IPv4 地址必须给出相同判定。
        for (int[] v4 : new int[][] {
                {10, 0, 0, 1}, {172, 16, 0, 1}, {192, 168, 0, 1},
                {100, 64, 0, 1}, {8, 8, 8, 8}, {172, 15, 0, 1}}) {
            assertEquals(
                    classify(callback, publicAddress,
                            embedded(mapped, v4[0], v4[1], v4[2], v4[3])),
                    classify(callback, publicAddress,
                            embedded(zero, v4[0], v4[1], v4[2], v4[3])),
                    () -> "::ffff: 与 :: 两种内嵌标记对 " + Arrays.toString(v4)
                            + " 的判定必须一致");
        }
    }

    @Test
    @DisplayName("既非 0x0000 也非 0xffff 的标记不触发递归")
    void otherMarkerDoesNotRecurse() throws Exception {
        Object callback = callbackFor();
        Method publicAddress = publicAddressMethod();
        // 第 10/11 字节是 0x0100：前 10 字节虽全零，但不构成任何一种内嵌标记，
        // 于是按普通 IPv6 全局单播判定——首字节全零落在 2000::/3 之外。
        assertFalse(classify(callback, publicAddress,
                embedded(new byte[] {0x01, 0x00}, 8, 8, 8, 8)));
    }

    // ==================== 多查询参数的分隔符 ====================

    @Test
    @DisplayName("两个及以上查询参数用 & 连接，且只出现一个 ?")
    void multipleQueryParametersUseSingleQuestionMark() throws Exception {
        // 既有测试只传了一个查询参数，& 分隔分支从未被走过。
        Map<String, String> query = new LinkedHashMap<>();
        query.put("city", "san francisco");
        query.put("units", "metric");

        String uri = endpointUri(
                "https://weather.example.test", "v1/forecast", query);

        assertEquals(1, uri.chars().filter(c -> c == '?').count(),
                () -> "只允许一个 ?，实际：" + uri);
        assertEquals(1, uri.chars().filter(c -> c == '&').count(),
                () -> "两个参数之间应恰好一个 &，实际：" + uri);
        assertTrue(uri.contains("city=san%20francisco"), uri);
        assertTrue(uri.contains("units=metric"), uri);
    }

    @Test
    @DisplayName("三个查询参数产生两个 &")
    void threeQueryParametersUseTwoAmpersands() throws Exception {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("city", "shanghai");
        query.put("units", "metric");
        query.put("lang", "zh");

        String uri = endpointUri(
                "https://weather.example.test", "v1/forecast", query);

        assertEquals(2, uri.chars().filter(c -> c == '&').count(),
                () -> "三个参数应产生两个 &，实际：" + uri);
        assertEquals(1, uri.chars().filter(c -> c == '?').count(), uri);
    }

    // ==================== provider 装载的补集 ====================

    @Test
    @DisplayName("Skill 目录快照为 null 时不冻结端点")
    void nullSkillSnapshotDoesNotFreezeEndpoints() {
        // 目录尚未初始化（快照为 null）与"快照不健康"是两种不同的状态：
        // 后者冻结全部端点，前者应当照常装载。既有测试只覆盖了不健康那一支。
        RagChatProperties properties = baseProperties();
        AllowlistedHttpToolProvider provider = new AllowlistedHttpToolProvider(
                catalog(null), properties, new ObjectMapper());

        assertFalse(provider.getToolCallbacks().isEmpty(),
                "快照为 null 只是一时没有数据，不该把端点全部冻结");
    }

    @Test
    @DisplayName("端点列表含 null 元素时拒绝装载")
    void nullEndpointElementIsRejected() {
        RagChatProperties properties = baseProperties();
        properties.getHttpTools().setEndpoints(Arrays.asList(
                properties.getHttpTools().getEndpoints().getFirst(), null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AllowlistedHttpToolProvider(
                        catalog(), properties, new ObjectMapper())
                        .getToolCallbacks());
        assertTrue(error.getMessage().contains("Duplicate or blank"),
                error.getMessage());
    }

    @Test
    @DisplayName("toolName 为纯空白时拒绝装载")
    void whitespaceToolNameIsRejected() {
        // 与 null/重复不同，空白名走的是 isBlank() 那一支。
        RagChatProperties properties = baseProperties();
        properties.getHttpTools().getEndpoints().getFirst().setToolName("   ");

        assertThrows(IllegalStateException.class,
                () -> new AllowlistedHttpToolProvider(
                        catalog(), properties, new ObjectMapper())
                        .getToolCallbacks());
    }

    @Test
    @DisplayName("skillName 指向未注册技能时拒绝装载")
    void unknownSkillNameIsRejected() {
        RuntimeSkillCatalog catalog = mock(RuntimeSkillCatalog.class);
        when(catalog.snapshot())
                .thenReturn(new RuntimeSkillCatalog.Snapshot(1, "d", true, Map.of()));
        when(catalog.find("not-registered")).thenReturn(null);
        RagChatProperties properties = baseProperties();
        properties.getHttpTools().getEndpoints().getFirst()
                .setSkillName("not-registered");

        assertThrows(IllegalStateException.class,
                () -> new AllowlistedHttpToolProvider(
                        catalog, properties, new ObjectMapper())
                        .getToolCallbacks());
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static Class<?> callbackClass() throws ClassNotFoundException {
        return Class.forName(
                "com.springairag.core.http.AllowlistedHttpToolProvider$EndpointCallback");
    }

    private static Method publicAddressMethod() throws Exception {
        Method method = callbackClass().getDeclaredMethod(
                "publicAddress", InetAddress.class);
        method.setAccessible(true);
        return method;
    }

    private static Object callbackFor() throws Exception {
        RagChatProperties properties = baseProperties();
        Constructor<?> constructor = callbackClass().getDeclaredConstructor(
                AllowlistedHttpToolProvider.class,
                RagChatProperties.HttpEndpointProperties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                provider(properties), properties.getHttpTools().getEndpoints().getFirst());
    }

    private static String endpointUri(
            String baseUrl, String path, Map<String, String> query)
            throws Exception {
        RagChatProperties properties = baseProperties();
        properties.getHttpTools().getEndpoints().getFirst().setBaseUrl(baseUrl);
        properties.getHttpTools().getEndpoints().getFirst().setPath(path);
        Constructor<?> constructor = callbackClass().getDeclaredConstructor(
                AllowlistedHttpToolProvider.class,
                RagChatProperties.HttpEndpointProperties.class);
        constructor.setAccessible(true);
        Object callback = constructor.newInstance(
                provider(properties),
                properties.getHttpTools().getEndpoints().getFirst());
        Method method = callbackClass().getDeclaredMethod(
                "endpointUri", RagChatProperties.HttpEndpointProperties.class, Map.class);
        method.setAccessible(true);
        return method.invoke(
                callback,
                properties.getHttpTools().getEndpoints().getFirst(),
                query).toString();
    }

    private static AllowlistedHttpToolProvider provider(
            RagChatProperties properties) {
        return new AllowlistedHttpToolProvider(
                catalog(), properties, new ObjectMapper(),
                (request, timeout, maxBytes, addresses) ->
                        new AllowlistedHttpToolProvider.HttpResponseData(
                                200, "application/json",
                                "{}".getBytes(StandardCharsets.UTF_8)),
                host -> new InetAddress[] {
                        InetAddress.getByAddress(host, new byte[] {
                                93, (byte) 184, (byte) 216, 34})});
    }

    private static RagChatProperties baseProperties() {
        RagChatProperties properties = new RagChatProperties();
        RagChatProperties.HttpToolProperties http = properties.getHttpTools();
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
        endpoint.setQueryParameters(List.of(parameter("city", true, 64)));
        http.setEndpoints(List.of(endpoint));
        properties.validate();
        return properties;
    }

    private static RagChatProperties.HttpQueryParameterProperties parameter(
            String name, boolean required, int maxLength) {
        RagChatProperties.HttpQueryParameterProperties parameter =
                new RagChatProperties.HttpQueryParameterProperties();
        parameter.setName(name);
        parameter.setRequired(required);
        parameter.setMaxLength(maxLength);
        return parameter;
    }

    private static RuntimeSkillCatalog catalog() {
        return catalog(new RuntimeSkillCatalog.Snapshot(1, "d", true, Map.of()));
    }

    /**
     * @param snapshot 目录快照；传 {@code null} 表示目录尚未初始化
     */
    private static RuntimeSkillCatalog catalog(RuntimeSkillCatalog.Snapshot snapshot) {
        RuntimeSkillCatalog catalog = mock(RuntimeSkillCatalog.class);
        RuntimeSkill skill = mock(RuntimeSkill.class);
        when(skill.capabilities()).thenReturn(List.of("weather.read"));
        when(catalog.snapshot()).thenReturn(snapshot);
        when(catalog.find("weather")).thenReturn(skill);
        return catalog;
    }
}
