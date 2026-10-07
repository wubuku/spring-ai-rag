package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagChatProperties 配置 setter 与校验长尾（Batch 560，JaCoCo 驱
 * 动）：defaultMode 读写、null 子配置回退新实例、validate 对执行
 * 与代理参数非正值拒绝。
 */
class RagChatPropertiesSetterTailTest {

    @Test
    void defaultModeRoundTrips() {
        RagChatProperties properties = new RagChatProperties();
        properties.setDefaultMode("agent");

        assertEquals("agent", properties.getDefaultMode());
    }

    @Test
    void nullNestedPropertiesFallBackToFreshInstances() {
        RagChatProperties properties = new RagChatProperties();

        // 这六个（连同 RagChatPropertiesNullArmsTailTest 的同类用例）
        // 字段都在声明处就 `= new X()` 初始化过。原来只断非空，而
        // "非空"在两处都会成立：setter 真回退成新实例，或者 setter 的
        // null 臂退化成"什么都不做、保留声明期那个实例"——后者照样绿。
        //
        // 用例名字承诺的是 Falls Back To **Fresh** Instances，
        // 所以先把声明期那个实例抓在手里，置 null 后断它**没有**被原样留下。
        var beforeStaticKnowledge = properties.getStaticKnowledge();
        var beforeSkills = properties.getSkills();
        var beforeHttpTools = properties.getHttpTools();

        properties.setStaticKnowledge(null);
        properties.setSkills(null);
        properties.setHttpTools(null);

        assertNotNull(properties.getStaticKnowledge());
        assertNotNull(properties.getSkills());
        assertNotNull(properties.getHttpTools());
        assertNotSame(beforeStaticKnowledge, properties.getStaticKnowledge(),
                "setStaticKnowledge(null) 必须换上一个新实例，而不是留下原来那个");
        assertNotSame(beforeSkills, properties.getSkills(),
                "setSkills(null) 必须换上一个新实例");
        assertNotSame(beforeHttpTools, properties.getHttpTools(),
                "setHttpTools(null) 必须换上一个新实例");
    }

    @Test
    void settersKeepProvidedInstances() {
        var staticKnowledge = new RagChatProperties.StaticKnowledgeProperties();
        var skills = new RagChatProperties.SkillProperties();
        var httpTools = new RagChatProperties.HttpToolProperties();

        RagChatProperties properties = new RagChatProperties();
        properties.setStaticKnowledge(staticKnowledge);
        properties.setSkills(skills);
        properties.setHttpTools(httpTools);

        assertSame(staticKnowledge, properties.getStaticKnowledge());
        assertSame(skills, properties.getSkills());
        assertSame(httpTools, properties.getHttpTools());
    }

    @Test
    void validateRejectsNonPositiveExecutionLimits() {
        RagChatProperties properties = new RagChatProperties();
        properties.getExecution().setMaxCandidateAttempts(0);

        var error = assertThrows(Exception.class, properties::validate);
        assertTrue(error.getMessage().contains("max-candidate-attempts"));
    }

    @Test
    void validateRejectsNonPositiveAgentToolRounds() {
        RagChatProperties properties = new RagChatProperties();
        properties.getAgent().setMaxToolRounds(0);

        var error = assertThrows(Exception.class, properties::validate);
        assertTrue(error.getMessage().contains("max-tool-rounds"));
    }

    @Test
    void validateAcceptsPositiveDefaults() {
        RagChatProperties properties = new RagChatProperties();

        properties.validate();
    }
}
