package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        properties.setStaticKnowledge(null);
        assertNotNull(properties.getStaticKnowledge());
        properties.setSkills(null);
        assertNotNull(properties.getSkills());
        properties.setHttpTools(null);
        assertNotNull(properties.getHttpTools());
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
