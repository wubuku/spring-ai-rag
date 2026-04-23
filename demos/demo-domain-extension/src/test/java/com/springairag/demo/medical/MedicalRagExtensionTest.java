package com.springairag.demo.medical;

import com.springairag.api.dto.RetrievalConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 医疗问诊领域扩展单元测试
 */
class MedicalRagExtensionTest {

    private final MedicalRagExtension extension = new MedicalRagExtension();

    @Test
    @DisplayName("domainId is medical")
    void domainId_isMedical() {
        assertEquals("medical", extension.getDomainId());
    }

    @Test
    @DisplayName("domainName is medical consultation")
    void domainName_isMedicalConsultation() {
        assertEquals("医疗问诊", extension.getDomainName());
    }

    @Test
    @DisplayName("system prompt contains {context} placeholder")
    void systemPrompt_containsContextPlaceholder() {
        String prompt = extension.getSystemPromptTemplate();
        assertTrue(prompt.contains("{context}"), "提示词必须包含 {context} 占位符");
    }

    @Test
    @DisplayName("System prompt contains safety rules")
    void systemPrompt_containsSafetyRules() {
        String prompt = extension.getSystemPromptTemplate();
        assertTrue(prompt.contains("120"), "必须包含急救电话提醒");
        assertTrue(prompt.contains("处方") || prompt.contains("用药"), "必须包含用药安全提醒");
    }

    @Test
    @DisplayName("Retrieval config uses high-recall mode")
    void retrievalConfig_usesHighRecall() {
        RetrievalConfig config = extension.getRetrievalConfig();
        assertTrue(config.getMaxResults() >= 12, "医疗领域应返回更多结果");
        assertTrue(config.getMinScore() < 0.5, "医疗领域应降低阈值确保召回");
        assertTrue(config.isUseHybridSearch());
        assertTrue(config.isUseRerank());
    }

    @Test
    @DisplayName("isApplicable - recognizes medical keywords")
    void isApplicable_detectsMedicalKeywords() {
        assertTrue(extension.isApplicable("头疼怎么办"));
        assertTrue(extension.isApplicable("发烧38度"));
        assertTrue(extension.isApplicable("咳嗽有痰"));
        assertTrue(extension.isApplicable("过敏症状"));
        assertTrue(extension.isApplicable("需要手术吗"));
    }

    @Test
    @DisplayName("isApplicable - filters non-medical questions")
    void isApplicable_rejectsNonMedical() {
        assertFalse(extension.isApplicable("今天天气怎么样"));
        assertFalse(extension.isApplicable("Python怎么学"));
        assertFalse(extension.isApplicable("推荐几本书"));
    }

    @Test
    @DisplayName("postProcessAnswer - adds medical disclaimer")
    void postProcessAnswer_addsDisclaimer() {
        String answer = "头疼可能由多种原因引起...";
        String processed = extension.postProcessAnswer(answer);
        assertTrue(processed.contains("就医"), "Should add medical disclaimer");
    }

    @Test
    @DisplayName("postProcessAnswer - does not add duplicate disclaimer")
    void postProcessAnswer_noDuplicateDisclaimer() {
        String answer = "建议及时就医检查";
        String processed = extension.postProcessAnswer(answer);
        assertEquals(answer, processed, "Should not add duplicate disclaimer when already present");
    }
}
