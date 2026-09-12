package com.springairag.core.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.RagChatToolPolicy;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.skill.RuntimeSkill;
import com.springairag.core.skill.RuntimeSkillCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Provider 级守卫与元数据（Batch 304）：validateAndFreeze 的技能
 * 快照健康检查、工具名唯一性、Skill 能力声明；getToolPolicies 的
 * 只读策略映射；ToolDefinition/ToolMetadata 元数据；传输关闭。
 */
class AllowlistedHttpToolProviderGuardsTest {

    // ── 开关与技能快照 ──────────────────────────────────────────────

    @Test
    void disabledHttpToolsExposeNoCallbacksOrPolicies() {
        RagChatProperties properties = properties();
        properties.getHttpTools().setEnabled(false);
        AllowlistedHttpToolProvider provider = provider(properties, catalog());

        assertTrue(provider.getToolCallbacks().isEmpty());
        assertTrue(provider.getToolPolicies().isEmpty());
    }

    @Test
    void unhealthySkillSnapshotDisablesEndpoints() {
        RagChatProperties properties = properties();
        RuntimeSkillCatalog catalog = mock(RuntimeSkillCatalog.class);
        when(catalog.snapshot()).thenReturn(
                new RuntimeSkillCatalog.Snapshot(1, "d", false, Map.of()));
        AllowlistedHttpToolProvider provider = provider(properties, catalog);

        // 技能目录不健康时端点整体冻结：不暴露任何工具与策略。
        assertTrue(provider.getToolCallbacks().isEmpty());
        assertTrue(provider.getToolPolicies().isEmpty());
    }

    // ── validateAndFreeze 守卫 ──────────────────────────────────────

