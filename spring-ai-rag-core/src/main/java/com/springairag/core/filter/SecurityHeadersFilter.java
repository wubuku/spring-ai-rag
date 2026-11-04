package com.springairag.core.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 下发浏览器安全响应头，覆盖 API、SSE 与 {@code /webui} 静态产物。
 *
 * <p>策略按本仓实际能力取最严：同源 API/SSE、无 CDN、无第三方脚本，因此
 * {@code script-src} 只允许同源，不保留 {@code 'unsafe-inline'}。WebUI 的主题
 * 预热脚本已从 {@code index.html} 内联块移到
 * {@code /webui/assets/theme-init.js}，所以内联脚本被 CSP 拦截属于预期行为。
 *
 * <p>React 组件使用 {@code style} 属性（design tokens 之外的一次性布局），
 * 这类内联样式由 {@code style-src-attr} 放行，而 {@code <style>} 块仍只允许
 * 同源文件。文件预览的 PDF 通过 {@code blob:} 渲染，{@code object-src} 与
 * {@code img-src} 显式允许该协议，避免安全头把既有功能打坏。
 *
 * <p>HSTS 只在 TLS 场景下发；直接访问 HTTP 或经可信反向代理转发
 * {@code X-Forwarded-Proto: https} 时视为 TLS。
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    public static final String CONTENT_SECURITY_POLICY_HEADER =
            "Content-Security-Policy";

    /**
     * 同源应用的最小策略。
     *
     * <p>不使用 {@code 'unsafe-inline'}：任何内联 {@code <script>} 或事件属性都
     * 会被浏览器拒绝。{@code frame-ancestors 'none'} 与
     * {@code X-Frame-Options: DENY} 同时保留，覆盖不支持 CSP Level 2 的旧客户端。
     */
    public static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "base-uri 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "style-src-attr 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'self'",
            "connect-src 'self'",
            "media-src 'self' blob:",
            "object-src 'self' blob:",
            "frame-src 'self'",
            "worker-src 'self'",
            "manifest-src 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    public static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=(), "
                    + "magnetometer=(), gyroscope=(), accelerometer=(), "
                    + "interest-cohort=()";

    public static final String STRICT_TRANSPORT_SECURITY =
            "max-age=31536000; includeSubDomains";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader(CONTENT_SECURITY_POLICY_HEADER, CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        if (isHttps(request)) {
            response.setHeader("Strict-Transport-Security",
                    STRICT_TRANSPORT_SECURITY);
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 仅当请求本身或可信代理声明为 HTTPS 时返回 true。
     *
     * <p>{@code X-Forwarded-Proto} 由入口代理设置；在未部署代理的直连场景，
     * 伪造该头只会让浏览器收到一个 HTTP 上无效的 HSTS 头，不构成风险。
     */
    static boolean isHttps(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto == null) {
            return false;
        }
        String first = forwardedProto.split(",")[0].trim();
        return "https".equalsIgnoreCase(first);
    }
}
