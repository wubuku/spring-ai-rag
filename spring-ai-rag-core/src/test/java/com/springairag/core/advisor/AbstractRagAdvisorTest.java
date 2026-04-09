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
 * Unit tests for {@link AbstractRagAdvisor}
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
    @DisplayName("enabled switch")
    class EnabledTests {

        @Test
        @DisplayName("enabled by default")
        void defaultEnabled() {
            assertTrue(advisor.isEnabled());
        }

        @Test
        @DisplayName("setEnabled toggles state")
        void setEnabled() {
            advisor.setEnabled(false);
            assertFalse(advisor.isEnabled());

            advisor.setEnabled(true);
            assertTrue(advisor.isEnabled());
        }

        @Test
        @DisplayName("shouldSkip returns true when disabled")
        void shouldSkipWhenDisabled() {
            advisor.setEnabled(false);
            assertTrue(advisor.shouldSkip(log));
        }

        @Test
        @DisplayName("shouldSkip returns false when enabled")
        void shouldNotSkipWhenEnabled() {
            advisor.setEnabled(true);
            assertFalse(advisor.shouldSkip(log));
        }
    }

    @Nested
    @DisplayName("after() passthrough")
    class AfterTests {

        @Test
        @DisplayName("after() returns response unchanged")
        void afterPassesThrough() {
            ChatClientResponse mockResponse = mock(ChatClientResponse.class);
            AdvisorChain mockChain = mock(AdvisorChain.class);

            ChatClientResponse result = advisor.after(mockResponse, mockChain);

            assertSame(mockResponse, result);
        }
    }

    @Nested
    @DisplayName("subclass contract")
    class ContractTests {

        @Test
        @DisplayName("getName returns custom name")
        void getName() {
            assertEquals("TestAdvisor", advisor.getName());
        }

        @Test
        @DisplayName("getOrder returns specified order")
        void getOrder() {
            assertEquals(Ordered.HIGHEST_PRECEDENCE + 50, advisor.getOrder());
        }
    }

    /** Test concrete implementation */
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
