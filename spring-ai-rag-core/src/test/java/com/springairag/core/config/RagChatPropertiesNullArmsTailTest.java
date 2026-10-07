package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagChatProperties null 缺省臂长尾（Batch 692，JaCoCo 驱动）：
 * 六个嵌套属性 setter 的 null 回退、技能加载上限往返、端点
 * queryParameters 的 null 清洗。
 *
 * <p><b>Batch 960</b>：六条 null 回退用例原来只断非空。这六个字段都在
 * 声明处就 {@code = new X()} 初始化过，所以"非空"在两种实现下都成立
 * ——setter 真的换上新实例，或者 setter 的 null 臂退化成"什么都不做、
 * 保留原来那个"。后者照样绿，而用例名字承诺的是
 * {@code FallsBackToFreshInstance}。现在改为先抓住原实例、置 null 后
 * 断它没有被原样留下。
 *
 * <p>勿再投入：HttpToolProperties.setEndpoints(null) 与
 * HttpEndpointProperties.setQueryParameters(null) 均在 setter 内
 * 归一化为空列表，validate 中的 endpoints/queryParameters null 检
 * 查（431/589 行）经公共 API 不可达。
 */
class RagChatPropertiesNullArmsTailTest {

    @Test
    void nullKnowledgeSetterFallsBackToFreshInstance() {
        RagChatProperties properties = new RagChatProperties();
        var before = properties.getKnowledge();

        properties.setKnowledge(null);

        assertNotNull(properties.getKnowledge());
        assertNotSame(before, properties.getKnowledge(),
                "setKnowledge(null) 必须换上一个新实例，而不是保留原来那个");
    }

    @Test
    void nullAgentSetterFallsBackToFreshInstance() {
        RagChatProperties properties = new RagChatProperties();
        var before = properties.getAgent();

        properties.setAgent(null);

        assertNotNull(properties.getAgent());
        assertNotSame(before, properties.getAgent(),
                "setAgent(null) 必须换上一个新实例");
    }

    @Test
    void nullHistorySetterFallsBackToFreshInstance() {
        RagChatProperties properties = new RagChatProperties();
        var before = properties.getHistory();

        properties.setHistory(null);

        assertNotNull(properties.getHistory());
        assertNotSame(before, properties.getHistory(),
                "setHistory(null) 必须换上一个新实例");
    }

    @Test
    void nullExecutionSetterFallsBackToFreshInstance() {
        RagChatProperties properties = new RagChatProperties();
        var before = properties.getExecution();

        properties.setExecution(null);

        assertNotNull(properties.getExecution());
        assertNotSame(before, properties.getExecution(),
                "setExecution(null) 必须换上一个新实例");
    }

    @Test
    void nullContextSetterFallsBackToFreshInstance() {
        RagChatProperties properties = new RagChatProperties();
        var before = properties.getContext();

        properties.setContext(null);

        assertNotNull(properties.getContext());
        assertNotSame(before, properties.getContext(),
                "setContext(null) 必须换上一个新实例");
    }

    @Test
    void nullIdempotencySetterFallsBackToFreshInstance() {
        RagChatProperties properties = new RagChatProperties();
        var before = properties.getIdempotency();

        properties.setIdempotency(null);

        assertNotNull(properties.getIdempotency());
        assertNotSame(before, properties.getIdempotency(),
                "setIdempotency(null) 必须换上一个新实例");
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
