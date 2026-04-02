package com.springairag.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ModelComparisonService 单元测试
 */
class ModelComparisonServiceTest {

    private ModelComparisonService service;

    @BeforeEach
    void setUp() {
        service = new ModelComparisonService();
    }

    private ChatModel mockModel(String responseText, long simulateDelayMs) {
        ChatModel model = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage msg =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);

        when(msg.getText()).thenReturn(responseText);
        when(generation.getOutput()).thenReturn(msg);
        when(response.getResult()).thenReturn(generation);
        when(response.getMetadata()).thenReturn(null);

        if (simulateDelayMs > 0) {
            when(model.call(any(Prompt.class))).thenAnswer(inv -> {
                Thread.sleep(simulateDelayMs);
                return response;
            });
        } else {
            when(model.call(any(Prompt.class))).thenReturn(response);
        }
        return model;
    }

    private ChatModel mockModelWithUsage(String responseText, int promptTokens, int completionTokens) {
        ChatModel model = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage msg =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        org.springframework.ai.chat.metadata.ChatResponseMetadata metadata =
                mock(org.springframework.ai.chat.metadata.ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);

        when(msg.getText()).thenReturn(responseText);
        when(generation.getOutput()).thenReturn(msg);
        when(response.getResult()).thenReturn(generation);
        when(usage.getPromptTokens()).thenReturn(promptTokens);
        when(usage.getCompletionTokens()).thenReturn(completionTokens);
        when(usage.getTotalTokens()).thenReturn(promptTokens + completionTokens);
        when(metadata.getUsage()).thenReturn(usage);
        when(response.getMetadata()).thenReturn(metadata);

        when(model.call(any(Prompt.class))).thenReturn(response);
        return model;
    }

    private ChatModel mockFailingModel(String errorMessage) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException(errorMessage));
        return model;
    }

    @Test
    @DisplayName("单模型对比成功")
    void compareModels_singleModel_success() {
        ChatModel model = mockModel("回答A", 0);
        Map<String, ChatModel> models = Map.of("deepseek", model);

        List<ModelComparisonService.ModelComparisonResult> results =
                service.compareModels("测试问题", models, 30);

        assertEquals(1, results.size());
        ModelComparisonService.ModelComparisonResult r = results.get(0);
        assertTrue(r.isSuccess());
        assertEquals("deepseek", r.getModelName());
        assertEquals("回答A", r.getResponse());
        assertTrue(r.getLatencyMs() >= 0);
    }

    @Test
    @DisplayName("多模型并行对比——两个模型都成功")
    void compareModels_twoModels_bothSucceed() {
        ChatModel modelA = mockModel("DeepSeek 回答", 0);
        ChatModel modelB = mockModel("Claude 回答", 0);

        Map<String, ChatModel> models = new LinkedHashMap<>();
        models.put("deepseek", modelA);
        models.put("claude", modelB);

        List<ModelComparisonService.ModelComparisonResult> results =
                service.compareModels("同一个问题", models, 30);

        assertEquals(2, results.size());
        assertTrue(results.get(0).isSuccess());
        assertTrue(results.get(1).isSuccess());
        assertEquals("DeepSeek 回答", results.get(0).getResponse());
        assertEquals("Claude 回答", results.get(1).getResponse());
    }

    @Test
    @DisplayName("多模型对比——一个成功一个失败")
    void compareModels_oneSuccessOneFailure() {
        ChatModel goodModel = mockModel("好的回答", 0);
        ChatModel badModel = mockFailingModel("API 错误");

        Map<String, ChatModel> models = new LinkedHashMap<>();
        models.put("good", goodModel);
        models.put("bad", badModel);

        List<ModelComparisonService.ModelComparisonResult> results =
                service.compareModels("测试", models, 30);

        assertEquals(2, results.size());
        assertTrue(results.get(0).isSuccess());
        assertFalse(results.get(1).isSuccess());
        assertEquals("API 错误", results.get(1).getError());
        assertNull(results.get(1).getResponse());
    }

    @Test
    @DisplayName("带 token 用量统计")
    void compareModels_withTokenUsage() {
        ChatModel model = mockModelWithUsage("回答", 50, 100);
        Map<String, ChatModel> models = Map.of("gpt-4", model);

        List<ModelComparisonService.ModelComparisonResult> results =
                service.compareModels("测试", models, 30);

        assertEquals(1, results.size());
        assertEquals(50, results.get(0).getPromptTokens());
        assertEquals(100, results.get(0).getCompletionTokens());
        assertEquals(150, results.get(0).getTotalTokens());
    }

    @Test
    @DisplayName("延迟测量准确——响应慢的模型 latencyMs 更大")
    void compareModels_latencyMeasured() {
        ChatModel fastModel = mockModel("快", 10);
        ChatModel slowModel = mockModel("慢", 100);

        Map<String, ChatModel> models = new LinkedHashMap<>();
        models.put("fast", fastModel);
        models.put("slow", slowModel);

        List<ModelComparisonService.ModelComparisonResult> results =
                service.compareModels("测试", models, 30);

        assertEquals(2, results.size());
        assertTrue(results.get(1).getLatencyMs() > results.get(0).getLatencyMs(),
                "慢模型的延迟应该大于快模型");
    }

    @Test
    @DisplayName("空模型 Map 返回空结果")
    void compareModels_emptyModels_returnsEmpty() {
        List<ModelComparisonService.ModelComparisonResult> results =
                service.compareModels("测试", Map.of(), 30);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("null 模型 Map 返回空结果")
    void compareModels_nullModels_returnsEmpty() {
        List<ModelComparisonService.ModelComparisonResult> results =
                service.compareModels("测试", null, 30);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("ModelComparisonResult.failure 工厂方法")
    void modelComparisonResult_failureFactory() {
        var r = ModelComparisonService.ModelComparisonResult.failure("test-model", "超时");
        assertFalse(r.isSuccess());
        assertEquals("test-model", r.getModelName());
        assertEquals("超时", r.getError());
        assertNull(r.getResponse());
        assertEquals(0, r.getLatencyMs());
    }
}
