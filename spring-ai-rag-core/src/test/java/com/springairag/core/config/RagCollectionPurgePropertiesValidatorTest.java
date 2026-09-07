package com.springairag.core.config;

import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatSessionCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Purge 启动门禁：配置越界拒绝、启用时依赖 bean 必须在场。 */
class RagCollectionPurgePropertiesValidatorTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ChatExecutionService> provider(
            ChatExecutionService bean) {
        ObjectProvider<ChatExecutionService> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ChatSessionCoordinator> coordinatorProvider(
            ChatSessionCoordinator bean) {
        ObjectProvider<ChatSessionCoordinator> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    @Test
    void passesWhenDisabledEvenIfBeansAreMissing() {
        RagProperties properties = new RagProperties();
        // 默认 enabled=false：不要求依赖 bean。
        RagCollectionPurgePropertiesValidator validator =
                new RagCollectionPurgePropertiesValidator(
                        properties,
                        provider(null),
                        coordinatorProvider(null));

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void passesWhenEnabledWithAllDependenciesPresent() {
        RagProperties properties = new RagProperties();
        properties.getCollectionPurge().setEnabled(true);
        RagCollectionPurgePropertiesValidator validator =
                new RagCollectionPurgePropertiesValidator(
                        properties,
                        provider(mock(ChatExecutionService.class)),
                        coordinatorProvider(mock(ChatSessionCoordinator.class)));

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void rejectsEnabledPurgeWithoutChatExecutionService() {
        RagProperties properties = new RagProperties();
        properties.getCollectionPurge().setEnabled(true);
        RagCollectionPurgePropertiesValidator validator =
                new RagCollectionPurgePropertiesValidator(
                        properties,
                        provider(null),
                        coordinatorProvider(mock(ChatSessionCoordinator.class)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                validator::validate);
        assertTrue(error.getMessage().contains("ChatExecutionService"));
    }

    @Test
    void rejectsEnabledPurgeWithoutChatSessionCoordinator() {
        RagProperties properties = new RagProperties();
        properties.getCollectionPurge().setEnabled(true);
        RagCollectionPurgePropertiesValidator validator =
                new RagCollectionPurgePropertiesValidator(
                        properties,
                        provider(mock(ChatExecutionService.class)),
                        coordinatorProvider(null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                validator::validate);
        assertTrue(error.getMessage().contains("ChatSessionCoordinator"));
    }

    @Test
    void rejectsOutOfRangePurgeLimitsBeforeBeanResolution() {
        RagProperties properties = new RagProperties();
        properties.getCollectionPurge().setEnabled(true);
        properties.getCollectionPurge().setMaxDocuments(0);
        RagCollectionPurgePropertiesValidator validator =
                new RagCollectionPurgePropertiesValidator(
                        properties,
                        provider(null),
                        coordinatorProvider(null));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                validator::validate);
        assertTrue(error.getMessage().contains("max-documents"));
    }
}
