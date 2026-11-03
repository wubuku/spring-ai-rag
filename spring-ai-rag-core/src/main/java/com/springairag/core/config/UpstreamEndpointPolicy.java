package com.springairag.core.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * 上游模型端点（base-url）的启动期校验与归一化。
 *
 * <p>Spring AI 会在 base-url 后自动追加 {@code /v1/...}，因此配置里带 {@code /v1}
 * 会得到 {@code /v1/v1/...}。这里统一去掉末尾的 {@code /} 和 {@code /v1} 并输出
 * WARN，避免聊天、embedding、rerank 三条路径行为不一致。
 *
 * <p>安全默认：明文 {@code http} 只允许回环地址；访问远程主机必须显式设置
 * {@code rag.security.allow-insecure-upstream=true}，否则启动失败并指出具体配置项。
 */
final class UpstreamEndpointPolicy {

    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\$\\{[^}]*}");
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(UpstreamEndpointPolicy.class);

    private UpstreamEndpointPolicy() {
    }

    /**
     * 校验并归一化 base-url；空白值原样返回（是否必填由调用方决定）。
     *
     * @param propertyKey     配置项名称，用于错误消息
     * @param baseUrl         原始配置值
     * @param allowInsecureHttp 是否允许对远程主机使用明文 http
     * @return 去掉末尾 {@code /} 与 {@code /v1} 的 base-url
     */
    static String normalizeAndValidate(String propertyKey,
                                       String baseUrl,
                                       boolean allowInsecureHttp) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return baseUrl;
        }
        String value = baseUrl.trim();
        if (UNRESOLVED_PLACEHOLDER.matcher(value).find()) {
            throw new IllegalStateException(
                    propertyKey + " contains an unresolved placeholder: '" + value
                            + "'. Set the referenced environment variable or remove the placeholder.");
        }

        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(
                    propertyKey + " is not a valid URL: '" + value + "'", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalStateException(
                    propertyKey + " must be an absolute http(s) URL including the scheme: '"
                            + value + "'");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException(
                    propertyKey + " has no host: '" + value + "'");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalStateException(
                    propertyKey + " must not embed credentials in the URL: '" + value + "'");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException(
                    propertyKey + " must not contain a query or fragment: '" + value + "'");
        }
        if (scheme.equalsIgnoreCase("http")
                && !isLoopbackHost(uri.getHost())
                && !allowInsecureHttp) {
            throw new IllegalStateException(
                    propertyKey + " uses plaintext http for remote host '" + uri.getHost()
                            + "'. Use https, or set rag.security.allow-insecure-upstream=true "
                            + "only for a trusted private network.");
        }

        String normalized = stripTrailingSlash(value);
        String withoutV1 = stripTrailingV1(normalized);
        if (!withoutV1.equals(value)) {
            log.warn("{} value '{}' was normalized to '{}': Spring AI appends '/v1/...' "
                    + "automatically, so base-url must not end with '/v1' or '/'.",
                    propertyKey, value, withoutV1);
        }
        return withoutV1;
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String stripTrailingV1(String value) {
        if (value.regionMatches(true, value.length() - 3, "/v1", 0, 3)) {
            String result = value.substring(0, value.length() - 3);
            return stripTrailingSlash(result);
        }
        return value;
    }

    private static boolean isLoopbackHost(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.equals("localhost") || normalized.equals("127.0.0.1")
                || normalized.equals("::1") || normalized.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        return normalized.startsWith("127.");
    }
}
