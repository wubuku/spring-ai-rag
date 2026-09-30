package com.springairag.core.evaluation;

import com.springairag.api.dto.SemanticEvaluationRequest;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 语义评估服务分派长尾（Batch 746，JaCoCo 驱动）：null evaluator
 * 归一为空串后触发拒绝（57）、router 注入时 null options 跳过
 * defaultOptions（183）、null context 归一为空串（163）。
 */
class SemanticEvaluationDispatchArmsTailTest {

    @Test
    void nullEvaluatorNormalizesToEmptyAndIsRejected() {
        SemanticEvaluationService service = new SemanticEvaluationService(
                mock(org.springframework.ai.chat.client.ChatClient.Builder.class),
                new RagProperties());

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.evaluate(new SemanticEvaluationRequest(
                        null, "q", "c", "a", "model-a")));
    }

    @Test
    void resolveBuilderWithRouterSkipsNullOptions() throws Exception {
        ChatModel model = mock(ChatModel.class);
        // getDefaultOptions() 返回 null → 跳过 defaultOptions 分支（183）。
        when(model.getDefaultOptions()).thenReturn(null);
        ChatModelRouter router = mock(ChatModelRouter.class);
        when(router.resolveRequired("model-a")).thenReturn(model);
        SemanticEvaluationService service = new SemanticEvaluationService(
                mock(org.springframework.ai.chat.client.ChatClient.Builder.class),
                router, new RagProperties());

        Method method = SemanticEvaluationService.class.getDeclaredMethod(
                "resolveBuilder", String.class);
        method.setAccessible(true);
        Object builder = method.invoke(service, "model-a");

        Assertions.assertNotNull(builder);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createEvaluationRequestNormalizesNullContext() throws Exception {
        SemanticEvaluationService service = new SemanticEvaluationService(
                mock(org.springframework.ai.chat.client.ChatClient.Builder.class),
                new RagProperties());
        Method method = SemanticEvaluationService.class.getDeclaredMethod(
                "createEvaluationRequest", Class.class,
                SemanticEvaluationRequest.class);
        method.setAccessible(true);
        Class<?> requestType = Class.forName(
                "org.springframework.ai.evaluation.EvaluationRequest");
        SemanticEvaluationRequest request = new SemanticEvaluationRequest(
                "RELEVANCY", "query", null, "answer", "model-a");

        Object evaluationRequest = method.invoke(service, requestType, request);

        Assertions.assertEquals("",
                ((org.springframework.ai.evaluation.EvaluationRequest)
                        evaluationRequest).getDataList().getFirst().getText());
    }

}
