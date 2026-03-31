package com.springairag.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagAdvisorProvider 接口测试
 */
class RagAdvisorProviderTest {

    @Test
    @DisplayName("接口方法可正确调用")
    void interfaceMethods_work() {
        RagAdvisorProvider provider = new RagAdvisorProvider() {
            @Override
            public String getName() { return "TestAdvisor"; }

            @Override
            public int getOrder() { return 100; }

            @Override
            public org.springframework.ai.chat.client.advisor.api.BaseAdvisor createAdvisor() {
                return null; // 测试时不创建实际 Advisor
            }
        };

        assertEquals("TestAdvisor", provider.getName());
        assertEquals(100, provider.getOrder());
    }
}
