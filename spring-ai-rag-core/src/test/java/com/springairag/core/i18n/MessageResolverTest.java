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

@DisplayName("MessageResolver 国际化消息解析")
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
    @DisplayName("resolve(code, args) 使用当前 Locale")
    class ResolveWithCurrentLocale {

        @Test
        @DisplayName("无参数消息解析")
        void resolveWithoutArgs() {
            when(messageSource.getMessage("error.rate_limit_exceeded", new Object[0],
                    "error.rate_limit_exceeded", Locale.CHINA))
                    .thenReturn("请求过于频繁，请稍后重试");

            String result = resolver.resolve("error.rate_limit_exceeded");

            assertEquals("请求过于频繁，请稍后重试", result);
        }

        @Test
        @DisplayName("带参数消息解析")
        void resolveWithArgs() {
            when(messageSource.getMessage(eq("error.document_not_found"), eq(new Object[]{"doc-123"}),
                    eq("error.document_not_found"), any()))
                    .thenReturn("文档不存在: doc-123");

            String result = resolver.resolve("error.document_not_found", "doc-123");

            assertEquals("文档不存在: doc-123", result);
        }

        @Test
        @DisplayName("消息代码不存在时返回代码本身")
        void resolveFallbackToCode() {
            when(messageSource.getMessage(eq("unknown.code"), any(), eq("unknown.code"), any()))
                    .thenReturn("unknown.code");

            String result = resolver.resolve("unknown.code");

            assertEquals("unknown.code", result);
        }

        @Test
        @DisplayName("英文 Locale 解析")
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
    @DisplayName("resolve(code, locale, args) 指定 Locale")
    class ResolveWithExplicitLocale {

        @Test
        @DisplayName("指定中文 Locale")
        void resolveWithChineseLocale() {
            when(messageSource.getMessage("error.rate_limit_exceeded", new Object[0],
                    "error.rate_limit_exceeded", Locale.CHINA))
                    .thenReturn("请求过于频繁，请稍后重试");

            String result = resolver.resolve("error.rate_limit_exceeded", Locale.CHINA);

            assertEquals("请求过于频繁，请稍后重试", result);
        }

        @Test
        @DisplayName("指定英文 Locale 带参数")
        void resolveWithEnglishLocaleAndArgs() {
            when(messageSource.getMessage(eq("error.document_not_found"), eq(new Object[]{"doc-456"}),
                    eq("error.document_not_found"), eq(Locale.ENGLISH)))
                    .thenReturn("Document not found: doc-456");

            String result = resolver.resolve("error.document_not_found", Locale.ENGLISH, "doc-456");

            assertEquals("Document not found: doc-456", result);
        }

        @Test
        @DisplayName("当前 Locale 为英文但指定中文 Locale 返回中文")
        void overrideEnglishLocaleWithChinese() {
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            when(messageSource.getMessage("error.rate_limit_exceeded", new Object[0],
                    "error.rate_limit_exceeded", Locale.CHINA))
                    .thenReturn("请求过于频繁，请稍后重试");

            String result = resolver.resolve("error.rate_limit_exceeded", Locale.CHINA);

            assertEquals("请求过于频繁，请稍后重试", result);
        }
    }
}
