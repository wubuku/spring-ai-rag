package com.springairag.core.extension;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.api.service.DomainRagExtension;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DomainExtensionRegistry
 */
class DomainExtensionRegistryTest {

    // Test domain extension implementation
    static class TestDomainExtension implements DomainRagExtension {
        private final String domainId;
        private final String domainName;

        TestDomainExtension(String domainId, String domainName) {
            this.domainId = domainId;
            this.domainName = domainName;
        }

        @Override public String getDomainId() { return domainId; }
        @Override public String getDomainName() { return domainName; }
        @Override public String getSystemPromptTemplate() { return "test prompt for " + domainId; }
    }

    static class LegacyContextDomainExtension extends TestDomainExtension {
        LegacyContextDomainExtension() {
            super("legacy", "Legacy");
        }

        @Override
        public String getSystemPromptTemplate() {
            return "领域安全规则\n\n参考资料：\n{context}";
        }
    }

    static class ModeAwareDomainExtension extends LegacyContextDomainExtension {
        @Override
        public String getSystemPromptTemplate(ChatMode mode) {
            return mode == ChatMode.KNOWLEDGE
                    ? getSystemPromptTemplate()
                    : "仅保留领域安全规则";
        }
    }

    @Test
    @DisplayName("empty list initialization: hasExtensions returns false")
    void emptyList_hasNoExtensions() {
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of());
        assertFalse(registry.hasExtensions());
        assertNull(registry.getExtension("any"));
    }

    @Test
    @DisplayName("null list initialization does not throw")
    void nullList_doesNotThrow() {
        DomainExtensionRegistry registry = new DomainExtensionRegistry(null);
        assertFalse(registry.hasExtensions());
    }

    @Test
    @DisplayName("single registered extension can be found by domainId")
    void singleExtension_canBeFound() {
        TestDomainExtension ext = new TestDomainExtension("skin", "Skin Detection");
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(ext));

        assertTrue(registry.hasExtensions());
        assertTrue(registry.hasDomain("skin"));
        assertEquals("Skin Detection", registry.getExtension("skin").getDomainName());
    }

    @Test
    @DisplayName("multiple registered extensions can each be found")
    void multipleExtensions_allFound() {
        TestDomainExtension skin = new TestDomainExtension("skin", "Skin Detection");
        TestDomainExtension legal = new TestDomainExtension("legal", "Legal Consultation");
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(skin, legal));

        assertEquals(2, registry.getAllExtensions().size());
        assertTrue(registry.hasDomain("skin"));
        assertTrue(registry.hasDomain("legal"));
        assertFalse(registry.hasDomain("finance"));
    }

    @Test
    @DisplayName("null domainId returns first registered extension (default)")
    void nullDomainId_returnsFirstExtension() {
        TestDomainExtension ext = new TestDomainExtension("skin", "Skin Detection");
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(ext));

        DomainRagExtension defaultExt = registry.getExtension(null);
        assertNotNull(defaultExt);
        assertEquals("skin", defaultExt.getDomainId());
    }

    @Test
    @DisplayName("blank domainId also returns default extension")
    void blankDomainId_returnsDefault() {
        TestDomainExtension ext = new TestDomainExtension("skin", "Skin Detection");
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(ext));

        assertNotNull(registry.getExtension("  "));
        assertNotNull(registry.getExtension(""));
    }

    @Test
    @DisplayName("getSystemPromptTemplate correctly delegates to extension")
    void getSystemPromptTemplate_delegatesToExtension() {
        TestDomainExtension ext = new TestDomainExtension("skin", "Skin Detection");
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(ext));

        assertEquals("test prompt for skin", registry.getSystemPromptTemplate("skin"));
    }

    @Test
    @DisplayName("getSystemPromptTemplate returns null for unknown domain")
    void getSystemPromptTemplate_unknownDomain_returnsNull() {
        TestDomainExtension ext = new TestDomainExtension("skin", "Skin Detection");
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(ext));

        assertNull(registry.getSystemPromptTemplate("unknown"));
    }

    @Test
    @DisplayName("legacy context prompt remains compatible with KNOWLEDGE")
    void legacyContextPrompt_knowledgeMode_removesPlaceholder() {
        DomainExtensionRegistry registry = new DomainExtensionRegistry(
                List.of(new LegacyContextDomainExtension()));

        assertEquals(
                "领域安全规则\n\n参考资料：",
                registry.getSystemPromptTemplate("legacy", ChatMode.KNOWLEDGE));
    }

    @Test
    @DisplayName("legacy context prompt is rejected for PLAIN and AGENT")
    void legacyContextPrompt_nonKnowledgeMode_usesTypedError() {
        DomainExtensionRegistry registry = new DomainExtensionRegistry(
                List.of(new LegacyContextDomainExtension()));

        RagException plain = assertThrows(
                RagException.class,
                () -> registry.getSystemPromptTemplate(
                        "legacy", ChatMode.PLAIN));
        RagException agent = assertThrows(
                RagException.class,
                () -> registry.getSystemPromptTemplate(
                        "legacy", ChatMode.AGENT));

        assertEquals(
                ErrorCode.DOMAIN_MODE_UNSUPPORTED,
                plain.getErrorCodeEnum());
        assertEquals(
                ErrorCode.DOMAIN_MODE_UNSUPPORTED,
                agent.getErrorCodeEnum());
    }

    @Test
    @DisplayName("mode-aware prompt can preserve domain safety rules in PLAIN")
    void modeAwarePrompt_plainMode_isAccepted() {
        DomainExtensionRegistry registry = new DomainExtensionRegistry(
                List.of(new ModeAwareDomainExtension()));

        assertEquals(
                "仅保留领域安全规则",
                registry.getSystemPromptTemplate("legacy", ChatMode.PLAIN));
    }

    @Test
    @DisplayName("extensions with blank domainId are skipped")
    void blankDomainId_skipped() {
        TestDomainExtension blank = new TestDomainExtension("", "Blank");
        TestDomainExtension valid = new TestDomainExtension("skin", "Skin Detection");
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(blank, valid));

        assertEquals(1, registry.getAllExtensions().size());
        assertTrue(registry.hasDomain("skin"));
    }

    @Test
    @DisplayName("DefaultDomainRagExtension has reasonable default retrieval config")
    void defaultExtension_hasReasonableConfig() {
        DefaultDomainRagExtension ext = new DefaultDomainRagExtension();
        RetrievalConfig config = ext.getRetrievalConfig();

        assertEquals(10, config.getMaxResults());
        assertTrue(config.getMinScore() > 0 && config.getMinScore() < 1);
        assertTrue(config.isUseHybridSearch());
        assertTrue(config.isUseRerank());
    }

    @Test
    @DisplayName("DefaultDomainRagExtension leaves context injection to the RAG pipeline")
    void defaultExtension_promptDoesNotContainContextPlaceholder() {
        DefaultDomainRagExtension ext = new DefaultDomainRagExtension();
        assertFalse(ext.getSystemPromptTemplate().contains("{context}"));
    }
}
