package com.springairag.demo;

import com.springairag.core.service.RagChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo E2E test — starts Spring Boot context with mocked AI components,
 * uses real PostgreSQL for ChatMemory.
 *
 * <p>Uses {@code @MockBean} to replace real AI models, ensuring tests
 * don't require external API keys while still validating the full
 * Spring context loads correctly with real database.
 */
@SpringBootTest(
        classes = DemoTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BasicRagDemoApplicationTest {

    @MockBean
    private EmbeddingModel embeddingModel;

    @MockBean
    private ChatModel chatModel;

    @Autowired(required = false)
    private RagChatService ragChatService;

    @Autowired(required = false)
    private DemoController demoController;

    @Test
    void contextLoads() {
        assertNotNull(ragChatService, "RagChatService should be loaded");
        assertNotNull(demoController, "DemoController should be loaded");
    }

    @Test
    void demoControllerEndpointsExist() {
        assertNotNull(demoController);
        assertEquals(DemoController.class, demoController.getClass());
    }

    @Test
    void ragChatServiceInitialized() {
        assertNotNull(ragChatService);
    }
}
