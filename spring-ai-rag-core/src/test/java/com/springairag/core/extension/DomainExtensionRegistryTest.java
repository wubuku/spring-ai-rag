package com.springairag.core.extension;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.service.DomainRagExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DomainExtensionRegistry 单元测试
 */
class DomainExtensionRegistryTest {

    // 测试用的领域扩展实现
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

    @Test
    @DisplayName("空列表初始化时 hasExtensions 为 false")
    void emptyList_hasNoExtensions() {
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of());
        assertFalse(registry.hasExtensions());
        assertNull(registry.getExtension("any"));
    }

    @Test
    @DisplayName("null 列表初始化时不抛异常")
    void nullList_doesNotThrow() {
        DomainExtensionRegistry registry = new DomainExtensionRegistry(null);
        assertFalse(registry.hasExtensions());
    }

    @Test
    @DisplayName("注册单个扩展后可按 domainId 查找")
    void singleExtension_canBeFound() {
        TestDomainExtension ext = new TestDomainExtension("skin", "皮肤检测");
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(ext));

        assertTrue(registry.hasExtensions());
        assertTrue(registry.hasDomain("skin"));
        assertEquals("皮肤检测", registry.getExtension("skin").getDomainName());
    }

    @Test
    @DisplayName("注册多个扩展后各自可查")
    void multipleExtensions_allFound() {
        TestDomainExtension skin = new TestDomainExtension("skin", "皮肤检测");
        TestDomainExtension legal = new TestDomainExtension("legal", "法律咨询");
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(skin, legal));

        assertEquals(2, registry.getAllExtensions().size());
        assertTrue(registry.hasDomain("skin"));
        assertTrue(registry.hasDomain("legal"));
        assertFalse(registry.hasDomain("finance"));
    }

    @Test
    @DisplayName("domainId 为 null 时返回第一个注册的扩展（默认）")
    void nullDomainId_returnsFirstExtension() {
        TestDomainExtension ext = new TestDomainExtension("skin", "皮肤检测");
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(ext));

        DomainRagExtension defaultExt = registry.getExtension(null);
        assertNotNull(defaultExt);
        assertEquals("skin", defaultExt.getDomainId());
    }

    @Test
    @DisplayName("domainId 为空白字符串时也返回默认扩展")
    void blankDomainId_returnsDefault() {
        TestDomainExtension ext = new TestDomainExtension("skin", "皮肤检测");
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(ext));

        assertNotNull(registry.getExtension("  "));
        assertNotNull(registry.getExtension(""));
    }

    @Test
    @DisplayName("getSystemPromptTemplate 正确委托给扩展")
    void getSystemPromptTemplate_delegatesToExtension() {
        TestDomainExtension ext = new TestDomainExtension("skin", "皮肤检测");
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(ext));

        assertEquals("test prompt for skin", registry.getSystemPromptTemplate("skin"));
    }

    @Test
    @DisplayName("getSystemPromptTemplate 对未知 domain 返回 null")
    void getSystemPromptTemplate_unknownDomain_returnsNull() {
        TestDomainExtension ext = new TestDomainExtension("skin", "皮肤检测");
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(ext));

        assertNull(registry.getSystemPromptTemplate("unknown"));
    }

    @Test
    @DisplayName("空 domainId 的扩展被跳过")
    void blankDomainId_skipped() {
        TestDomainExtension blank = new TestDomainExtension("", "空白");
        TestDomainExtension valid = new TestDomainExtension("skin", "皮肤检测");
        DomainExtensionRegistry registry = new DomainExtensionRegistry(List.of(blank, valid));

        assertEquals(1, registry.getAllExtensions().size());
        assertTrue(registry.hasDomain("skin"));
    }

    @Test
    @DisplayName("DefaultDomainRagExtension 的默认检索配置合理")
    void defaultExtension_hasReasonableConfig() {
        DefaultDomainRagExtension ext = new DefaultDomainRagExtension();
        RetrievalConfig config = ext.getRetrievalConfig();

        assertEquals(10, config.getMaxResults());
        assertTrue(config.getMinScore() > 0 && config.getMinScore() < 1);
        assertTrue(config.isUseHybridSearch());
        assertTrue(config.isUseRerank());
    }

    @Test
    @DisplayName("DefaultDomainRagExtension 的系统提示词包含 {context} 占位符")
    void defaultExtension_promptContainsContextPlaceholder() {
        DefaultDomainRagExtension ext = new DefaultDomainRagExtension();
        assertTrue(ext.getSystemPromptTemplate().contains("{context}"));
    }
}
