package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.ai.chat.model.ChatModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;

/**
 * ModelRegistry 生命周期与获取长尾（Batch 686，JaCoCo 驱动）：
 * register 的 BeansException 降级、getDefault 对缺失 bean 的 ISE、
 * get 对未知 provider 的 IAE。
 */
class ModelRegistryLifecycleTailTest {

    @Test
    void registerSkipsMissingBeans() {
        var ctx = mock(ApplicationContext.class);
        when(ctx.getBean(anyString(), any(Class.class)))
                .thenThrow(new NoSuchBeanDefinitionException("openAiChatModel"));

        var registry = new ModelRegistry(ctx, new RagProperties(), null);
        registry.init();

        assertThrows(IllegalArgumentException.class,
                () -> registry.get("openai"));
    }

    @Test
    void getDefaultThrowsWhenBeanMissing() {
        var ctx = mock(ApplicationContext.class);
        when(ctx.getBean("chatModel", ChatModel.class))
                .thenThrow(new NoSuchBeanDefinitionException("chatModel"));

        var registry = new ModelRegistry(ctx, new RagProperties(), null);

        assertThrows(NoSuchBeanDefinitionException.class,
                registry::getDefault);
    }

    @Test
    void getDefaultReturnsRegisteredModel() {
        var ctx = mock(ApplicationContext.class);
        var model = mock(ChatModel.class);
        when(ctx.getBean("chatModel", ChatModel.class)).thenReturn(model);

        var registry = new ModelRegistry(ctx, new RagProperties(), null);

        assertEquals(model, registry.getDefault());
    }

    @Test
    void unknownProviderThrowsIllegalArgument() {
        var ctx = mock(ApplicationContext.class);
        var registry = new ModelRegistry(ctx, new RagProperties(), null);

        assertThrows(IllegalArgumentException.class,
                () -> registry.get("ghost"));
    }
}
