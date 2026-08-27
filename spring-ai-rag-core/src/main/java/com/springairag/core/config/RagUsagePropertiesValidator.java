package com.springairag.core.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 校验模型 invocation 账本配置。
 */
@Component
public final class RagUsagePropertiesValidator {

    private final RagProperties properties;

    public RagUsagePropertiesValidator(RagProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        properties.getUsage().validate();
    }
}
