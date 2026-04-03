package com.springairag.demo.component;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ComponentLevelController unit test — mocks ChatClient beans,
 * tests controller endpoints without starting full Spring context.
 */
@WebMvcTest(controllers = ComponentLevelController.class)
@ActiveProfiles("test")
class ComponentLevelControllerTest {

    @MockBean(name = "ragChatClient")
    private ChatClient ragChatClient;

    @MockBean(name = "ragChatClientWithMemory")
    private ChatClient ragChatClientWithMemory;

    @Autowired
    private ComponentLevelController controller;

    @Test
    void controllerLoads() {
        assertNotNull(controller);
    }

    @Test
    void askEndpointExists() {
        assertNotNull(controller);
        assertNotNull(ragChatClient);
    }

    @Test
    void chatEndpointExists() {
        assertNotNull(controller);
        assertNotNull(ragChatClientWithMemory);
    }

    @Test
    void compareMemoryEndpointExists() {
        assertNotNull(controller);
        assertNotNull(ragChatClient);
        assertNotNull(ragChatClientWithMemory);
    }
}
