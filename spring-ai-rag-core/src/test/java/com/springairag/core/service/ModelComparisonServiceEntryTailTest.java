package com.springairag.core.service;

import com.springairag.core.config.ChatModelRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ModelComparisonService 便捷入口长尾（Batch 656，JaCoCo 驱动）：
 * compareProviders 按 provider 列表逐个解析并比较、compareAll
 * Providers 经可用模型引用全集比较。
 */
class ModelComparisonServiceEntryTailTest {

    private ExecutorService executor;
    private ChatModelRouter chatModelRouter;
    private ModelComparisonService service;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        chatModelRouter = mock(ChatModelRouter.class);
        service = new ModelComparisonService(executor, chatModelRouter);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private ChatModel answeringModel(String answer) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new org.springframework.ai.chat.messages
                        .AssistantMessage(answer))),
                ChatResponseMetadata.builder().build()));
        return model;
    }

    @Test
    void compareProvidersResolvesEachReferenceInOrder() {
        ChatModel modelA = answeringModel("A 回答");
        ChatModel modelB = answeringModel("B 回答");
        when(chatModelRouter.resolveRequired("a/model-a")).thenReturn(modelA);
        when(chatModelRouter.resolveRequired("b/model-b")).thenReturn(modelB);

        var results = service.compareProviders(
                "问题", List.of("a/model-a", "b/model-b"), 5);

        assertEquals(2, results.size());
        assertEquals("a/model-a", results.get(0).getModelName());
        assertEquals("b/model-b", results.get(1).getModelName());
    }

    @Test
    void compareAllProvidersUsesAvailableModelRefs() {
        ChatModel modelA = answeringModel("A 回答");
        ChatModel modelB = answeringModel("B 回答");
        when(chatModelRouter.getAvailableModelRefs())
                .thenReturn(List.of("a/model-a", "b/model-b"));
        when(chatModelRouter.resolveRequired("a/model-a")).thenReturn(modelA);
        when(chatModelRouter.resolveRequired("b/model-b")).thenReturn(modelB);

        var results = service.compareAllProviders("问题", 5);

        assertEquals(2, results.size());
        assertEquals("a/model-a", results.get(0).getModelName());
    }
}
