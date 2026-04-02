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
 * 国际化配置
 *
 * <p>提供多语言错误消息支持。通过 Accept-Language 请求头自动选择语言，
 * 默认中文（zh_CN），支持中文和英文切换。
 *
 * <p>消息文件位于 classpath:messages/ 目录：
 * <ul>
 *   <li>messages.properties — 默认（中文）</li>
 *   <li>messages_zh_CN.properties — 中文</li>
 *   <li>messages_en.properties — 英文</li>
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
