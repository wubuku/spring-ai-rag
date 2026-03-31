package com.springairag.api.service;

import com.springairag.api.dto.RetrievalConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DomainRagExtension 接口默认方法测试
 */
class DomainRagExtensionTest {

    @Test
    void defaultRetrievalConfig() {
        DomainRagExtension ext = new DomainRagExtension() {
            @Override public String getDomainId() { return "test"; }
            @Override public String getDomainName() { return "测试领域"; }
            @Override public String getSystemPromptTemplate() { return "你是测试助手"; }
        };

        RetrievalConfig config = ext.getRetrievalConfig();
        assertNotNull(config);
        assertEquals(10, config.getMaxResults());
    }

    @Test
    void defaultPostProcess() {
        DomainRagExtension ext = new DomainRagExtension() {
            @Override public String getDomainId() { return "test"; }
            @Override public String getDomainName() { return "测试"; }
            @Override public String getSystemPromptTemplate() { return ""; }
        };

        assertEquals("原始回答", ext.postProcessAnswer("原始回答"));
    }

    @Test
    void defaultIsApplicable() {
        DomainRagExtension ext = new DomainRagExtension() {
            @Override public String getDomainId() { return "test"; }
            @Override public String getDomainName() { return "测试"; }
            @Override public String getSystemPromptTemplate() { return ""; }
        };

        assertTrue(ext.isApplicable("任何查询"));
    }
}
