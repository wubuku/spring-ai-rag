package com.springairag.demo.medical;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 医疗问诊 Prompt 定制器单元测试
 */
class MedicalPromptCustomizerTest {

    private final MedicalPromptCustomizer customizer = new MedicalPromptCustomizer();

    @Test
    @DisplayName("Medical domain user message gets consultation prefix")
    void customizeUserMessage_medicalDomain_addsPrefix() {
        Map<String, Object> metadata = Map.of("domainId", "medical");
        String result = customizer.customizeUserMessage("头疼怎么办", metadata);

        assertTrue(result.contains("[医疗问诊模式]"));
        assertTrue(result.contains("头疼怎么办"));
        assertTrue(result.contains("症状描述"));
    }

    @Test
    @DisplayName("Non-medical domain message remains unchanged")
    void customizeUserMessage_otherDomain_noChange() {
        Map<String, Object> metadata = Map.of("domainId", "legal");
        String original = "合同违约怎么办";
        String result = customizer.customizeUserMessage(original, metadata);

        assertEquals(original, result);
    }

    @Test
    @DisplayName("message unchanged when no domainId")
    void customizeUserMessage_noDomain_noChange() {
        Map<String, Object> metadata = Map.of();
        String original = "普通问题";
        String result = customizer.customizeUserMessage(original, metadata);

        assertEquals(original, result);
    }

    @Test
    @DisplayName("order is 100")
    void order_is100() {
        assertEquals(100, customizer.getOrder());
    }
}