    @Test
    void duplicateToolNameRejected() {
        RagChatProperties properties = properties();
        RagChatProperties.HttpEndpointProperties endpoint =
                new RagChatProperties.HttpEndpointProperties();
        endpoint.setToolName("getWeather");
        endpoint.setSkillName("weather");
        endpoint.setCapability("weather.read");
        endpoint.setBaseUrl("https://other.example.test");
        endpoint.setPath("/v1/current");
        properties.getHttpTools().getEndpoints().add(endpoint);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> provider(properties, catalog()));
        assertTrue(error.getMessage()
                .contains("Duplicate or blank allowlisted HTTP tool name"));
    }

    @Test
    void blankToolNameRejected() {
        RagChatProperties properties = properties();
        properties.getHttpTools().getEndpoints().getFirst().setToolName(null);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> provider(properties, catalog()));
        assertTrue(error.getMessage()
                .contains("Duplicate or blank allowlisted HTTP tool name"));
    }

    @Test
    void unknownSkillRejected() {
        RagChatProperties properties = properties();
        RuntimeSkillCatalog catalog = catalog();
        when(catalog.find("weather")).thenReturn(null);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> provider(properties, catalog));
        assertTrue(error.getMessage()
                .contains("capability is not declared by its registered Skill"));
    }

    @Test
    void capabilityNotDeclaredBySkillRejected() {
        RagChatProperties properties = properties();
        RuntimeSkillCatalog catalog = catalog();
        when(catalog.find("weather").capabilities())
                .thenReturn(List.of("traffic.read"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> provider(properties, catalog));
        assertTrue(error.getMessage()
                .contains("capability is not declared by its registered Skill"));
    }

    // ── 只读策略映射 ────────────────────────────────────────────────

    @Test
    void toolPoliciesExposeReadOnlyEffectWithEndpointLimits() {
        AllowlistedHttpToolProvider provider = provider(properties(), catalog());

        Map<String, RagChatToolPolicy> policies = provider.getToolPolicies();
        assertEquals(1, policies.size());
        RagChatToolPolicy policy = policies.get("getWeather");
        assertEquals(RagChatToolPolicy.Effect.READ_ONLY, policy.effect());
        assertEquals(2, policy.maxCallsPerRequest());
        assertEquals(4_000, policy.maxResultCharacters());
        assertEquals(Duration.ofMillis(5_000), policy.timeout());
    }

    // ── 工具定义与元数据 ────────────────────────────────────────────

    @Test
    void toolDefinitionDescribesEndpointAndSchema() throws Exception {
        AllowlistedHttpToolProvider provider = provider(properties(), catalog());
        ToolDefinition definition =
                provider.getToolCallbacks().getFirst().getToolDefinition();

        assertEquals("getWeather", definition.name());
        assertTrue(definition.description().contains("weather.read"));
        assertTrue(definition.description().contains("weather"));

        JsonNode schema = new ObjectMapper().readTree(definition.inputSchema());
        assertEquals("object", schema.get("type").asText());
        assertEquals(false, schema.get("additionalProperties").asBoolean());
        assertEquals("string", schema.get("properties").get("city")
                .get("type").asText());
        assertEquals(64, schema.get("properties").get("city")
                .get("maxLength").asInt());
        assertEquals(16, schema.get("properties").get("units")
                .get("maxLength").asInt());
        assertEquals("city", schema.get("required").get(0).asText());
    }

    @Test
    void optionalOnlyDefinitionOmitsRequiredList() throws Exception {
        RagChatProperties properties = properties();
        RagChatProperties.HttpEndpointProperties endpoint =
                properties.getHttpTools().getEndpoints().getFirst();
        endpoint.setQueryParameters(List.of(
                parameter("units", false, 16)));
        AllowlistedHttpToolProvider provider = provider(properties, catalog());
        ToolDefinition definition =
                provider.getToolCallbacks().getFirst().getToolDefinition();

        JsonNode schema = new ObjectMapper().readTree(definition.inputSchema());
        // 全可选参数时不应出现 required 列表。
        assertTrue(schema.get("required") == null
                || schema.get("required").isNull());
    }

    @Test
    void toolMetadataIsNotReturnDirect() {
        AllowlistedHttpToolProvider provider = provider(properties(), catalog());
        ToolMetadata metadata =
                provider.getToolCallbacks().getFirst().getToolMetadata();

        assertFalse(metadata.returnDirect());
    }

    // ── 传输关闭 ────────────────────────────────────────────────────

    @Test
    void closeTransportClosesUnderlyingTransport() {
        AtomicBoolean closed = new AtomicBoolean();
        AllowlistedHttpToolProvider provider = new AllowlistedHttpToolProvider(
                catalog(), properties(), new ObjectMapper(),
                new AllowlistedHttpToolProvider.HttpTransport() {
                    @Override public AllowlistedHttpToolProvider.HttpResponseData execute(
                            java.net.http.HttpRequest request,
                            Duration timeout,
                            int maxResponseBytes,
                            java.net.InetAddress[] validatedAddresses) {
                        throw new IllegalStateException("unused");
                    }

                    @Override public void close() {
                        closed.set(true);
                    }
                },
                host -> new java.net.InetAddress[] {
                        java.net.InetAddress.getByAddress(
                                new byte[] {93, (byte) 184, (byte) 216, 34})});

        provider.closeTransport();
        assertTrue(closed.get());
    }

    @Test
    void closeTransportSwallowsCloseFailure() {
        AllowlistedHttpToolProvider provider = new AllowlistedHttpToolProvider(
                catalog(), properties(), new ObjectMapper(),
                new AllowlistedHttpToolProvider.HttpTransport() {
                    @Override public AllowlistedHttpToolProvider.HttpResponseData execute(
                            java.net.http.HttpRequest request,
                            Duration timeout,
                            int maxResponseBytes,
                            java.net.InetAddress[] validatedAddresses) {
                        throw new IllegalStateException("unused");
                    }

                    @Override public void close() {
                        throw new IllegalStateException("already closed");
                    }
                },
                host -> new java.net.InetAddress[] {
                        java.net.InetAddress.getByAddress(
                                new byte[] {93, (byte) 184, (byte) 216, 34})});

        // 关闭失败不阻断应用停机。
        provider.closeTransport();
    }

    // ── fixture ─────────────────────────────────────────────────────

    private AllowlistedHttpToolProvider provider(
            RagChatProperties properties, RuntimeSkillCatalog catalog) {
        return new AllowlistedHttpToolProvider(
                catalog, properties, new ObjectMapper(),
                (request, timeout, maxBytes, addresses) -> {
                    throw new IllegalStateException("transport unused here");
                },
                host -> new java.net.InetAddress[] {
                        java.net.InetAddress.getByAddress(
                                new byte[] {93, (byte) 184, (byte) 216, 34})});
    }

    private RagChatProperties properties() {
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
        endpoint.setQueryParameters(List.of(
                parameter("city", true, 64),
                parameter("units", false, 16)));
        http.setEndpoints(List.of(endpoint));
        properties.validate();
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

    private RuntimeSkillCatalog catalog() {
        RuntimeSkillCatalog catalog = mock(RuntimeSkillCatalog.class);
        RuntimeSkill skill = mock(RuntimeSkill.class);
        when(skill.capabilities()).thenReturn(List.of("weather.read"));
        when(catalog.find("weather")).thenReturn(skill);
        return catalog;
    }
}
