package com.springairag.core.filter;

import com.springairag.core.config.ApiSloProperties;
import com.springairag.core.metrics.ApiSloTrackerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP request interceptor that records endpoint latencies for SLO compliance tracking.
 *
 * <p>Intercepts all controller requests, measures their duration, and delegates
 * to {@link ApiSloTrackerService} for compliance computation.
 *
 * <p>The endpoint name is derived from the controller method's {@code @Timed} value
 * if present, otherwise from the request mapping path.
 *
 * <p>This interceptor is safe to load even when {@link ApiSloTrackerService} is not
 * available in the context — it simply does nothing in that case.
 */
@Component
public class ApiSloHandlerInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiSloHandlerInterceptor.class);

    private final ApiSloProperties sloProperties;
    private final ApplicationContext applicationContext;

    public ApiSloHandlerInterceptor(
            @Autowired(required = false) ApiSloProperties sloProperties,
            @Autowired ApplicationContext applicationContext) {
        this.sloProperties = sloProperties != null ? sloProperties : createDefaultProperties();
        this.applicationContext = applicationContext;
    }

    private static ApiSloProperties createDefaultProperties() {
        ApiSloProperties props = new ApiSloProperties();
        props.setEnabled(false);
        return props;
    }

    private final Map<HandlerMethod, String> timedValueCache = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!sloProperties.isEnabled() || !(handler instanceof HandlerMethod hm)) {
            return true;
        }
        request.setAttribute("_sloStartTime", System.currentTimeMillis());

        String endpointName = timedValueCache.computeIfAbsent(hm, this::resolveEndpointName);
        request.setAttribute("_sloEndpoint", endpointName);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) throws Exception {
        Long startTime = (Long) request.getAttribute("_sloStartTime");
        String endpoint = (String) request.getAttribute("_sloEndpoint");

        if (startTime == null || endpoint == null || !sloProperties.isEnabled()) {
            return;
        }

        try {
            ApiSloTrackerService tracker = applicationContext.getBean(ApiSloTrackerService.class);
            long latencyMs = System.currentTimeMillis() - startTime;
            tracker.recordLatency(endpoint, latencyMs);
        } catch (org.springframework.beans.factory.NoSuchBeanDefinitionException e) {
            // ApiSloTrackerService not available in this context (e.g., @WebMvcTest without it)
            log.trace("ApiSloTrackerService not available, skipping SLO tracking");
        }
    }

    private String resolveEndpointName(HandlerMethod hm) {
        // Try to find @Timed annotation value
        io.micrometer.core.annotation.Timed timed = AnnotationUtils.findAnnotation(
                hm.getMethod(), io.micrometer.core.annotation.Timed.class);
        if (timed != null && !timed.value().isEmpty()) {
            return timed.value();
        }

        // Fall back to controller method path
        RequestMapping mapping = AnnotationUtils.findAnnotation(hm.getMethod(), RequestMapping.class);
        if (mapping != null && mapping.value().length > 0) {
            String path = mapping.value()[0];
            // Strip /api/v1/rag prefix
            if (path.startsWith("/api/v1/rag")) {
                path = path.substring("/api/v1/rag".length());
            }
            String method = mapping.method().length > 0
                    ? mapping.method()[0].name().toLowerCase() : "get";
            return "rag." + method + path.replace("/", ".");
        }

        // Last resort: use bean name + method name
        return hm.getBeanType().getSimpleName() + "." + hm.getMethod().getName();
    }
}
