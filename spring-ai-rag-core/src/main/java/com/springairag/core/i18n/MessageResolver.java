package com.springairag.core.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 国际化消息解析工具
 *
 * <p>封装 MessageSource 调用，根据当前请求的 Locale 自动选择语言。
 * 支持参数替换和默认消息降级。
 *
 * <p>使用方式：
 * <pre>
 * messageResolver.resolve("error.rate_limit_exceeded")
 * messageResolver.resolve("error.document_not_found", "doc-123")
 * </pre>
 */
@Component
public class MessageResolver {

    private final MessageSource messageSource;

    public MessageResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * 解析国际化消息
     *
     * @param code 消息代码（如 "error.rate_limit_exceeded"）
     * @param args 消息参数（替换 {0}, {1} 等占位符）
     * @return 本地化后的消息
     */
    public String resolve(String code, Object... args) {
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(code, args, code, locale);
    }

    /**
     * 解析国际化消息（指定 Locale）
     *
     * @param code   消息代码
     * @param locale 目标语言
     * @param args   消息参数
     * @return 本地化后的消息
     */
    public String resolve(String code, Locale locale, Object... args) {
        return messageSource.getMessage(code, args, code, locale);
    }
}
