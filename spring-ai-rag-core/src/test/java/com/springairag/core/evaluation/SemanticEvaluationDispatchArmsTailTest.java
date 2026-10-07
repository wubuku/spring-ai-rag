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

        // 原来只有 assertNotNull(builder)：builder 是 return 语句上的
        // 新建对象，null 分支之外它必然存在，这条断不出任何东西。
        // 至少断到具体类型 —— 返回 ChatClient.Builder 而不是 null 或
        // 别的什么，这条才有内容（"跳过 options" 的另一半在下一条用例）。
        Assertions.assertInstanceOf(
                org.springframework.ai.chat.client.ChatClient.Builder.class,
                builder);
    }

    /**
     * 反向对照：options 非 null 时 copy() 必须被调到。
     *
     * <p>用 atLeastOnce 而不是 times(1)：{@code copy()} 在一次调用里出现
     * <b>两次</b>，一次是 {@code resolveBuilder} 第 183 行自己的拷贝，
     * 另一次是 Spring AI 的 {@code DefaultChatClientRequestSpec} 构造器
     * 内部再拷一遍。写死精确次数等于把第三方内部实现也钉进我们的契约，
     * 库一改就假红。要断的是"这条分支确实走到了"，不是"走几次"。
     */
    @Test
    void resolveBuilderAppliesDefaultOptionsCopyWhenModelHasThem() throws Exception {
        ChatModel model = mock(ChatModel.class);
        ChatModelRouter router = mock(ChatModelRouter.class);
        when(router.resolveRequired("model-a")).thenReturn(model);
        SemanticEvaluationService service = new SemanticEvaluationService(
                mock(org.springframework.ai.chat.client.ChatClient.Builder.class),
                router, new RagProperties());

        org.springframework.ai.chat.prompt.ChatOptions options =
                org.mockito.Mockito.mock(
                        org.springframework.ai.chat.prompt.ChatOptions.class);
        // copy() 在 mock 上默认返回 null，而 defaultOptions(null) 直接抛
        // IllegalArgumentException —— 反向对照要成立，copy() 必须有返回值。
        org.mockito.Mockito.when(options.copy()).thenReturn(options);
        when(model.getDefaultOptions()).thenReturn(options);

        Method method = SemanticEvaluationService.class.getDeclaredMethod(
                "resolveBuilder", String.class);
        method.setAccessible(true);
        Object builder = method.invoke(service, "model-a");

        Assertions.assertInstanceOf(
                org.springframework.ai.chat.client.ChatClient.Builder.class,
                builder);
        org.mockito.Mockito.verify(options, org.mockito.Mockito.atLeastOnce())
                .copy();
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
