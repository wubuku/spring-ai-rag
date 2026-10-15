package com.springairag.core.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds the stable failure-class header to native non-streaming HTTP errors.
 *
 * <p>Streaming responses can commit status 200 before an SSE error event is
 * emitted; those events carry their own classification instead.</p>
 */
public class FailureClassResponseHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (!response.isCommitted()) {
                FailureClassHttpSupport.apply(response, response.getStatus(), null);
            }
        }
    }
}
