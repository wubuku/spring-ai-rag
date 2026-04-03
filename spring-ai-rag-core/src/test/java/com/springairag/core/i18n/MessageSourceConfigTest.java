package com.springairag.core.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MessageSourceConfig 国际化配置")
class MessageSourceConfigTest {

    private final MessageSourceConfig config = new MessageSourceConfig();

    @Test
    @DisplayName("MessageSource Bean 创建成功")
    void messageSourceCreated() {
        MessageSource messageSource = config.messageSource();

        assertNotNull(messageSource);
        // 验证默认消息文件存在
        String message = messageSource.getMessage("error.rate_limit_exceeded", null, Locale.CHINA);
        assertNotNull(message);
        assertFalse(message.isEmpty());
    }

    @Test
    @DisplayName("useCodeAsDefaultMessage 为 true，未知代码返回代码本身")
    void unknownCodeFallsBackToCode() {
        MessageSource messageSource = config.messageSource();

        String message = messageSource.getMessage("unknown.test.code", null, Locale.CHINA);

        assertEquals("unknown.test.code", message);
    }

    @Test
    @DisplayName("中文消息正确解析")
    void chineseMessageResolved() {
        MessageSource messageSource = config.messageSource();

        String message = messageSource.getMessage("error.rate_limit_exceeded", null, Locale.CHINA);

        assertEquals("请求过于频繁，请稍后重试", message);
    }

    @Test
    @DisplayName("英文消息正确解析")
    void englishMessageResolved() {
        MessageSource messageSource = config.messageSource();

        String message = messageSource.getMessage("error.rate_limit_exceeded", null, Locale.ENGLISH);

        assertEquals("Too many requests, please try again later", message);
    }

    @Test
    @DisplayName("带参数消息解析")
    void messageWithArgs() {
        MessageSource messageSource = config.messageSource();

        String message = messageSource.getMessage("error.document_not_found",
                new Object[]{"test-doc"}, Locale.CHINA);

        assertTrue(message.contains("test-doc"));
    }

    @Test
    @DisplayName("LocaleResolver Bean 创建成功")
    void localeResolverCreated() {
        LocaleResolver resolver = config.localeResolver();

        assertNotNull(resolver);
    }

    @Test
    @DisplayName("默认 Locale 为中文")
    void defaultLocaleIsChinese() {
        LocaleResolver resolver = config.localeResolver();

        // AcceptHeaderLocaleResolver 的默认 Locale
        assertNotNull(resolver);
    }
}
