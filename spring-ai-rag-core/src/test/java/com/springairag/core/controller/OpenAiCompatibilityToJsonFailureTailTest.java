package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * OpenAI 兼容层 toJson 序列化失败包装长尾（Batch 508，JaCoCo 驱
 * 动）：不可序列化对象（自引用 Map）→ IllegalStateException，防止
 * 静默吞掉序列化故障；可序列化对象原样透出 JSON。
 */
class OpenAiCompatibilityToJsonFailureTailTest {

    private String invokeToJson(Object value) throws Exception {
        OpenAiCompatibilityController controller = new OpenAiCompatibilityController(
                mock(com.springairag.core.openai.OpenAiModelAliasRegistry.class),
                mock(com.springairag.core.openai.OpenAiChatRequestMapper.class),
                mock(com.springairag.core.chat.ChatExecutionService.class),
                new ObjectMapper());
        Method method = OpenAiCompatibilityController.class
                .getDeclaredMethod("toJson", Object.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(controller, value);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    @Test
    void selfReferencingMapFailsSerializationWithIllegalState() {
        Map<String, Object> selfRef = new HashMap<>();
        selfRef.put("self", selfRef);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> invokeToJson(selfRef));
        assertTrue(error.getMessage().contains("Failed to serialize"),
                "应报序列化失败: " + error.getMessage());
    }

    @Test
    void serializableValueStillPassesThroughToJson() throws Exception {
        String json = invokeToJson(Map.of("ok", 1));
        assertTrue(json.contains("ok"));
    }
}
