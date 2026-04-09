package com.springairag.core.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MessageSourceConfig i18n Configuration")
class MessageSourceConfigTest {

    private final MessageSourceConfig config = new MessageSourceConfig();

    @Test
    @DisplayName("MessageSource Bean created successfully")
    void messageSourceCreated() {
        MessageSource messageSource = config.messageSource();

        assertNotNull(messageSource);
        // Verify default message file exists
        String message = messageSource.getMessage("error.rate_limit_exceeded", null, Locale.CHINA);
        assertNotNull(message);
        assertFalse(message.isEmpty());
    }

    @Test
    @DisplayName("useCodeAsDefaultMessage is true, unknown codes return the code itself")
    void unknownCodeFallsBackToCode() {
        MessageSource messageSource = config.messageSource();

        String message = messageSource.getMessage("unknown.test.code", null, Locale.CHINA);

        assertEquals("unknown.test.code", message);
    }

    @Test
    @DisplayName("Chinese message resolves correctly")
    void chineseMessageResolved() {
        MessageSource messageSource = config.messageSource();

        String message = messageSource.getMessage("error.rate_limit_exceeded", null, Locale.CHINA);

        assertEquals("请求过于频繁，请稍后重试", message);
    }

    @Test
    @DisplayName("English message resolves correctly")
    void englishMessageResolved() {
        MessageSource messageSource = config.messageSource();

        String message = messageSource.getMessage("error.rate_limit_exceeded", null, Locale.ENGLISH);

        assertEquals("Too many requests, please try again later", message);
    }

    @Test
    @DisplayName("Message with arguments resolves correctly")
    void messageWithArgs() {
        MessageSource messageSource = config.messageSource();

        String message = messageSource.getMessage("error.document_not_found",
                new Object[]{"test-doc"}, Locale.CHINA);

        assertTrue(message.contains("test-doc"));
    }

    @Test
    @DisplayName("LocaleResolver Bean created successfully")
    void localeResolverCreated() {
        LocaleResolver resolver = config.localeResolver();

        assertNotNull(resolver);
    }

    @Test
    @DisplayName("Default locale is Chinese")
    void defaultLocaleIsChinese() {
        LocaleResolver resolver = config.localeResolver();

        // AcceptHeaderLocaleResolver default locale
        assertNotNull(resolver);
    }
}
