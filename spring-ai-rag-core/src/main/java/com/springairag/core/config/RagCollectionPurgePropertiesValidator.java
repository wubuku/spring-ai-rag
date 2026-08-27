package com.springairag.core.config;

import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatSessionCoordinator;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 启动时拒绝无界或自相矛盾的 Collection purge 配置。
 */
@Component
public class RagCollectionPurgePropertiesValidator {

    private final RagProperties properties;
    private final ObjectProvider<ChatExecutionService> executionService;
    private final ObjectProvider<ChatSessionCoordinator> sessionCoordinator;

    public RagCollectionPurgePropertiesValidator(
            RagProperties properties,
            ObjectProvider<ChatExecutionService> executionService,
            ObjectProvider<ChatSessionCoordinator> sessionCoordinator) {
        this.properties = properties;
        this.executionService = executionService;
        this.sessionCoordinator = sessionCoordinator;
    }

    @PostConstruct
    void validate() {
        properties.getCollectionPurge().validate();
        if (properties.getCollectionPurge().isEnabled()) {
            requireBean(executionService, "ChatExecutionService");
            requireBean(sessionCoordinator, "ChatSessionCoordinator");
        }
    }

    private void requireBean(ObjectProvider<?> provider, String name) {
        if (provider.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "rag.collection-purge.enabled requires " + name);
        }
    }
}
