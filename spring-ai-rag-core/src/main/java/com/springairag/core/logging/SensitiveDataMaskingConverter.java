package com.springairag.core.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Logback MessageConverter that masks sensitive data in log messages.
 * Configured as a Logback converter to apply regex-based replacements
 * for common sensitive patterns (passwords, API keys, tokens, etc.).
 *
 * <p>Supported patterns:</p>
 * <ul>
 *   <li>URL query parameters: ?password=xxx, ?apiKey=xxx, ?token=xxx, ?secret=xxx</li>
 *   <li>JSON fields: "password":"xxx", "apiKey":"xxx", "token":"xxx", "secret":"xxx"</li>
 *   <li>Key-value pairs: password=xxx, api_key=xxx, access_token=xxx</li>
 *   <li>Authorization headers: Bearer xxx, Basic xxx</li>
 * </ul>
 *
 * <p>Enable in logback-spring.xml:</p>
 * <pre>{@code
 * <converter converterClass="com.springairag.core.logging.SensitiveDataMaskingConverter"/>
 * }</pre>
 */
public class SensitiveDataMaskingConverter extends MessageConverter {

    private static final String MASK = "***REDACTED***";

    /**
     * Patterns applied in order. Each pattern matches a sensitive field
     * and captures the value portion for replacement.
     */
    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            // JSON field patterns: "password":"value" or 'password':'value'
            Pattern.compile("\"(password|passwd|pwd|pass)\"\\s*:\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"(apiKey|api_key|apikey|api-key)\"\\s*:\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"(token|accessToken|access_token|refreshToken|refresh_token|authToken)\"\\s*:\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"(secret|secretKey|secret_key|clientSecret)\"\\s*:\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"(authorization|auth|credentials)\"\\s*:\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"(privateKey|private_key|publicKey|public_key)\"\\s*:\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"(ssn|socialSecurityNumber|creditCard|credit_card|cardNumber|cvv)\"\\s*:\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE),

            // JSON with backslash-escaped quotes: \"password\":\"value\"
            Pattern.compile("\\\\\"(password|passwd|pwd|pass)\\\\\"\\s*:\\s*\\\\\"[^\\\\\"]*\\\\\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\\\\"(apiKey|api_key|apikey|api-key)\\\\\"\\s*:\\s*\\\\\"[^\\\\\"]*\\\\\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\\\\"(token|accessToken|access_token|refreshToken|refresh_token|authToken)\\\\\"\\s*:\\s*\\\\\"[^\\\\\"]*\\\\\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\\\\"(secret|secretKey|secret_key|clientSecret)\\\\\"\\s*:\\s*\\\\\"[^\\\\\"]*\\\\\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\\\\"(authorization|auth|credentials)\\\\\"\\s*:\\s*\\\\\"[^\\\\\"]*\\\\\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\\\\"(privateKey|private_key|publicKey|public_key)\\\\\"\\s*:\\s*\\\\\"[^\\\\\"]*\\\\\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\\\\"(ssn|socialSecurityNumber|creditCard|credit_card|cardNumber|cvv)\\\\\"\\s*:\\s*\\\\\"[^\\\\\"]*\\\\\"", Pattern.CASE_INSENSITIVE),

            // URL query parameter patterns
            Pattern.compile("(\\?|&)(password|passwd|pwd|pass)=[^&\\s\"']*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\?|&)(apiKey|api_key|apikey|api-key)=[^&\\s\"']*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\?|&)(token|accessToken|access_token|refreshToken|refresh_token)=[^&\\s\"']*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\?|&)(secret|secretKey|secret_key|clientSecret)=[^&\\s\"']*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\?|&)(authorization|auth)=[^&\\s\"']*", Pattern.CASE_INSENSITIVE),

            // Key-value pair patterns (non-JSON, non-URL), bare value
            Pattern.compile("\\b(password|passwd|pwd|pass)\\s*=\\s*[^\\s\"',;}]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(apiKey|api_key|apikey|api-key)\\s*=\\s*[^\\s\"',;}]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(token|accessToken|access_token)\\s*=\\s*[^\\s\"',;}]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(secret|secretKey|secret_key)\\s*=\\s*[^\\s\"',;}]+", Pattern.CASE_INSENSITIVE),

            // SQL single-quoted: password='secret'
            Pattern.compile("\\b(password|passwd|pwd|pass)\\s*=\\s*'[^']*'", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(apiKey|api_key|apikey|api-key)\\s*=\\s*'[^']*'", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(token|accessToken|access_token)\\s*=\\s*'[^']*'", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(secret|secretKey|secret_key)\\s*=\\s*'[^']*'", Pattern.CASE_INSENSITIVE),

            // SQL double-quoted: password="secret"
            Pattern.compile("\\b(password|passwd|pwd|pass)\\s*=\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(apiKey|api_key|apikey|api-key)\\s*=\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(token|accessToken|access_token)\\s*=\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(secret|secretKey|secret_key)\\s*=\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE),

            // Authorization header patterns
            Pattern.compile("(Bearer\\s+)[A-Za-z0-9\\-_\\.]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(Basic\\s+)[A-Za-z0-9\\+/=]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(bearer\\s+)[A-Za-z0-9\\-_\\.]+"),
            Pattern.compile("(basic\\s+)[A-Za-z0-9\\+/=]+"),

            // AWS keys
            Pattern.compile("(AKIA[0-9A-Z]{16})"),
            // Generic "key" patterns at end of strings (key=value without known prefix)
            Pattern.compile("\\b(key|private_key)\\s*[:=]\\s*[\"']?[A-Za-z0-9\\+/=\\-_]{20,}[\"']?", Pattern.CASE_INSENSITIVE),
            // Generic key=VALUE where VALUE contains sensitive-looking patterns (API keys, tokens)
            Pattern.compile("\\b(key|private_key)\\s*=\\s*(sk-|ak-|token-|bearer-|eyJ)[A-Za-z0-9\\-_\\.+=/]{10,}", Pattern.CASE_INSENSITIVE)
    );

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message == null || message.isEmpty()) {
            return message;
        }
        return maskSensitiveData(message);
    }

    /**
     * Applies all sensitive data masking patterns to the given message.
     * Patterns are applied iteratively to catch overlapping patterns.
     */
    public static String maskSensitiveData(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String result = message;
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            result = pattern.matcher(result).replaceAll(MASK);
        }
        return result;
    }

    /**
     * Reveals only the type of sensitive data without exposing the value.
     * Useful for debugging when you need to know something sensitive was present.
     */
    public static String maskSensitiveDataKeepType(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            java.util.regex.Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                return message.substring(0, matcher.start()) + "[SENSITIVE:" + getSensitiveType(pattern) + "]" +
                       message.substring(matcher.end());
            }
        }
        return message;
    }

    private static String getSensitiveType(Pattern pattern) {
        String p = pattern.pattern();
        if (p.contains("password") || p.contains("passwd") || p.contains("pwd")) return "PASSWORD";
        if (p.contains("apiKey") || p.contains("api_key") || p.contains("apikey")) return "API_KEY";
        if (p.contains("token") || p.contains("Token")) return "TOKEN";
        if (p.contains("secret") || p.contains("secretKey")) return "SECRET";
        if (p.contains("authorization") || p.contains("auth")) return "AUTH";
        if (p.contains("Bearer")) return "BEARER_TOKEN";
        if (p.contains("Basic")) return "BASIC_AUTH";
        if (p.contains("AKIA")) return "AWS_KEY";
        return "SENSITIVE";
    }
}
