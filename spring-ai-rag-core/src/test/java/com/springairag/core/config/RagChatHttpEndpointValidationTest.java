package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RagChatProperties$HttpEndpointProperties.validate 的配置校验矩阵
 * （Batch 308）：标识符正则、https 源约束、路径安全、方法白名单、
 * 数值上下限、查询参数与内容类型、凭证环境变量与头部。
 */
class RagChatHttpEndpointValidationTest {

    private static final String PREFIX = "rag.chat.http-tools endpoint";

    // ── 合法配置基线 ────────────────────────────────────────────────

    @Test
    void validEndpointPassesValidation() {
        assertDoesNotThrow(() -> validProperties().validate());
    }

    @Test
    void headMethodAndAbsentCredentialAreAccepted() {
        assertDoesNotThrow(() -> valid(endpoint -> {
            endpoint.setMethod("HEAD");
            endpoint.setCredentialEnv(null);
        }).validate());
    }

    // ── 标识符正则 ──────────────────────────────────────────────────

    @Test
    void toolNameRejectsBlankAndIllegalShapes() {
        assertInvalid("tool-name is invalid", e -> e.setToolName(null));
        assertInvalid("tool-name is invalid", e -> e.setToolName(""));
        assertInvalid("tool-name is invalid", e -> e.setToolName("1abc"));
        assertInvalid("tool-name is invalid", e -> e.setToolName("a b"));
    }

    @Test
    void capabilityRejectsBlankAndIllegalShapes() {
        assertInvalid("capability is invalid", e -> e.setCapability(""));
        assertInvalid("capability is invalid", e -> e.setCapability("ABC"));
        assertInvalid("capability is invalid", e -> e.setCapability("a..b"));
        assertInvalid("capability is invalid", e -> e.setCapability("-a"));
    }

    @Test
    void skillNameRejectsBlankAndIllegalShapes() {
        assertInvalid("skill-name is invalid", e -> e.setSkillName(""));
        assertInvalid("skill-name is invalid", e -> e.setSkillName("a_b"));
        assertInvalid("skill-name is invalid", e -> e.setSkillName("A-b"));
        assertInvalid("skill-name is invalid", e -> e.setSkillName("-a"));
    }

    // ── base-url https 源约束 ───────────────────────────────────────

    @Test
    void baseUrlIsRequired() {
        assertInvalid("base-url is required", e -> e.setBaseUrl("   "));
    }

    @Test
    void baseUrlMustBeHttpsOrigin() {
        assertInvalid("must be an https origin",
                e -> e.setBaseUrl("http://weather.example.test"));
        assertInvalid("must be an https origin",
                e -> e.setBaseUrl("ftp://weather.example.test"));
        assertInvalid("must be an https origin",
                e -> e.setBaseUrl("https://weather.example.test/v1"));
        assertInvalid("must be an https origin",
                e -> e.setBaseUrl("https://user@weather.example.test"));
        assertInvalid("must be an https origin",
                e -> e.setBaseUrl("https://weather.example.test/?a=b"));
        assertInvalid("must be an https origin",
                e -> e.setBaseUrl("https://weather.example.test/#frag"));
    }

    @Test
    void malformedBaseUrlReportsInvalidUri() {
        assertInvalid("base-url is invalid",
                e -> e.setBaseUrl("https://weather example.test"));
    }

    // ── path 安全 ───────────────────────────────────────────────────

    @Test
    void pathRejectsUnsafeShapes() {
        assertInvalid("path is unsafe", e -> e.setPath(""));
        assertInvalid("path is unsafe", e -> e.setPath("v1/forecast"));
        assertInvalid("path is unsafe", e -> e.setPath("/a\\b"));
        assertInvalid("path is unsafe", e -> e.setPath("/a/../b"));
        assertInvalid("path is unsafe", e -> e.setPath("/a%20b"));
        assertInvalid("path is unsafe", e -> e.setPath("/a#b"));
        assertInvalid("path is unsafe", e -> e.setPath("/a?b"));
        assertInvalid("path is unsafe", e -> e.setPath("/a\0b"));
        assertInvalid("path is unsafe", e -> e.setPath("/a\nb"));
    }

    // ── 方法白名单与数值上限 ────────────────────────────────────────

    @Test
    void methodRestrictedToGetAndHead() {
        assertInvalid("method must be GET or HEAD", e -> e.setMethod("POST"));
        assertInvalid("method must be GET or HEAD", e -> e.setMethod("DELETE"));
    }

    @Test
    void numericLimitsMustBePositive() {
        assertInvalid("max-calls-per-request", e -> e.setMaxCallsPerRequest(0));
        assertInvalid("timeout-ms", e -> e.setTimeoutMs(0));
        assertInvalid("max-response-bytes", e -> e.setMaxResponseBytes(-1));
        assertInvalid("max-result-characters", e -> e.setMaxResultCharacters(0));
        assertInvalid("max-json-depth", e -> e.setMaxJsonDepth(0));
        assertInvalid("max-json-nodes", e -> e.setMaxJsonNodes(0));
        assertInvalid("max-json-array-items", e -> e.setMaxJsonArrayItems(0));
    }

