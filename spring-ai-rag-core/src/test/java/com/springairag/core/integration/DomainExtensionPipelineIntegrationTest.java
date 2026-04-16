package com.springairag.core.integration;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.service.DomainRagExtension;
import com.springairag.core.extension.DefaultDomainRagExtension;
import com.springairag.core.extension.DomainExtensionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Domain Extension Pipeline Integration Tests
 *
 * <p>Verifies core domain extension behavior, including DomainExtensionRegistry and DefaultDomainRagExtension:
 * <ol>
 *   <li>DomainExtensionRegistry correctly registers and looks up extensions</li>
 *   <li>DefaultDomainRagExtension serves as default when no domainId is provided</li>
 *   <li>Each extension isApplicable() correctly filters queries</li>
 *   <li>PromptCustomizer chain executes in order</li>
 *   <li>Simulates medical domain extension behavior verification (consistent with MedicalRagExtension behavior)</li>
 * </ol>
 */
class DomainExtensionPipelineIntegrationTest {

    // ─────────────────────────────────────────────
    // 1. DefaultDomainRagExtension behavior verification
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("system prompt contains {context} placeholder")
    void defaultExtension_promptContainsContext() {
        DefaultDomainRagExtension def = new DefaultDomainRagExtension();
        assertTrue(def.getSystemPromptTemplate().contains("{context}"),
                "默认提示词应包含 {context} 占位符");
    }

    @Test
    @DisplayName("retrieval config has reasonable defaults")
    void defaultExtension_retrievalConfig_reasonable() {
        DefaultDomainRagExtension def = new DefaultDomainRagExtension();
        RetrievalConfig config = def.getRetrievalConfig();

        assertEquals(10, config.getMaxResults(),
                "默认 maxResults 应为 10");
        assertTrue(config.getMinScore() > 0 && config.getMinScore() < 1,
                "minScore 应在 0-1 之间");
        assertTrue(config.isUseHybridSearch(),
                "默认应使用混合检索");
    }

    @Test
    @DisplayName("isApplicable accepts all queries")
    void defaultExtension_isApplicable_acceptsAll() {
        DefaultDomainRagExtension def = new DefaultDomainRagExtension();

        assertTrue(def.isApplicable("任何问题都可以"),
                "默认扩展应接受所有查询");
        assertTrue(def.isApplicable(""),
                "空查询也应被接受");
    }

    @Test
    @DisplayName("postProcessAnswer returns input unchanged")
    void defaultExtension_postProcessAnswer_noOp() {
        DefaultDomainRagExtension def = new DefaultDomainRagExtension();
        assertEquals("原始回答", def.postProcessAnswer("原始回答"));
    }

