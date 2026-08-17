package com.springairag.api.service;

import com.springairag.api.enums.ChatMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RagAdvisorProvider interface
 */
class RagAdvisorProviderTest {

    @Test
    @DisplayName("interface methods can be invoked correctly")
    void interfaceMethods_work() {
        RagAdvisorProvider provider = new RagAdvisorProvider() {
            @Override
            public String getName() { return "TestAdvisor"; }

            @Override
            public int getOrder() { return 100; }

            @Override
            public org.springframework.ai.chat.client.advisor.api.BaseAdvisor createAdvisor() {
                return null; // does not create actual advisor in tests
            }
        };

        assertEquals("TestAdvisor", provider.getName());
        assertEquals(100, provider.getOrder());
        assertEquals(Set.of(ChatMode.KNOWLEDGE), provider.supportedModes());
        assertEquals(AdvisorScope.ATTEMPT, provider.advisorScope());
    }
}
