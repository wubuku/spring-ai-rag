package com.springairag.core.i18n;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("MessageResolver i18n Message Resolution")
class MessageResolverTest {

    private MessageSource messageSource;
    private MessageResolver resolver;

    @BeforeEach
    void setUp() {
        messageSource = mock(MessageSource.class);
        resolver = new MessageResolver(messageSource);
        LocaleContextHolder.setLocale(Locale.CHINA);
    }

    @Nested
    @DisplayName("resolve(code, args) Uses Current Locale")
    class ResolveWithCurrentLocale {

        @Test
        @DisplayName("No-argument message resolution")
        void resolveWithoutArgs() {
            when(messageSource.getMessage("error.rate_limit_exceeded", new Object[0],
                    "error.rate_limit_exceeded", Locale.CHINA))
                    .thenReturn("Rate limit exceeded, please retry later");

            String result = resolver.resolve("error.rate_limit_exceeded");

            assertEquals("Rate limit exceeded, please retry later", result);
        }

        @Test
        @DisplayName("Message resolution with arguments")
        void resolveWithArgs() {
            when(messageSource.getMessage(eq("error.document_not_found"), eq(new Object[]{"doc-123"}),
                    eq("error.document_not_found"), any()))
                    .thenReturn("Document not found: doc-123");

            String result = resolver.resolve("error.document_not_found", "doc-123");

            assertEquals("Document not found: doc-123", result);
        }

        @Test
        @DisplayName("Returns code itself when message code does not exist")
        void resolveFallbackToCode() {
            when(messageSource.getMessage(eq("unknown.code"), any(), eq("unknown.code"), any()))
                    .thenReturn("unknown.code");

            String result = resolver.resolve("unknown.code");

            assertEquals("unknown.code", result);
        }

        @Test
        @DisplayName("English locale resolution")
        void resolveWithEnglishLocale() {
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            when(messageSource.getMessage("error.rate_limit_exceeded", new Object[0],
                    "error.rate_limit_exceeded", Locale.ENGLISH))
                    .thenReturn("Rate limit exceeded");

            String result = resolver.resolve("error.rate_limit_exceeded");

            assertEquals("Rate limit exceeded", result);
        }
    }

    @Nested
    @DisplayName("resolve(code, locale, args) Explicit Locale")
    class ResolveWithExplicitLocale {

        @Test
        @DisplayName("Specifies Chinese locale")
        void resolveWithChineseLocale() {
            when(messageSource.getMessage("error.rate_limit_exceeded", new Object[0],
                    "error.rate_limit_exceeded", Locale.CHINA))
                    .thenReturn("Rate limit exceeded, please retry later");

            String result = resolver.resolve("error.rate_limit_exceeded", Locale.CHINA);

            assertEquals("Rate limit exceeded, please retry later", result);
        }

        @Test
        @DisplayName("Specifies English locale with arguments")
        void resolveWithEnglishLocaleAndArgs() {
            when(messageSource.getMessage(eq("error.document_not_found"), eq(new Object[]{"doc-456"}),
                    eq("error.document_not_found"), eq(Locale.ENGLISH)))
                    .thenReturn("Document not found: doc-456");

            String result = resolver.resolve("error.document_not_found", Locale.ENGLISH, "doc-456");

            assertEquals("Document not found: doc-456", result);
        }

        @Test
        @DisplayName("Returns Chinese when current locale is English but Chinese is specified")
        void overrideEnglishLocaleWithChinese() {
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            when(messageSource.getMessage("error.rate_limit_exceeded", new Object[0],
                    "error.rate_limit_exceeded", Locale.CHINA))
                    .thenReturn("Rate limit exceeded, please retry later");

            String result = resolver.resolve("error.rate_limit_exceeded", Locale.CHINA);

            assertEquals("Rate limit exceeded, please retry later", result);
        }
    }
}
