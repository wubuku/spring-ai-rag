package com.springairag.core.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** Validates data-governance retention and cleanup bounds at startup. */
@Component
public final class RagDataGovernancePropertiesValidator {

    private final RagProperties properties;

    public RagDataGovernancePropertiesValidator(RagProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        properties.getDataGovernance().validate();
        properties.getLlmBudget().validate();
    }
}
