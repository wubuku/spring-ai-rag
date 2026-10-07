package com.springairag.core.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MaskingLogstashEncoder.
 * Verifies that sensitive data is masked before JSON encoding.
 *
 * <p>Batch 953：这套用例原先清一色是「{@code assertNotNull(result)} +
 * 「{@code assertFalse(json.contains(secret))}」。后者有一个致命弱点——
 * 只要编码器吐出空串或半截垃圾，它照样绿。Batch 948 在 SSE 那条链上
 * 已经吃过一次亏：载荷被 ISO-8859-1 写坏时，「不含某个字符串」这种
 * 断言只会更轻松地通过。
 *
 * <p>所以每条脱敏用例现在都配一条<strong>正向</strong>断言：输出必须
 * 是一份能解析的日志事件，且密钥所在的那句话还剩下、掩码标记确实
 * 出现了。空/空串消息那两条也从「字节数组非 null」升级成「事件信封
 * 完整」。
 */
class MaskingLogstashEncoderTest {

    /** 与 {@link SensitiveDataMaskingConverter} 里的 MASK 一致。 */
    private static final String MASK = "***REDACTED***";

    /**
     * 脱敏模式认的是<strong>结构</strong>（字段名 + 引号里的任意值），不是值的真假，
     * 所以这批夹具刻意用一眼就假的字符串：仓库的 added-line 密钥扫描会拦下
     * {@code sk-} 加长串这类形态，而这条用例原先用的恰恰是那个形态，
     * 每改动一次这个文件就踩它一次。别"顺手"把它换回更像真 key 的样子——
     * 值像不像，跟模式能不能匹配无关。
     */
    private static final String FAKE_API_KEY = "not-a-real-credential";
    private static final String FAKE_BEARER = "not.a.real.jwt.value";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MaskingLogstashEncoder encoder;

    @BeforeEach
    void setUp() {
        encoder = new MaskingLogstashEncoder();
        encoder.start();
    }

    /**
     * 解析编码结果，并断言它确实是一份完整的日志事件。
     *
     * <p>信封校验是所有「不该出现某物」断言的前提：没有它，
     * 一个只吐空串的编码器能让整套脱敏用例全绿。
     */
    private Map<String, Object> decodeEvent(byte[] encoded) {
        assertNotNull(encoded, "编码结果不应为 null");
        assertTrue(encoded.length > 0, "编码结果不应为空");
        Map<String, Object> event;
        try {
            event = MAPPER.readValue(encoded, Map.class);
        } catch (Exception e) {
            throw new AssertionError(
                    "编码结果不是合法 JSON：" + new String(encoded), e);
        }
        assertEquals("INFO", event.get("level"));
        assertEquals("test.logger", event.get("logger_name"));
        assertEquals("main", event.get("thread_name"));
        return event;
    }

    private String decodeMessage(byte[] encoded) {
        return (String) decodeEvent(encoded).get("message");
    }

    @Test
    void encode_masksPasswordInMessage() throws Exception {
        ILoggingEvent event = mockLoggingEvent(
            "User login: {\"username\":\"admin\",\"password\":\"secret123\"}"
        );

        String message = decodeMessage(encoder.encode(event));

        assertFalse(message.contains("secret123"),
            "Sensitive password should be masked in JSON output");
        // 正向对照：句子其余部分还在，掩码标记确实替换了进去。
        // 没有这两条，"输出是空的"也能满足上面的 assertFalse。
        assertTrue(message.contains("User login:"), message);
        assertTrue(message.contains("***REDACTED***"), message);
    }

    @Test
    void encode_masksApiKeyInMessage() throws Exception {
        ILoggingEvent event = mockLoggingEvent(
            "API call with key: {\"apiKey\":\"" + FAKE_API_KEY + "\"}"
        );

        String message = decodeMessage(encoder.encode(event));

        assertFalse(message.contains(FAKE_API_KEY),
            "Sensitive apiKey should be masked in JSON output");
        assertTrue(message.contains("API call with key:"), message);
        assertTrue(message.contains(MASK), message);
    }

    @Test
    void encode_masksTokenInMessage() throws Exception {
        ILoggingEvent event = mockLoggingEvent(
            "Request token: access_token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
        );

        String message = decodeMessage(encoder.encode(event));

        assertFalse(message.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"),
            "Sensitive token should be masked in JSON output");
        assertTrue(message.contains("Request token:"), message);
        assertTrue(message.contains(MASK), message);
    }

    @Test
    void encode_preservesNonSensitiveData() throws Exception {
        String safeMessage = "GET /api/users/123 - 200 OK - Response time: 45ms";
        ILoggingEvent event = mockLoggingEvent(safeMessage);

        String message = decodeMessage(encoder.encode(event));

        // 原来写的是 assertTrue(json.contains("200 OK") || json.contains("ms"))，
        // 这个 || 只要命中一半就算过。整条消息原样保留才是真的断言。
        assertEquals(safeMessage, message);
    }

    @Test
    void encode_noChangeWhenNoSensitiveData() throws Exception {
        String safeMessage = "Normal log: user admin logged in at 10:30";
        ILoggingEvent event = mockLoggingEvent(safeMessage);

        String message = decodeMessage(encoder.encode(event));

        assertEquals(safeMessage, message);
        assertFalse(message.contains(MASK), "无敏感数据时不应出现掩码：" + message);
    }

    @Test
    void encode_masksBearerToken() throws Exception {
        ILoggingEvent event = mockLoggingEvent(
            "Authorization: Bearer " + FAKE_BEARER
        );

        String message = decodeMessage(encoder.encode(event));

        assertFalse(message.contains(FAKE_BEARER),
            "Bearer token should be masked");
        assertTrue(message.contains("Authorization:"), message);
        assertTrue(message.contains(MASK), message);
    }

    @Test
    void encode_masksAwsAccessKey() throws Exception {
        ILoggingEvent event = mockLoggingEvent(
            "AWS credentials: AKIAIOSFODNN7EXAMPLE"
        );

        String message = decodeMessage(encoder.encode(event));

        assertFalse(message.contains("AKIAIOSFODNN7EXAMPLE"),
            "AWS access key should be masked");
        assertTrue(message.contains("AWS credentials:"), message);
        assertTrue(message.contains(MASK), message);
    }

    @Test
    void encode_emptyMessage() throws Exception {
        ILoggingEvent event = mockLoggingEvent("");

        Map<String, Object> decoded = decodeEvent(encoder.encode(event));

        // 走的是 encode() 里「消息为空 → 直接 super.encode」那条分支，
        // 但事件信封仍必须完整。原先只断言字节数组非 null，
        // 一个只 new byte[0] 的实现能过。
        assertEquals("", decoded.get("message"));
    }

    @Test
    void encode_nullMessage() throws Exception {
        ILoggingEvent event = mockLoggingEvent(null);

        Map<String, Object> decoded = decodeEvent(encoder.encode(event));

        // null 消息：键要么是显式 null，要么整个不出现，两种都算通过，
        // 但 level/logger/thread 三个字段一个都不能少。
        assertNull(decoded.get("message"));
    }

    /**
     * Creates a mock ILoggingEvent that returns the given message
     * for getFormattedMessage() and getMessage().
     */
    @SuppressWarnings("unchecked")
    private ILoggingEvent mockLoggingEvent(String message) throws Exception {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn(message);
        when(event.getMessage()).thenReturn(message);
        when(event.getLoggerName()).thenReturn("test.logger");
        when(event.getLevel()).thenReturn(Level.INFO);
        when(event.getThreadName()).thenReturn("main");
        when(event.getInstant()).thenReturn(Instant.now());
        return event;
    }
}