package com.springairag.core.evaluation;

import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 语义评估服务助手长尾（Batch 740，JaCoCo 驱动）：safeError 对
 * null/空白回退固定文案、经掩码器透传与 500 截断（183-195）。
 */
class SemanticEvaluationSafeErrorTailTest {

    private String safeError(String value) throws Exception {
        SemanticEvaluationService service = new SemanticEvaluationService(
                Mockito.mock(ChatClient.Builder.class), new RagProperties());
        Method method = SemanticEvaluationService.class
                .getDeclaredMethod("safeError", String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, value);
    }

    @Test
    void nullOrBlankValueFallsBackToFixedMessage() throws Exception {
        assertEquals("Semantic evaluator failed", safeError(null));
        assertEquals("Semantic evaluator failed", safeError("   "));
    }

    @Test
    void plainValuePassesThroughMasker() throws Exception {
        assertEquals("evaluator down", safeError("evaluator down"));
    }

    @Test
    void longValueIsTruncatedTo500Chars() throws Exception {
        String longMessage = "e".repeat(800);
        String safe = safeError(longMessage);
        assertEquals(500, safe.length());
        assertEquals("e".repeat(500), safe);
    }
}
