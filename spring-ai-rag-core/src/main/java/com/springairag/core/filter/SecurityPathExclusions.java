package com.springairag.core.filter;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 安全过滤器的路径排除判定，供 {@link ApiKeyAuthFilter} 与 {@link RateLimitFilter} 共用。
 *
 * <p>为什么需要共用：两个过滤器各自维护一份几乎相同的前缀清单，
 * 迟早会漂移——一处加了新端点而另一处没加，就是一个"要么漏鉴权、
 * 要么莫名 401"的不一致来源。更重要的是，<b>判定必须落在同一个语义上</b>：
 * 认证排除与限流排除如果对同一个 URI 给出不同答案，行为会随过滤器顺序变化，
 * 那是最难排查的一类问题。
 *
 * <p>两条必须遵守的规则：
 *
 * <ol>
 *   <li><b>分段感知的前缀匹配</b>。{@code path.startsWith("/health")} 会把
 *       {@code /healthz}、{@code /healthcheck} 一并排除；一旦将来出现这类端点，
 *       它会**静默地不受鉴权**。正确的形状是"等于该前缀，或以 {@code 前缀 + "/"} 开头"。</li>
 *   <li><b>形态可疑的 URI 一律不算排除</b>（fail closed）。{@code getRequestURI()}
 *       返回的是客户端发来的<b>原始、未规范化</b>路径，而容器是按<b>规范化之后</b>
 *       的路径做路由的。形如 {@code /actuator/../api/v1/rag/documents} 的请求，
 *       在过滤器眼里以 {@code /actuator} 开头（会被排除），在路由器眼里却是
 *       {@code /api/v1/rag/documents}。到底能不能真的走通，取决于容器的
 *       规范化与拒绝策略——<b>本项目当前无法起真实容器实测（无 Docker、无数据库），
 *       因此这里不去断言"可利用"，而是让判定不再依赖那个前提</b>：
 *       含穿越段或编码分隔符的 URI 一律按"不排除"处理，即必须通过鉴权与限流。</li>
 * </ol>
 */
final class SecurityPathExclusions {

    /**
     * 以分段为界的前缀。覆盖 {@code /actuator/**}、{@code /swagger-ui/**}、
     * {@code /v3/api-docs/**}、{@code /health/components}、{@code /error}。
     */
    private static final List<String> SHARED_PREFIXES =
            List.of("/actuator", "/swagger-ui", "/v3/api-docs", "/health", "/error");

    /**
     * 只按整条路径匹配。{@code springdoc.swagger-ui.path} 默认是
     * {@code /swagger-ui.html}，它不以 {@code "/swagger-ui/"} 开头，
     * 只能整条匹配。
     */
    private static final List<String> SHARED_EXACT = List.of("/swagger-ui.html");

    private SecurityPathExclusions() {
    }

    /**
     * @param path       原始请求路径
     * @param exactPaths 额外需要整条匹配的路径（可为空）
     * @return 是否应当跳过鉴权 / 跳过限流
     */
    static boolean isExcluded(String path, Collection<String> exactPaths) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (isAmbiguous(path)) {
            return false;
        }
        for (String prefix : SHARED_PREFIXES) {
            if (matchesPrefix(path, prefix)) {
                return true;
            }
        }
        for (String exact : SHARED_EXACT) {
            if (path.equals(exact)) {
                return true;
            }
        }
        if (exactPaths != null) {
            for (String exact : exactPaths) {
                if (path.equals(exact)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 该 URI 是否含有会让"原始路径"与"容器路由路径"分叉的成分。
     *
     * <p>判定不尝试去"修复"这种路径——规范化一个畸形路径再拿去做安全判定，
     * 本身就是在发明语义。这里只回答"能不能放心排除"。
     */
    static boolean isAmbiguous(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f")
                || lower.contains("%5c") || lower.contains("%00")) {
            return true;
        }
        for (String segment : path.split("/", -1)) {
            if ("..".equals(segment) || ".".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /** 分段感知的前缀匹配：{@code /health} 命中，{@code /healthz} 不命中。 */
    static boolean matchesPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
