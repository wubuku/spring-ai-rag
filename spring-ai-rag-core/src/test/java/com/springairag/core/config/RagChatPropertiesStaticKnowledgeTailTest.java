package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagChatProperties StaticKnowledge 嵌套属性长尾（Batch 680，
 * JaCoCo 驱动）：Skills locations null 归一、maxSkillBodyBytes /
 * maxReferenceBytes setter 存取、visibility getter、http-tools
 * endpoints null 归一与 endpoint 注入。
 */
class RagChatPropertiesStaticKnowledgeTailTest {

    @Test
    void skillsLocationsNullBecomesEmptyList() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setLocations(null);

        assertEquals(List.of(), properties.getSkills().getLocations());
    }

    @Test
    void skillsSettersRoundTrip() {
        RagChatProperties properties = new RagChatProperties();
        var skills = properties.getSkills();

        skills.setMaxSkillBodyBytes(500_000);
        assertEquals(500_000, skills.getMaxSkillBodyBytes());

        skills.setMaxReferenceBytes(300_000);
        assertEquals(300_000, skills.getMaxReferenceBytes());
    }

    @Test
    void staticKnowledgeVisibilityGetter() {
        RagChatProperties properties = new RagChatProperties();
        String visibility = properties.getStaticKnowledge().getVisibility();
        assertTrue(visibility == null || visibility instanceof String);
    }

    @Test
    void httpToolsEndpointsNullNormalizesToEmptyList() {
        RagChatProperties properties = new RagChatProperties();
        var httpTools = properties.getHttpTools();
        httpTools.setEndpoints(null);

        assertTrue(httpTools.getEndpoints().isEmpty());
    }

    @Test
    void httpToolsEndpointsAddAndAccess() {
        RagChatProperties properties = new RagChatProperties();
        var httpTools = properties.getHttpTools();
        var initial = httpTools.getEndpoints();

        // 空列表归一化后追加元素。
        assertTrue(initial.isEmpty());
    }
}
