package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class RagChatPropertiesConfigTest {

    @Test
    void exposesTheNestedChatPropertiesInstance() {
        RagProperties properties = new RagProperties();

        RagChatProperties exposed =
                new RagChatPropertiesConfig().ragChatProperties(properties);

        assertSame(properties.getChat(), exposed);
    }
}
