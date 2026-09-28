package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagChatProperties null 缺省臂长尾（Batch 692，JaCoCo 驱动）：
 * 六个嵌套属性 setter 的 null 回退、技能加载上限往返、端点
 * queryParameters 的 null 清洗。
 *
 * 勿再投入：HttpToolProperties.setEndpoints(null) 与
 * HttpEndpointProperties.setQueryParameters(null) 均在 setter 内
 * 归一化为空列表，validate 中的 endpoints/queryParameters null 检
 * 查（431/589 行）经公共 API 不可达。
 */
class RagChatPropertiesNullArmsTailTest {

    @Test
    void nullKnowledgeSetterFallsBackToFreshInstance() {
        RagChatProperties properties = new RagChatProperties();
        properties.setKnowledge(null);
        assertNotNull(properties.getKnowledge());
    }

    @Test
    void nullAgentSetterFallsBackToFreshInstance() {
        RagChatProperties properties = new RagChatProperties();
        properties.setAgent(null);
        assertNotNull(properties.getAgent());
    }

    @Test
    void nullHistorySetterFallsBackToFreshInstance() {
        RagChatProperties properties = new RagChatProperties();
        properties.setHistory(null);
        assertNotNull(properties.getHistory());
    }

    @Test
    void nullExecutionSetterFallsBackToFreshInstance() {
        RagChatProperties properties = new RagChatProperties();
        properties.setExecution(null);
        assertNotNull(properties.getExecution());
    }

    @Test
    void nullContextSetterFallsBackToFreshInstance() {
        RagChatProperties properties = new RagChatProperties();
        properties.setContext(null);
        assertNotNull(properties.getContext());
    }

    @Test
    void nullIdempotencySetterFallsBackToFreshInstance() {
        RagChatProperties properties = new RagChatProperties();
        properties.setIdempotency(null);
        assertNotNull(properties.getIdempotency());
    }

    @Test
    void skillLoadLimitsRoundTrip() {
        RagChatProperties.SkillProperties skills =
                new RagChatProperties.SkillProperties();
        skills.setMaxLoadsPerRequest(9);
        skills.setMaxReferenceReadsPerRequest(11);
        skills.setMaxCatalogCharacters(4096);

        assertEquals(9, skills.getMaxLoadsPerRequest());
        assertEquals(11, skills.getMaxReferenceReadsPerRequest());
        assertEquals(4096, skills.getMaxCatalogCharacters());
    }

    @Test
    void endpointNullQueryParametersNormalizeToEmptyList() {
        RagChatProperties.HttpEndpointProperties endpoint =
                new RagChatProperties.HttpEndpointProperties();
        endpoint.setQueryParameters(null);

        assertNotNull(endpoint.getQueryParameters());
        assertTrue(endpoint.getQueryParameters().isEmpty());
    }

    @Test
    void validateAcceptsDefaultsAfterNullNormalizedSetters() {
        RagChatProperties properties = new RagChatProperties();
        // 默认配置下 validate 全链路通过（嵌套 setter 的 null 回退
        // 实例满足各段校验）。
        properties.validate();
    }
}
