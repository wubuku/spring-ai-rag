package com.springairag.core.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Internationalized message resolution utility
 *
 * <p>Wraps MessageSource calls, automatically selecting language based on the current request Locale.
 * Supports parameter substitution and default message fallback.
 *
 * <p>Usage:
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
     * Resolve internationalized message
     *
     * @param code message code (e.g., "error.rate_limit_exceeded")
     * @param args message arguments (replaces {0}, {1}, etc. placeholders)
     * @return localized message
     */
    public String resolve(String code, Object... args) {
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(code, args, code, locale);
    }

    /**
     * Resolve internationalized message (with specified Locale)
     *
     * @param code   message code
     * @param locale target locale
     * @param args   message arguments
     * @return localized message
     */
    public String resolve(String code, Locale locale, Object... args) {
        return messageSource.getMessage(code, args, code, locale);
    }
}
