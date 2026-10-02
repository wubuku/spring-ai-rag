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
 *       {@code /api/v1/rag/documents}。含穿越段或编码分隔符的 URI 一律按
 *       "不排除"处理，即必须通过鉴权与限流。</li>
 * </ol>
 *
 * <p><b>上面这个分歧是真的，而且已经实测过</b>：{@code SecurityPathTraversalProbeTest}
 * 起一个真实 Tomcat，用裸 socket 发穿越请求行（普通 HTTP 客户端会在请求离开
 * 进程前就规范化路径，那样只能回答"客户端做了什么"）。测到的是
 * {@code requestURI=/actuator/../api/v1/rag/probe-protected} 而
 * {@code servletPath=/api/v1/rag/probe-protected}——<b>过滤器确实按规范化路径被
 * 匹配，却拿到原始请求行</b>。所以这里防的不是假想。
 *
 * <p><b>也要说清它在当前栈上的分量</b>：同一批实测显示，这个请求随后
 * <b>无论走不走排除判定都是 404</b>，因为 Spring MVC 按未规范化的 URI 解析
 * handler，穿越根本匹配不到端点。也就是说本规则在当前栈上是<b>纵深防御</b>，
 * 而不是唯一挡住请求的那一道。保留它的理由有三条，且都与"今天恰好 404"无关：
 * 前面挂反向代理、改 connector 配置（例如放开编码斜杠）、或框架升级到按规范化
 * 路径解析 handler，都可能让这个 404 消失；而"原文与规范化路径不一致"这个前提
 * 一旦成立，判定的方向就该是保守的那一边。
 *
 * <p>另需注意：这条规则<b>不能</b>由容器探测来证明有效。删掉 {@link #isAmbiguous}
 * 的分支后 {@code SecurityPathTraversalProbeTest} 依然全绿——因为路由本来就把
 * 请求 404 掉了，匿名用例的 404 根本证明不了排除规则起了作用。真正钉住这条规则
 * 的是 {@code SecurityPathExclusionsTest} 单元测试，删掉分支它会红。探测负责的
 * 是容器层的前置事实：过滤器确实按规范化路径被匹配、也确实拿到原始路径。
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
