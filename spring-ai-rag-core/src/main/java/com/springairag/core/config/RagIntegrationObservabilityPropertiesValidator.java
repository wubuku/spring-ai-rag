package com.springairag.core.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 校验外部业务接入数据面可观测性配置。
 */
@Component
public final class RagIntegrationObservabilityPropertiesValidator {

    private final RagProperties properties;

    public RagIntegrationObservabilityPropertiesValidator(RagProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        properties.getIntegrationObservability().validate();
    }
}
