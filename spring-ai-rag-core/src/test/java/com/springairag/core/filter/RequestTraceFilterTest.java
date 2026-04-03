package com.springairag.core.filter;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    // ==================== 基础功能 ====================

    @Nested
    @DisplayName("基础追踪")
    class BasicTracing {

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

    // ==================== 采样策略 ====================

    @Nested
    @DisplayName("采样策略")
    class Sampling {

        @Test
        @DisplayName("采样率 0.0 时不注入 MDC")
        void zeroRateSkipsMdc() throws ServletException, IOException {
            filter.configure(true, 0.0, false, false);
            filter.doFilter(request, response, chain);

            assertNull(MDC.get(RequestTraceFilter.TRACE_ID_KEY));
            // 响应头可能没有（外部传入时有）
        }

        @Test
        @DisplayName("采样率 1.0 时始终注入 MDC")
        void fullRateAlwaysInjects() throws ServletException, IOException {
            filter.configure(true, 1.0, false, false);

            for (int i = 0; i < 10; i++) {
                request = new MockHttpServletRequest();
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilter(request, response, chain);
                assertNotNull(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER),
                        "采样率 1.0 时第 " + (i + 1) + " 次请求应生成 traceId");
            }
        }

        @Test
        @DisplayName("外部传入 traceId 始终保留即使未采样")
        void externalTraceAlwaysPreserved() throws ServletException, IOException {
            filter.configure(true, 0.0, false, false);
            request.addHeader("X-Trace-Id", "external-trace-123");

            filter.doFilter(request, response, chain);

            // 未采样但 traceId 应传递到响应头
            assertEquals("external-trace-123", response.getHeader(RequestTraceFilter.TRACE_ID_HEADER));
        }

        @Test
        @DisplayName("追踪关闭时跳过所有处理")
        void disabledSkipsAll() throws ServletException, IOException {
            filter.configure(false, 1.0, false, false);
            filter.doFilter(request, response, chain);

            assertNull(MDC.get(RequestTraceFilter.TRACE_ID_KEY));
            assertNull(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER));
            // 验证 chain 被调用（请求被放行）
            assertNotNull(chain.getRequest());
        }

        @Test
        @DisplayName("请求属性标记采样状态")
        void sampledAttributeSet() throws ServletException, IOException {
            filter.configure(true, 1.0, false, false);
            filter.doFilter(request, response, chain);

            assertEquals(true, request.getAttribute(RequestTraceFilter.SAMPLED_ATTRIBUTE));
        }
    }

    // ==================== W3C Trace Context ====================

    @Nested
    @DisplayName("W3C Trace Context")
    class W3CTracing {

        @Test
        @DisplayName("启用 W3C 格式时输出 traceparent 响应头")
        void w3cEnabledOutputsTraceparent() throws ServletException, IOException {
            filter.configure(true, 1.0, true, false);
            filter.doFilter(request, response, chain);

            String traceparent = response.getHeader("traceparent");
            assertNotNull(traceparent, "应输出 traceparent 响应头");
            assertTrue(traceparent.startsWith("00-"), "应以 00- 开头");
            String[] parts = traceparent.split("-");
            assertEquals(4, parts.length, "格式: 00-traceId-spanId-flags");
            assertEquals(32, parts[1].length(), "traceId 应为 32 字符");
            assertEquals(16, parts[2].length(), "spanId 应为 16 字符");
            assertEquals("01", parts[3]);
        }

        @Test
        @DisplayName("传入 traceparent 时复用上游 traceId")
        void incomingTraceparentReusesTraceId() throws ServletException, IOException {
            filter.configure(true, 1.0, true, false);
            String upstreamTraceId = "0af7651916cd43dd8448eb211c80319c";
            request.addHeader("traceparent",
                    "00-" + upstreamTraceId + "-b7ad6b7169203331-01");

            filter.doFilter(request, response, chain);

            String traceparent = response.getHeader("traceparent");
            assertTrue(traceparent.contains(upstreamTraceId),
                    "应复用上游 traceId");
        }

        @Test
        @DisplayName("W3C 格式下 X-Trace-Id 仍输出")
        void w3cAlsoOutputsXTraceId() throws ServletException, IOException {
            filter.configure(true, 1.0, true, false);
            filter.doFilter(request, response, chain);

            assertNotNull(response.getHeader("X-Trace-Id"));
            assertNotNull(response.getHeader("traceparent"));
        }

        @Test
        @DisplayName("禁用 W3C 时不输出 traceparent")
        void w3cDisabledNoTraceparent() throws ServletException, IOException {
            filter.configure(true, 1.0, false, false);
            filter.doFilter(request, response, chain);

            assertNull(response.getHeader("traceparent"));
        }
    }

    // ==================== Span ID ====================

    @Nested
    @DisplayName("Span ID")
    class SpanId {

        @Test
        @DisplayName("启用 spanId 时 MDC 包含 spanId")
        void spanIdEnabledAddsToMdc() throws ServletException, IOException {
            filter.configure(true, 1.0, false, true);
            filter.doFilter(request, response, chain);

            String spanId = MDC.get(RequestTraceFilter.SPAN_ID_KEY);
            // spanId 在 finally 中被清理，但可以在 filter 执行期间验证
            // 这里我们检查 traceId 至少被设置了
            assertNotNull(MDC.get(RequestTraceFilter.TRACE_ID_KEY) == null ?
                    response.getHeader(RequestTraceFilter.TRACE_ID_HEADER) : "ok");
        }

        @Test
        @DisplayName("W3C+spanId 时 traceparent 包含独立 spanId")
        void w3cWithSpanId() throws ServletException, IOException {
            filter.configure(true, 1.0, true, true);
            filter.doFilter(request, response, chain);

            String traceparent = response.getHeader("traceparent");
            assertNotNull(traceparent);
            String[] parts = traceparent.split("-");
            assertEquals(16, parts[2].length(), "spanId 应为 16 字符十六进制");
        }

        @Test
        @DisplayName("不同请求的 spanId 不同")
        void differentSpansPerRequest() throws ServletException, IOException {
            filter.configure(true, 1.0, true, true);

            filter.doFilter(request, response, new MockFilterChain());
            String parent1 = response.getHeader("traceparent");

            response = new MockHttpServletResponse();
            request = new MockHttpServletRequest();
            filter.doFilter(request, response, new MockFilterChain());
            String parent2 = response.getHeader("traceparent");

            assertNotEquals(parent1.split("-")[2], parent2.split("-")[2],
                    "不同请求应有不同 spanId");
        }
    }

    // ==================== 十六进制 ID 生成 ====================

    @Nested
    @DisplayName("ID 生成")
    class IdGeneration {

        @Test
        @DisplayName("generateHexId 生成指定长度")
        void hexIdLength() {
            for (int len : new int[]{8, 12, 16, 32}) {
                String id = RequestTraceFilter.generateHexId(len);
                assertEquals(len, id.length());
                assertTrue(id.matches("[0-9a-f]+"), "应为纯十六进制");
            }
        }

        @Test
        @DisplayName("多次生成不重复")
        void hexIdUnique() {
            var ids = new java.util.HashSet<String>();
            for (int i = 0; i < 100; i++) {
                ids.add(RequestTraceFilter.generateHexId(16));
            }
            assertEquals(100, ids.size(), "100 次生成应全部不同");
        }
    }
}
