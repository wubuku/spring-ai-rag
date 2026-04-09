package com.springairag.core.filter;

import com.springairag.core.config.ApiSloProperties;
import com.springairag.core.metrics.ApiSloTrackerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiSloHandlerInterceptor Tests")
class ApiSloHandlerInterceptorTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private ApiSloHandlerInterceptor interceptor;

    @BeforeEach
    void setUp() {
        ApiSloProperties properties = new ApiSloProperties();
        properties.setEnabled(true);
        interceptor = new ApiSloHandlerInterceptor(properties, applicationContext);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // preHandle tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("preHandle returns true immediately when SLO tracking is disabled")
    void preHandle_disabled_returnsTrue() throws Exception {
        ApiSloProperties disabledProps = new ApiSloProperties();
        disabledProps.setEnabled(false);
        ApiSloHandlerInterceptor disabledInterceptor =
                new ApiSloHandlerInterceptor(disabledProps, applicationContext);

        boolean result = disabledInterceptor.preHandle(request, response, new Object());

        assertTrue(result);
        verify(request, never()).setAttribute(any(), any());
    }

    @Test
    @DisplayName("preHandle returns true immediately when handler is not HandlerMethod")
    void preHandle_notHandlerMethod_returnsTrue() throws Exception {
        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        verify(request, never()).setAttribute(eq("_sloStartTime"), any());
    }

    @Test
    @DisplayName("preHandle sets start time and endpoint attributes for HandlerMethod")
    void preHandle_handlerMethod_setsAttributes() throws Exception {
        HandlerMethod hm = createHandlerMethod("handleGet");

        boolean result = interceptor.preHandle(request, response, hm);

        assertTrue(result);
        verify(request).setAttribute(eq("_sloStartTime"), any(Long.class));
        verify(request).setAttribute(eq("_sloEndpoint"), any(String.class));
    }

    @Test
    @DisplayName("preHandle with null sloProperties falls back to disabled behavior")
    void preHandle_nullSloProperties_doesNothing() throws Exception {
        ApiSloHandlerInterceptor nullPropsInterceptor =
                new ApiSloHandlerInterceptor(null, applicationContext);

        boolean result = nullPropsInterceptor.preHandle(request, response, new Object());

        assertTrue(result);
        verifyNoInteractions(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // afterCompletion tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("afterCompletion does nothing when startTime attribute is null")
    void afterCompletion_nullStartTime_doesNothing() throws Exception {
        // startTime attribute not set -> afterCompletion returns early
        interceptor.afterCompletion(request, response, null, null);

        // No bean lookup attempted because startTime is null
        verifyNoInteractions(applicationContext);
    }

    @Test
    @DisplayName("afterCompletion does nothing when endpoint attribute is null")
    void afterCompletion_nullEndpoint_doesNothing() throws Exception {
        when(request.getAttribute("_sloStartTime")).thenReturn(System.currentTimeMillis());
        // endpoint attribute not set

        interceptor.afterCompletion(request, response, null, null);

        verifyNoInteractions(applicationContext);
    }

    @Test
    @DisplayName("afterCompletion does nothing when SLO is disabled")
    void afterCompletion_disabled_doesNothing() throws Exception {
        ApiSloProperties disabledProps = new ApiSloProperties();
        disabledProps.setEnabled(false);
        ApiSloHandlerInterceptor disabledInterceptor =
                new ApiSloHandlerInterceptor(disabledProps, applicationContext);
        when(request.getAttribute("_sloStartTime")).thenReturn(System.currentTimeMillis());
        when(request.getAttribute("_sloEndpoint")).thenReturn("test.endpoint");

        disabledInterceptor.afterCompletion(request, response, null, null);

        verifyNoInteractions(applicationContext);
    }

    @Test
    @DisplayName("afterCompletion records latency when enabled and attributes present")
    void afterCompletion_enabled_recordsLatency() throws Exception {
        ApiSloTrackerService trackerService = mock(ApiSloTrackerService.class);
        when(applicationContext.getBean(ApiSloTrackerService.class)).thenReturn(trackerService);
        HandlerMethod hm = createHandlerMethod("handleGet");

        // preHandle first to set up attributes
        interceptor.preHandle(request, response, hm);

        // Simulate some elapsed time
        when(request.getAttribute("_sloStartTime")).thenReturn(System.currentTimeMillis() - 150);
        when(request.getAttribute("_sloEndpoint")).thenReturn("rag.get.search");

        interceptor.afterCompletion(request, response, hm, null);

        verify(trackerService).recordLatency(eq("rag.get.search"), any(Long.class));
    }

    @Test
    @DisplayName("afterCompletion handles missing ApiSloTrackerService gracefully")
    void afterCompletion_noTrackerService_doesNotThrow() throws Exception {
        when(applicationContext.getBean(ApiSloTrackerService.class))
                .thenThrow(new NoSuchBeanDefinitionException("ApiSloTrackerService"));
        HandlerMethod hm = createHandlerMethod("handleGet");

        // preHandle first to set up attributes
        interceptor.preHandle(request, response, hm);

        when(request.getAttribute("_sloStartTime")).thenReturn(System.currentTimeMillis() - 50);
        when(request.getAttribute("_sloEndpoint")).thenReturn("test.endpoint");

        // Should not throw — logs trace and returns
        assertDoesNotThrow(() ->
                interceptor.afterCompletion(request, response, hm, null));
    }

    @Test
    @DisplayName("afterCompletion records latency even when exception is passed")
    void afterCompletion_withException_recordsLatency() throws Exception {
        ApiSloTrackerService trackerService = mock(ApiSloTrackerService.class);
        when(applicationContext.getBean(ApiSloTrackerService.class)).thenReturn(trackerService);
        HandlerMethod hm = createHandlerMethod("handlePost");

        interceptor.preHandle(request, response, hm);

        when(request.getAttribute("_sloStartTime")).thenReturn(System.currentTimeMillis() - 100);
        when(request.getAttribute("_sloEndpoint")).thenReturn("rag.post.documents");

        Exception ex = new RuntimeException("some error");
        interceptor.afterCompletion(request, response, hm, ex);

        // Latency is still recorded regardless of exception
        verify(trackerService).recordLatency(eq("rag.post.documents"), any(Long.class));
    }

    @Test
    @DisplayName("endpoint name is cached across multiple preHandle calls for same handler")
    void preHandle_cachesEndpointName() throws Exception {
        HandlerMethod hm = createHandlerMethod("handleGet");

        // First call
        interceptor.preHandle(request, response, hm);
        // Second call with same handler
        interceptor.preHandle(request, response, hm);

        // Should set attributes twice (cache is internal, we just verify no errors)
        verify(request, times(2)).setAttribute(eq("_sloStartTime"), any(Long.class));
        verify(request, times(2)).setAttribute(eq("_sloEndpoint"), any(String.class));
    }

    @Test
    @DisplayName("preHandle resolves endpoint name from @RequestMapping path")
    void preHandle_resolvesEndpointFromRequestMapping() throws Exception {
        HandlerMethod hm = createHandlerMethod("handlePost");

        interceptor.preHandle(request, response, hm);

        verify(request).setAttribute(eq("_sloEndpoint"), eq("rag.post.documents"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────
    private HandlerMethod createHandlerMethod(String methodName) {
        try {
            return new HandlerMethod(new TestController(), methodName);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public static class TestController {
        @RequestMapping(value = "/api/v1/rag/search", method = RequestMethod.GET)
        public String handleGet() { return "result"; }

        @RequestMapping(value = "/api/v1/rag/documents", method = RequestMethod.POST)
        public String handlePost() { return "result"; }
    }
}
