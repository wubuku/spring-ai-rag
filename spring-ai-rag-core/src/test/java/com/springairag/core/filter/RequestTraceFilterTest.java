package com.springairag.core.filter;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RequestTraceFilter 请求追踪过滤器")
class RequestTraceFilterTest {

    private RequestTraceFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RequestTraceFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("自动生成 traceId 并注入 MDC")
    void doFilter_generatesTraceId() throws ServletException, IOException {
        filter.doFilter(request, response, chain);

        String traceId = response.getHeader(RequestTraceFilter.TRACE_ID_HEADER);
        assertNotNull(traceId);
        assertEquals(12, traceId.length(), "traceId 应为 12 字符");
    }

    @Test
    @DisplayName("响应头包含 X-Trace-Id")
    void doFilter_setsResponseHeader() throws ServletException, IOException {
        filter.doFilter(request, response, chain);

        assertNotNull(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER));
    }

    @Test
    @DisplayName("使用调用方传入的 traceId")
    void doFilter_usesIncomingTraceId() throws ServletException, IOException {
        String incomingTrace = "abc123def456";
        request.addHeader(RequestTraceFilter.INCOMING_TRACE_HEADER, incomingTrace);

        filter.doFilter(request, response, chain);

        assertEquals(incomingTrace, response.getHeader(RequestTraceFilter.TRACE_ID_HEADER));
    }

    @Test
    @DisplayName("过滤链执行后 MDC 已清理")
    void doFilter_clearsMdcAfterRequest() throws ServletException, IOException {
        filter.doFilter(request, response, chain);

        assertNull(MDC.get(RequestTraceFilter.TRACE_ID_KEY),
                "请求完成后 MDC 中的 traceId 应被清理");
    }

    @Test
    @DisplayName("每次请求生成不同 traceId")
    void doFilter_differentTraceIdsPerRequest() throws ServletException, IOException {
        MockHttpServletResponse response1 = new MockHttpServletResponse();
        MockHttpServletResponse response2 = new MockHttpServletResponse();

        filter.doFilter(request, response1, new MockFilterChain());
        filter.doFilter(request, response2, new MockFilterChain());

        String trace1 = response1.getHeader(RequestTraceFilter.TRACE_ID_HEADER);
        String trace2 = response2.getHeader(RequestTraceFilter.TRACE_ID_HEADER);

        assertNotEquals(trace1, trace2, "不同请求应有不同 traceId");
    }

    @Test
    @DisplayName("空白传入 traceId 时自动生成")
    void doFilter_blankIncomingGeneratesNew() throws ServletException, IOException {
        request.addHeader(RequestTraceFilter.INCOMING_TRACE_HEADER, "   ");

        filter.doFilter(request, response, chain);

        String traceId = response.getHeader(RequestTraceFilter.TRACE_ID_HEADER);
        assertNotNull(traceId);
        assertEquals(12, traceId.length());
        assertNotEquals("   ", traceId.trim());
    }

    @Test
    @DisplayName("过滤链异常时 MDC 仍清理")
    void doFilter_clearsMdcOnException() {
        MockFilterChain failingChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws IOException, ServletException {
                MDC.put(RequestTraceFilter.TRACE_ID_KEY, "should-be-cleaned");
                throw new ServletException("test error");
            }
        };

        assertThrows(ServletException.class, () ->
                filter.doFilter(request, response, failingChain));

        assertNull(MDC.get(RequestTraceFilter.TRACE_ID_KEY),
                "异常后 MDC 仍应被清理");
    }
}
