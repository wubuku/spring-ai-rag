package com.springairag.core.filter;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RequestTraceFilter 上下文解析残余（Batch 364）：外部 X-Trace-Id
 * + spanId 生成、合法/非法 traceparent 解析、中段采样率分支、
 * 配置访问器。
 */
class RequestTraceFilterContextTest {

    private static final String TRACE_ID_KEY = RequestTraceFilter.TRACE_ID_KEY;
    private static final String SPAN_ID_KEY = RequestTraceFilter.SPAN_ID_KEY;

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
    void externalTraceIdGeneratesSpanIdWhenEnabled()
            throws ServletException, IOException {
        filter.configure(true, 1.0, false, true);
        request.addHeader(RequestTraceFilter.INCOMING_TRACE_HEADER, "ext-trace-1");
        String[] spanInMdc = new String[1];

        jakarta.servlet.FilterChain capture =
                (req, res) -> spanInMdc[0] = MDC.get(SPAN_ID_KEY);
        filter.doFilter(request, response, capture);

        // 外部 traceId 保留，spanId 独立生成并注入 MDC。
        assertEquals("ext-trace-1", response.getHeader(
                RequestTraceFilter.TRACE_ID_HEADER));
        assertNotNull(spanInMdc[0]);
        assertEquals(16, spanInMdc[0].length());
    }

    @Test
    void validTraceparentProvidesSpanIdWhenEnabled()
            throws ServletException, IOException {
        filter.configure(true, 1.0, true, true);
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        String spanId = "b7ad6b7169203331";
        request.addHeader(RequestTraceFilter.INCOMING_TRACEPARENT_HEADER,
                "00-" + traceId + "-" + spanId + "-01");
        String[] mdc = new String[2];
        jakarta.servlet.FilterChain capture = (req, res) -> {
            mdc[0] = MDC.get(TRACE_ID_KEY);
            mdc[1] = MDC.get(SPAN_ID_KEY);
        };

        filter.doFilter(request, response, capture);

        assertEquals(traceId, response.getHeader(
                RequestTraceFilter.W3C_TRACEPARENT_HEADER)
                .substring(3, 35));
        assertEquals(traceId, mdc[0]);
        assertEquals(spanId, mdc[1]);
    }

    @Test
    void malformedTraceparentFallsBackToGeneratedTraceId()
            throws ServletException, IOException {
        filter.configure(true, 1.0, true, true);
        request.addHeader(RequestTraceFilter.INCOMING_TRACEPARENT_HEADER,
                "not-a-valid-traceparent");

        filter.doFilter(request, response, chain);

        // 解析失败回退生成 12 字符 traceId；w3cFormat 下仍外发
        // traceparent 头（内嵌生成的 traceId）。
        String generated = response.getHeader(RequestTraceFilter.TRACE_ID_HEADER);
        assertEquals(12, generated.length());
        assertTrue(response.getHeader(
                RequestTraceFilter.W3C_TRACEPARENT_HEADER)
                .startsWith("00-" + generated + "-"));
    }

    @Test
    void midRangeSamplingRateExercisesRandomDecision()
            throws ServletException, IOException {
        filter.configure(true, 0.5, false, false);

        filter.doFilter(request, response, chain);

        // 采样决策随机分支可采可不采：只断言请求正常通过过滤器链。
        assertNotNull(((MockFilterChain) chain).getRequest());
    }

    @Test
    void configurationAccessorsReflectConfiguredValues() {
        filter.configure(false, 0.25, true, true);

        assertFalse(filter.isEnabled());
        assertEquals(0.25, filter.getSamplingRate());
        assertTrue(filter.isW3cFormat());
        assertTrue(filter.isSpanIdEnabled());
    }
}