    // ─────────────────────────────────────────────
    // 2. DomainExtensionRegistry 查找行为
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("empty registry initializes hasExtensions to false")
    void emptyRegistry_hasNoExtensions() {
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of());
        assertFalse(registry.hasExtensions());
        assertNull(registry.getExtension("any"));
    }

    @Test
    @DisplayName("null registry does not throw")
    void nullRegistry_doesNotThrow() {
        DomainExtensionRegistry registry = new DomainExtensionRegistry(null);
        assertFalse(registry.hasExtensions());
    }

    @Test
    @DisplayName("registered extension can be found by domainId")
    void registry_lookupByDomainId() {
        TestMedicalDomainExtension medical = new TestMedicalDomainExtension();
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(medical));

        assertTrue(registry.hasExtensions());
        assertTrue(registry.hasDomain("medical"));
        assertEquals("医疗问诊", registry.getExtension("medical").getDomainName());
    }

    @Test
    @DisplayName("unknown domainId returns null")
    void registry_unknownDomain_returnsNull() {
        TestMedicalDomainExtension medical = new TestMedicalDomainExtension();
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(medical));

        assertNull(registry.getExtension("unknown-domain"));
        assertNull(registry.getSystemPromptTemplate("unknown-domain"));
    }

    @Test
    @DisplayName("null domainId returns first registered extension (default)")
    void registry_nullDomainId_returnsFirst() {
        TestMedicalDomainExtension medical = new TestMedicalDomainExtension();
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(medical));

        DomainRagExtension defaultExt = registry.getExtension(null);
        assertNotNull(defaultExt);
        assertEquals("medical", defaultExt.getDomainId());
    }

    @Test
    @DisplayName("blank domainId also returns default extension")
    void registry_blankDomain_returnsDefault() {
        TestMedicalDomainExtension medical = new TestMedicalDomainExtension();
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(medical));

        assertNotNull(registry.getExtension("  "));
        assertNotNull(registry.getExtension(""));
    }

    @Test
    @DisplayName("empty domainId extension is skipped")
    void registry_blankDomainId_skipped() {
        TestBlankDomainExtension blank = new TestBlankDomainExtension();
        TestMedicalDomainExtension valid = new TestMedicalDomainExtension();
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(blank, valid));

        assertEquals(1, registry.getAllExtensions().size());
        assertTrue(registry.hasDomain("medical"));
        assertFalse(registry.hasDomain(""));
    }

    @Test
    @DisplayName("getSystemPromptTemplate correctly delegates to extension")
    void registry_getSystemPromptTemplate_delegates() {
        TestMedicalDomainExtension medical = new TestMedicalDomainExtension();
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(medical));

        assertTrue(registry.getSystemPromptTemplate("medical").contains("{context}"));
    }

    @Test
    @DisplayName("multiple extensions each can be looked up")
    void registry_multipleExtensions_allFound() {
        TestMedicalDomainExtension medical = new TestMedicalDomainExtension();
        TestLegalDomainExtension legal = new TestLegalDomainExtension();
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(medical, legal));

        assertEquals(2, registry.getAllExtensions().size());
        assertTrue(registry.hasDomain("medical"));
        assertTrue(registry.hasDomain("legal"));
        assertFalse(registry.hasDomain("finance"));
    }

    // ─────────────────────────────────────────────
    // 3. 模拟医疗领域扩展的行为验证
    // （与 demo-domain-extension/MedicalRagExtension 行为一致）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("medical extension — high-recall retrieval config")
    void medicalExtension_usesHighRecallConfig() {
        TestMedicalDomainExtension medical = new TestMedicalDomainExtension();
        RetrievalConfig config = medical.getRetrievalConfig();

        assertTrue(config.getMaxResults() >= 12,
                "医疗领域应返回 >= 12 个结果，实际: " + config.getMaxResults());
        assertTrue(config.getMinScore() < 0.4,
                "医疗领域 minScore 应 < 0.4，实际: " + config.getMinScore());
        assertTrue(config.isUseHybridSearch(),
                "医疗领域应使用混合检索");
        assertTrue(config.isUseRerank(),
                "医疗领域应启用重排");
    }

    @Test
    @DisplayName("medical extension — isApplicable recognizes medical symptoms")
    void medicalExtension_isApplicable_medicalSymptoms() {
        TestMedicalDomainExtension medical = new TestMedicalDomainExtension();

        assertTrue(medical.isApplicable("最近总是头疼"),
                "头疼是医学症状，应返回 true");
        assertTrue(medical.isApplicable("发烧38度怎么办"),
                "发烧是医学症状，应返回 true");
        assertTrue(medical.isApplicable("过敏皮肤症状"),
                "过敏是医学症状，应返回 true");
        assertTrue(medical.isApplicable("咳嗽有痰"),
                "咳嗽是医学症状，应返回 true");
        assertTrue(medical.isApplicable("需要手术吗"),
                "手术是医学相关，应返回 true");
    }

    @Test
    @DisplayName("medical extension — isApplicable filters non-medical issues")
    void medicalExtension_isApplicable_nonMedical() {
        TestMedicalDomainExtension medical = new TestMedicalDomainExtension();

        assertFalse(medical.isApplicable("今天天气怎么样"),
                "天气问题不是医学问题，应返回 false");
        assertFalse(medical.isApplicable("Python 怎么学"),
                "编程问题不是医学问题，应返回 false");
        assertFalse(medical.isApplicable("推荐几本小说"),
                "推荐问题不是医学问题，应返回 false");
        assertFalse(medical.isApplicable(""),
                "空查询应返回 false");
    }

    @Test
    @DisplayName("medical extension — system prompt contains {context} placeholder")
    void medicalExtension_promptContainsContextPlaceholder() {
        TestMedicalDomainExtension medical = new TestMedicalDomainExtension();
        assertTrue(medical.getSystemPromptTemplate().contains("{context}"),
                "医疗提示词应包含 {context} 占位符");
    }

    @Test
    @DisplayName("medical extension — postProcessAnswer adds medical consultation reminder")
    void medicalExtension_postProcessAnswer_addsDisclaimer() {
        TestMedicalDomainExtension medical = new TestMedicalDomainExtension();

        String answer = "这可能是感冒引起的头疼...";
        String processed = medical.postProcessAnswer(answer);

        assertTrue(processed.contains("就医") || processed.contains("医生"),
                "后处理应添加就医提醒，实际: " + processed);
    }

    @Test
    @DisplayName("medical extension — does not add duplicate disclaimer")
    void medicalExtension_postProcessAnswer_noDuplicate() {
        TestMedicalDomainExtension medical = new TestMedicalDomainExtension();

        String answer = "建议您及时就医检查";
        String processed = medical.postProcessAnswer(answer);

        assertEquals(answer, processed,
                "已有就医提醒时不应重复添加");
    }

    @Test
    @DisplayName("medical extension — null answer returns null")
    void medicalExtension_postProcessAnswer_nullAnswer() {
        TestMedicalDomainExtension medical = new TestMedicalDomainExtension();
        assertNull(medical.postProcessAnswer(null));
    }

    // ─────────────────────────────────────────────
    // 测试辅助类
    // ─────────────────────────────────────────────

    /** Simulated medical domain extension (consistent with MedicalRagExtension behavior). */
    static class TestMedicalDomainExtension implements DomainRagExtension {
        @Override
        public String getDomainId() { return "medical"; }
        @Override
        public String getDomainName() { return "医疗问诊"; }
        @Override
        public String getSystemPromptTemplate() {
            return "你是一个专业的医疗健康问诊助手。\n\n参考资料：\n{context}";
        }
        @Override
        public RetrievalConfig getRetrievalConfig() {
            return RetrievalConfig.builder()
                    .maxResults(15)
                    .minScore(0.3)
                    .useHybridSearch(true)
                    .useRerank(true)
                    .vectorWeight(0.5)
                    .fulltextWeight(0.5)
                    .build();
        }
        @Override
        public boolean isApplicable(String query) {
            if (query == null || query.isBlank()) return false;
            String lower = query.toLowerCase();
            return lower.contains("疼") || lower.contains("痛") || lower.contains("病")
                    || lower.contains("药") || lower.contains("症") || lower.contains("医")
                    || lower.contains("健康") || lower.contains("检查") || lower.contains("治疗")
                    || lower.contains("发烧") || lower.contains("咳嗽") || lower.contains("过敏")
                    || lower.contains("症状") || lower.contains("诊断") || lower.contains("手术");
        }
        @Override
        public String postProcessAnswer(String answer) {
            if (answer == null) return null;
            if (answer.contains("就医") || answer.contains("医生")
                    || answer.contains("检查") || answer.contains("治疗")) {
                return answer;
            }
            return answer + "\n\n⚠️ 温馨提示：如症状持续或加重，请及时就医。";
        }
    }

    /** Simulated legal domain extension. */
    static class TestLegalDomainExtension implements DomainRagExtension {
        @Override
        public String getDomainId() { return "legal"; }
        @Override
        public String getDomainName() { return "法律咨询"; }
        @Override
        public String getSystemPromptTemplate() {
            return "你是一个专业的法律咨询助手。\n\n参考资料：\n{context}";
        }
        @Override
        public RetrievalConfig getRetrievalConfig() {
            return RetrievalConfig.builder()
                    .maxResults(10)
                    .minScore(0.4)
                    .useHybridSearch(true)
                    .useRerank(true)
                    .build();
        }
        @Override
        public boolean isApplicable(String query) {
            if (query == null || query.isBlank()) return false;
            String lower = query.toLowerCase();
            return lower.contains("法律") || lower.contains("合同") || lower.contains("诉讼")
                    || lower.contains("赔偿") || lower.contains("纠纷") || lower.contains("律师");
        }
        @Override
        public String postProcessAnswer(String answer) {
            return answer;
        }
    }

    /** Test extension with empty domainId (should be skipped). */
    static class TestBlankDomainExtension implements DomainRagExtension {
        @Override
        public String getDomainId() { return ""; }
        @Override
        public String getDomainName() { return "空白领域"; }
        @Override
        public String getSystemPromptTemplate() { return "blank"; }
        @Override
        public RetrievalConfig getRetrievalConfig() { return new RetrievalConfig(); }
        @Override
        public boolean isApplicable(String query) { return true; }
        @Override
        public String postProcessAnswer(String answer) { return answer; }
    }
}
