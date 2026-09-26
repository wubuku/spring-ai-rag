package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagChatProperties 嵌套属性长尾（Batch 657，JaCoCo 驱动）：
 * Skills / StaticKnowledge 的 locations null 归一、http-tools 的
 * max-total-response-bytes 上限、endpoints 缺失与空元素校验。
 */
class RagChatPropertiesNestedTailTest {

    @Test
    void skillsLocationsNullBecomesEmptyList() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setLocations(null);

        assertEquals(List.of(), properties.getSkills().getLocations());
    }

    @Test
    void staticKnowledgeLocationsNullBecomesEmptyList() {
        RagChatProperties properties = new RagChatProperties();
        properties.getStaticKnowledge().setLocations(null);

        assertEquals(List.of(),
                properties.getStaticKnowledge().getLocations());
    }

    @Test
    void httpToolsRejectsOversizedTotalResponseBytes() {
        RagChatProperties properties = new RagChatProperties();
        properties.getHttpTools().setMaxTotalResponseBytes(4_194_305);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void httpToolsNullEndpointsNormalizeToEmptyList() {
        // setter 已将 null 归一为空列表（430-432 的 null 守卫为防御
        // 性分支），空 endpoints 通过校验。
        RagChatProperties properties = new RagChatProperties();
        properties.getHttpTools().setEndpoints(null);

        assertEquals(List.of(), properties.getHttpTools().getEndpoints());
    }

    @Test
    void httpToolsRejectsNullEndpointElement() {
        RagChatProperties properties = new RagChatProperties();
        properties.getHttpTools().setEndpoints(
                new java.util.ArrayList<>(java.util.Collections.nCopies(
                        1, (RagChatProperties.HttpEndpointProperties) null)));

        IllegalStateException error = assertThrows(
                IllegalStateException.class, properties::validate);
        assertTrue(error.getMessage()
                .contains("endpoints must not contain null"));
    }
}
