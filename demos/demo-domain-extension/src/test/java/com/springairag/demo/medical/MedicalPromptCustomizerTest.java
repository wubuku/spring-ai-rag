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
    @DisplayName("医疗领域用户消息添加问诊前缀")
    void customizeUserMessage_medicalDomain_addsPrefix() {
        Map<String, Object> metadata = Map.of("domainId", "medical");
        String result = customizer.customizeUserMessage("头疼怎么办", metadata);

        assertTrue(result.contains("[医疗问诊模式]"));
        assertTrue(result.contains("头疼怎么办"));
        assertTrue(result.contains("症状描述"));
    }

    @Test
    @DisplayName("非医疗领域消息不修改")
    void customizeUserMessage_otherDomain_noChange() {
        Map<String, Object> metadata = Map.of("domainId", "legal");
        String original = "合同违约怎么办";
        String result = customizer.customizeUserMessage(original, metadata);

        assertEquals(original, result);
    }

    @Test
    @DisplayName("无 domainId 时消息不修改")
    void customizeUserMessage_noDomain_noChange() {
        Map<String, Object> metadata = Map.of();
        String original = "普通问题";
        String result = customizer.customizeUserMessage(original, metadata);

        assertEquals(original, result);
    }

    @Test
    @DisplayName("order 为 100")
    void order_is100() {
        assertEquals(100, customizer.getOrder());
    }
}
