package com.springairag.core.advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.core.Ordered;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link AbstractRagAdvisor} 单元测试
 */
class AbstractRagAdvisorTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractRagAdvisorTest.class);

    /** 测试用具体子类 */
    private TestAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new TestAdvisor();
    }

    @Nested
    @DisplayName("enabled 开关")
    class EnabledTests {

        @Test
        @DisplayName("默认启用")
        void defaultEnabled() {
            assertTrue(advisor.isEnabled());
        }

        @Test
        @DisplayName("setEnabled 切换状态")
        void setEnabled() {
            advisor.setEnabled(false);
            assertFalse(advisor.isEnabled());

            advisor.setEnabled(true);
            assertTrue(advisor.isEnabled());
        }

        @Test
        @DisplayName("禁用时 shouldSkip 返回 true")
        void shouldSkipWhenDisabled() {
            advisor.setEnabled(false);
            assertTrue(advisor.shouldSkip(log));
        }

        @Test
        @DisplayName("启用时 shouldSkip 返回 false")
        void shouldNotSkipWhenEnabled() {
            advisor.setEnabled(true);
            assertFalse(advisor.shouldSkip(log));
        }
    }

    @Nested
    @DisplayName("after() 透传")
    class AfterTests {

        @Test
        @DisplayName("after() 原样返回 response")
        void afterPassesThrough() {
            ChatClientResponse mockResponse = mock(ChatClientResponse.class);
            AdvisorChain mockChain = mock(AdvisorChain.class);

            ChatClientResponse result = advisor.after(mockResponse, mockChain);

            assertSame(mockResponse, result);
        }
    }

    @Nested
    @DisplayName("子类契约")
    class ContractTests {

        @Test
        @DisplayName("getName 返回自定义名称")
        void getName() {
            assertEquals("TestAdvisor", advisor.getName());
        }

        @Test
        @DisplayName("getOrder 返回指定顺序")
        void getOrder() {
            assertEquals(Ordered.HIGHEST_PRECEDENCE + 50, advisor.getOrder());
        }
    }

    /** 测试用具体实现 */
    static class TestAdvisor extends AbstractRagAdvisor {

        @Override
        public String getName() {
            return "TestAdvisor";
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 50;
        }

        @Override
        public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
            return request;
        }
    }
}
