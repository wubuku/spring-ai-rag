package com.springairag.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the chat section of the unified {@link RagProperties} binding.
 *
 * <p>Chat components depend on the focused type, while binding remains owned by
 * the single {@code rag} configuration object. Returning the nested instance
 * keeps validation and consumers on the same configuration state.</p>
 */
@Configuration(proxyBeanMethods = false)
public class RagChatPropertiesConfig {

    @Bean
    RagChatProperties ragChatProperties(RagProperties ragProperties) {
        return ragProperties.getChat();
    }
}
