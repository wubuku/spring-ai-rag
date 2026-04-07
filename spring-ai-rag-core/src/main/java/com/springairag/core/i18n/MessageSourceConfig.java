package com.springairag.core.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * Internationalization configuration.
 *
 * <p>Provides multi-language error message support. Auto-selects language via Accept-Language header,
 * defaulting to zh_CN with support for Chinese and English.
 *
 * <p>Message files are located in classpath:messages/ directory:
 * <ul>
 *   <li>messages.properties — default (Chinese)</li>
 *   <li>messages_zh_CN.properties — Chinese</li>
 *   <li>messages_en.properties — English</li>
 * </ul>
 */
@Configuration
public class MessageSourceConfig {

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages/messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(true);
        return messageSource;
    }

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.CHINA);
        resolver.setSupportedLocales(List.of(Locale.CHINA, Locale.ENGLISH));
        return resolver;
    }
}
