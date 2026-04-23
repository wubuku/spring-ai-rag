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
    @DisplayName("domainId 为 medical")
    void domainId_isMedical() {
        assertEquals("medical", extension.getDomainId());
    }

    @Test
    @DisplayName("domainName 为医疗问诊")
    void domainName_isMedicalConsultation() {
        assertEquals("医疗问诊", extension.getDomainName());
    }

    @Test
    @DisplayName("系统提示词包含 {context} 占位符")
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
    @DisplayName("isApplicable 识别医疗关键词")
    void isApplicable_detectsMedicalKeywords() {
        assertTrue(extension.isApplicable("头疼怎么办"));
        assertTrue(extension.isApplicable("发烧38度"));
        assertTrue(extension.isApplicable("咳嗽有痰"));
        assertTrue(extension.isApplicable("过敏症状"));
        assertTrue(extension.isApplicable("需要手术吗"));
    }

    @Test
    @DisplayName("isApplicable 过滤非医疗问题")
    void isApplicable_rejectsNonMedical() {
        assertFalse(extension.isApplicable("今天天气怎么样"));
        assertFalse(extension.isApplicable("Python怎么学"));
        assertFalse(extension.isApplicable("推荐几本书"));
    }

    @Test
    @DisplayName("postProcessAnswer 添加免责声明")
    void postProcessAnswer_addsDisclaimer() {
        String answer = "头疼可能由多种原因引起...";
        String processed = extension.postProcessAnswer(answer);
        assertTrue(processed.contains("就医"), "应添加就医提醒");
    }

    @Test
    @DisplayName("postProcessAnswer 已有就医提醒时不重复添加")
    void postProcessAnswer_noDuplicateDisclaimer() {
        String answer = "建议及时就医检查";
        String processed = extension.postProcessAnswer(answer);
        assertEquals(answer, processed, "已有就医提醒则不重复添加");
    }
}
