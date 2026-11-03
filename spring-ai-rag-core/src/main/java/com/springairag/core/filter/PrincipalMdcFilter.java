package com.springairag.core.filter;

import com.springairag.core.chat.ChatPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把认证后的稳定身份写入 MDC，便于只靠日志回答「谁」。
 *
 * <p>必须在 {@link ApiKeyAuthFilter} 之后执行（order -9），
 * 此时请求属性里已有认证结果；只写 principal id，不写原始凭证。
 * 请求结束恢复线程原有 MDC，避免线程池复用时串号。
 */
public class PrincipalMdcFilter extends OncePerRequestFilter {

    /** MDC key：稳定 principal id（如 {@code db:123} / {@code root:environment-root}）。 */
    public static final String PRINCIPAL_ID_KEY = "principalId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String principalId = ChatPrincipal.from(request).id();
        String previous = MDC.get(PRINCIPAL_ID_KEY);
        MDC.put(PRINCIPAL_ID_KEY, principalId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(PRINCIPAL_ID_KEY);
            } else {
                MDC.put(PRINCIPAL_ID_KEY, previous);
            }
        }
    }
}
