package com.springairag.core.i18n;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MessageSourceConfig.
 */
@ExtendWith(MockitoExtension.class)
class MessageSourceConfigTest {

    @Test
    void messageSource_beanIsResourceBundleMessageSource() {
        MessageSourceConfig config = new MessageSourceConfig();
        MessageSource messageSource = config.messageSource();
        assertInstanceOf(ResourceBundleMessageSource.class, messageSource);
    }

    @Test
    void messageSource_setCorrectBasename() {
        ResourceBundleMessageSource messageSource =
            (ResourceBundleMessageSource) new MessageSourceConfig().messageSource();
        assertEquals("messages/messages", messageSource.getBasenameSet().iterator().next());
    }

    @Test
    void messageSource_unknownCodeReturnsCodeAsDefaultMessage() {
        // With useCodeAsDefaultMessage=true, unknown codes return the code itself
        MessageSource messageSource = new MessageSourceConfig().messageSource();
        String result = messageSource.getMessage("unknown.code.here", null, Locale.CHINA);
        assertEquals("unknown.code.here", result);
    }

    @Test
    void localeResolver_beanIsAcceptHeaderLocaleResolver() {
        LocaleResolver localeResolver = new MessageSourceConfig().localeResolver();
        assertInstanceOf(AcceptHeaderLocaleResolver.class, localeResolver);
    }

    @Test
    void localeResolver_supportedLocalesContainsChinaAndEnglish() {
        AcceptHeaderLocaleResolver resolver =
            (AcceptHeaderLocaleResolver) new MessageSourceConfig().localeResolver();
        List<Locale> supported = resolver.getSupportedLocales();
        assertEquals(2, supported.size());
        assertTrue(supported.contains(Locale.CHINA));
        assertTrue(supported.contains(Locale.ENGLISH));
    }

    @Test
    void localeResolver_japaneseIsNotSupported() {
        AcceptHeaderLocaleResolver resolver =
            (AcceptHeaderLocaleResolver) new MessageSourceConfig().localeResolver();
        assertFalse(resolver.getSupportedLocales().contains(Locale.JAPAN));
    }

    @Test
    void localeResolver_frenchIsNotSupported() {
        AcceptHeaderLocaleResolver resolver =
            (AcceptHeaderLocaleResolver) new MessageSourceConfig().localeResolver();
        assertFalse(resolver.getSupportedLocales().contains(Locale.FRENCH));
    }

    @Test
    void localeResolver_resolveLocale_returnsChinaForZhRequest(
            @Mock HttpServletRequest request,
            @Mock java.util.Enumeration<Locale> localesEnum) {
        AcceptHeaderLocaleResolver resolver =
            (AcceptHeaderLocaleResolver) new MessageSourceConfig().localeResolver();
        when(request.getHeader("Accept-Language")).thenReturn("zh-CN");
        when(request.getLocales()).thenReturn(localesEnum);
        when(localesEnum.hasMoreElements()).thenReturn(true);
        when(localesEnum.nextElement()).thenReturn(Locale.CHINA);
        Locale resolved = resolver.resolveLocale(request);
        assertEquals(Locale.CHINA, resolved);
    }

    @Test
    void localeResolver_resolveLocale_returnsEnglishForEnRequest(
            @Mock HttpServletRequest request,
            @Mock java.util.Enumeration<Locale> localesEnum) {
        AcceptHeaderLocaleResolver resolver =
            (AcceptHeaderLocaleResolver) new MessageSourceConfig().localeResolver();
        when(request.getHeader("Accept-Language")).thenReturn("en-US");
        when(request.getLocales()).thenReturn(localesEnum);
        when(localesEnum.hasMoreElements()).thenReturn(true);
        when(localesEnum.nextElement()).thenReturn(Locale.US);
        Locale resolved = resolver.resolveLocale(request);
        assertEquals(Locale.US, resolved);
    }

    @Test
    void localeResolver_resolveLocale_returnsDefaultWhenNoHeader(
            @Mock HttpServletRequest request,
            @Mock java.util.Enumeration<Locale> localesEnum) {
        AcceptHeaderLocaleResolver resolver =
            (AcceptHeaderLocaleResolver) new MessageSourceConfig().localeResolver();
        when(request.getHeader("Accept-Language")).thenReturn(null);
        when(request.getLocales()).thenReturn(localesEnum);
        when(localesEnum.hasMoreElements()).thenReturn(false);
        // Falls back to default locale (Locale.CHINA)
        Locale resolved = resolver.resolveLocale(request);
        assertEquals(Locale.CHINA, resolved);
    }
}