    @Test
    void limitsEnforceSupportedRanges() {
        assertInvalid("outside their supported range",
                e -> e.setTimeoutMs(30_001));
        assertInvalid("outside their supported range",
                e -> e.setMaxResponseBytes(4_194_305));
        // 端点响应上限不得超过请求级总预算。
        assertInvalid("outside their supported range",
                e -> e.setMaxResponseBytes(4_096));
        assertInvalid("outside their supported range",
                e -> e.setMaxResultCharacters(1_023));
        assertInvalid("outside their supported range",
                e -> e.setMaxResultCharacters(2_000_001));
    }

    // ── 查询参数 ────────────────────────────────────────────────────

    @Test
    void queryParameterRejectsNullElementBadNameAndDuplicates() {
        assertInvalid("query parameter is invalid",
                e -> e.setQueryParameters(Arrays.asList(
                        parameter("city", true, 64), null)));
        assertInvalid("query parameter is invalid",
                e -> e.setQueryParameters(List.of(parameter("", true, 64))));
        assertInvalid("query parameter is invalid",
                e -> e.setQueryParameters(List.of(parameter("1a", true, 64))));
        assertInvalid("query parameter is invalid",
                e -> e.setQueryParameters(List.of(
                        parameter("city", true, 64),
                        parameter("city", false, 16))));
        // 参数级上限单独报错。
        assertInvalid("must not exceed 4096",
                e -> e.setQueryParameters(List.of(
                        parameter("city", true, 4_097))));
        assertInvalid("query-parameter max-length",
                e -> e.setQueryParameters(List.of(
                        parameter("city", true, 0))));
    }

    // ── 响应内容类型 ────────────────────────────────────────────────

    @Test
    void responseContentTypesMustBeConcrete() {
        assertInvalid("response-content-types are invalid",
                e -> e.setResponseContentTypes(new ArrayList<>()));
        assertInvalid("response-content-types are invalid",
                e -> e.setResponseContentTypes(null));
        assertInvalid("response-content-types are invalid",
                e -> e.setResponseContentTypes(List.of("application/json", " ")));
        assertInvalid("response-content-types are invalid",
                e -> e.setResponseContentTypes(
                        List.of("application/json; charset=utf-8")));
        assertInvalid("response-content-types are invalid",
                e -> e.setResponseContentTypes(List.of("*/*")));
    }

    // ── 凭证环境变量与头部 ──────────────────────────────────────────

    @Test
    void credentialEnvShapeIsEnforced() {
        assertInvalid("credential-env is invalid",
                e -> e.setCredentialEnv("BAD-NAME"));
        assertInvalid("credential-env is invalid",
                e -> e.setCredentialEnv("1A"));
    }

    @Test
    void credentialHeaderShapeIsEnforced() {
        assertInvalid("credential-header is invalid",
                e -> e.setCredentialHeader(null));
        assertInvalid("credential-header is invalid",
                e -> e.setCredentialHeader("  "));
        assertInvalid("credential-header is invalid",
                e -> e.setCredentialHeader("Bad Header"));
    }

    // ── fixture ─────────────────────────────────────────────────────

    private RagChatProperties validProperties() {
        RagChatProperties properties = new RagChatProperties();
        RagChatProperties.HttpToolProperties http = properties.getHttpTools();
        http.setEnabled(true);
        http.setMaxTotalResponseBytes(128);
        http.setEndpoints(List.of(validEndpoint()));
        properties.validate();
        return properties;
    }

    private RagChatProperties.HttpEndpointProperties validEndpoint() {
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
        return endpoint;
    }

    private RagChatProperties valid(
            Consumer<RagChatProperties.HttpEndpointProperties> tweak) {
        RagChatProperties properties = new RagChatProperties();
        RagChatProperties.HttpToolProperties http = properties.getHttpTools();
        http.setEnabled(true);
        http.setMaxTotalResponseBytes(128);
        RagChatProperties.HttpEndpointProperties endpoint = validEndpoint();
        tweak.accept(endpoint);
        http.setEndpoints(List.of(endpoint));
        return properties;
    }

    private RagChatProperties.HttpQueryParameterProperties parameter(
            String name, boolean required, int maxLength) {
        RagChatProperties.HttpQueryParameterProperties parameter =
                new RagChatProperties.HttpQueryParameterProperties();
        parameter.setName(name);
        parameter.setRequired(required);
        parameter.setMaxLength(maxLength);
        return parameter;
    }

    private void assertInvalid(
            String messageFragment,
            Consumer<RagChatProperties.HttpEndpointProperties> tweak) {
        RagChatProperties properties = valid(tweak);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                properties::validate);

        assertTrue(error.getMessage().contains(PREFIX + " " + messageFragment)
                        || error.getMessage().contains(messageFragment),
                "异常消息应包含 " + messageFragment + "，实际为 "
                        + error.getMessage());
    }
}
