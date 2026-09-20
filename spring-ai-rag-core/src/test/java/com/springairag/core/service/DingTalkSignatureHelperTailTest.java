package com.springairag.core.service;

import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DingTalk 签名与重试解析长尾（Batch 542，JaCoCo 驱动）：Retry-
 * After 头解析（null/空白/数字/负数/垃圾）、buildWebhookUrl 加签
 * 与免签两分支、computeSignature 确定性、escapeJson 转义矩阵。
 */
class DingTalkSignatureHelperTailTest {

    private DingTalkNotificationService service() {
        // Builder 链式调用（requestFactory/connectTimeout/readTimeout/
        // build）用 RETURNS_SELF 自动返回自身，build 单独返回 mock。
        var builder = mock(RestTemplateBuilder.class,
                org.mockito.Mockito.RETURNS_SELF);
        when(builder.build()).thenReturn(mock(RestTemplate.class));
        var config = new NotificationConfig();
        config.setEnabled(true);
        return new DingTalkNotificationService(config, builder);
    }

    private Object invokeStatic(String name, Class<?>[] params,
                                Object... args) throws Exception {
        var method = DingTalkNotificationService.class
                .getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    @Test
    void retryAfterParsesSecondsAndToleratesGarbage() throws Exception {
        var params = new Class<?>[]{String.class};
        assertNull(invokeStatic("retryAfter", params, new Object[]{null}));
        assertNull(invokeStatic("retryAfter", params, "   "));
        assertEquals(Duration.ofSeconds(30),
                invokeStatic("retryAfter", params, " 30 "));
        assertEquals(Duration.ofSeconds(0),
                invokeStatic("retryAfter", params, "-5"));
        assertNull(invokeStatic("retryAfter", params, "soon"));
    }

    @Test
    void buildWebhookUrlAppendsSignatureOnlyWithSecret() {
        DingTalkNotificationService service = service();

        NotificationConfig.DingTalkConfig plain =
                new NotificationConfig.DingTalkConfig();
        plain.setWebhookUrl(
                "https://oapi.dingtalk.com/robot/send?access_token=t");
        String unsigned = service.buildWebhookUrl(plain);
        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=t",
                unsigned);

        NotificationConfig.DingTalkConfig secured =
                new NotificationConfig.DingTalkConfig();
        secured.setWebhookUrl(
                "https://oapi.dingtalk.com/robot/send?access_token=t");
        secured.setSecret("secret-key");
        String signed = service.buildWebhookUrl(secured);
        assertTrue(signed.startsWith(unsigned + "&timestamp="));
        assertTrue(signed.contains("&sign="));
        assertFalse(signed.endsWith("&sign="));
    }

    @Test
    void computeSignatureIsDeterministicBase64() {
        DingTalkNotificationService service = service();

        String first = service.computeSignature("secret-key", 1_700_000_000L);
        String second = service.computeSignature("secret-key", 1_700_000_000L);

        assertEquals(first, second);
        assertFalse(first.isEmpty());
    }

    @Test
    void escapeJsonEscapesQuotesBackslashesAndLineBreaks() throws Exception {
        var escape = DingTalkNotificationService.class
                .getDeclaredMethod("escapeJson", String.class);
        escape.setAccessible(true);
        DingTalkNotificationService serviceInstance = service();

        assertEquals("", escape.invoke(serviceInstance, new Object[]{null}));

        String input = "line\nbreak \"quoted\" back\\slash";
        String expected = "line\\nbreak \\\"quoted\\\" back\\\\slash";
        assertEquals(expected, escape.invoke(serviceInstance, input));
    }
}
