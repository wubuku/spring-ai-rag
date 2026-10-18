package com.springairag.core.admission;

import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class AdmissionWebConfigTest {
    @Test
    void registrationCoversVersionedNativeRoutesAndChatAliasButNotStatusOrHistory() {
        class Registry extends org.springframework.web.servlet.config.annotation.InterceptorRegistry {
            java.util.List<Object> installed() { return getInterceptors(); }
        }
        var registry = new Registry();
        var service = new AdmissionService(new RagProperties(), new SimpleMeterRegistry());
        new AdmissionWebConfig().admissionConfigurer(service).addInterceptors(registry);
        var mapped = (org.springframework.web.servlet.handler.MappedInterceptor) registry.installed().getFirst();
        for (String path : java.util.List.of("/api/v1/rag/chat", "/api/v1/rag/chat/ask",
                "/api/v1/rag/chat/stream", "/api/v1/rag/search", "/api/v1/rag/search/config",
                "/v1/chat/completions")) {
            var request = new MockHttpServletRequest("POST", path);
            org.springframework.web.util.ServletRequestPathUtils.parseAndCache(request);
            assertTrue(mapped.matches(request), path);
        }
        for (String path : java.util.List.of("/actuator/health", "/v1/models", "/api/v1/rag/chat/history/test",
                "/api/v1/rag/chat/turns/test", "/api/v1/rag/documents")) {
            var request = new MockHttpServletRequest("GET", path);
            org.springframework.web.util.ServletRequestPathUtils.parseAndCache(request);
            assertFalse(mapped.matches(request), path);
        }
    }

    @Test
    void asyncRedispatchTimeoutErrorCompletionAndSyncPathsReleaseExactlyOnce() throws Exception {
        AdmissionService service = new AdmissionService(new RagProperties(), new SimpleMeterRegistry());
        var interceptor = new AdmissionWebConfig.Interceptor(service);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, this);
        interceptor.preHandle(request, response, this);
        assertEquals(1, service.active("request"));
        request.setAsyncSupported(true);
        var context = (MockAsyncContext) request.startAsync();
        interceptor.afterConcurrentHandlingStarted(request, response, this);
        for (var listener : context.getListeners()) {
            listener.onTimeout(new AsyncEvent(context));
            listener.onError(new AsyncEvent(context));
            listener.onComplete(new AsyncEvent(context));
        }
        interceptor.afterCompletion(request, response, this, null);
        assertEquals(0, service.active("request"));
        request = new MockHttpServletRequest();
        interceptor.preHandle(request, response, this);
        assertEquals(1, service.active("request"));
        interceptor.afterCompletion(request, response, this, new RuntimeException());
        assertEquals(0, service.active("request"));
    }
}
