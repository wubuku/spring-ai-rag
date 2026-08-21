package com.springairag.core.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Fails startup when the cross-field chat execution limits are impossible.
 */
@Component
public final class RagChatPropertiesValidator {

    private final RagProperties properties;

    public RagChatPropertiesValidator(RagProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        properties.getChat().validate();
    }
}
