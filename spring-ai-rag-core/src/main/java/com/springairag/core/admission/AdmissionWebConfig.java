package com.springairag.core.admission;

import com.springairag.core.chat.ChatPrincipal;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdmissionWebConfig {
    @Bean
    public WebMvcConfigurer admissionConfigurer(AdmissionService admission) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new Interceptor(admission)).addPathPatterns(
                        "/api/*/rag/chat", "/api/*/rag/chat/ask", "/api/*/rag/chat/stream",
                        "/api/*/rag/search", "/api/*/rag/search/**", "/v1/chat/completions");
            }
        };
    }

    static final class Interceptor implements AsyncHandlerInterceptor {
        private static final String LEASE = Interceptor.class.getName();
        private final AdmissionService admission;

        Interceptor(AdmissionService admission) { this.admission = admission; }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (request.getAttribute(LEASE) == null) {
                request.setAttribute(LEASE, admission.request(ChatPrincipal.from(request).id()));
            }
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                Object handler, Exception error) {
            release(request);
        }

        @Override
        public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                Object handler) {
            AdmissionService.Lease lease = (AdmissionService.Lease) request.getAttribute(LEASE);
            AsyncListener listener = new AsyncListener() {
                public void onComplete(AsyncEvent event) { lease.close(); }
                public void onTimeout(AsyncEvent event) { lease.close(); }
                public void onError(AsyncEvent event) { lease.close(); }
                public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
            };
            try {
                request.getAsyncContext().addListener(listener);
            } catch (IllegalStateException completed) {
                lease.close();
            }
        }

        private void release(HttpServletRequest request) {
            if (request.getAttribute(LEASE) instanceof AdmissionService.Lease lease) {
                lease.close();
            }
        }
    }
}
