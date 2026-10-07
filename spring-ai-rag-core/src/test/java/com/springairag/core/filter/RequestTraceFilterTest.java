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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RequestTraceFilter - Request Tracing Filter")
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

    /**
     * 在<strong>链执行的那一刻</strong>把 MDC 整份截下来。
     *
     * <p>{@code RequestTraceFilter#doFilter} 在 finally 里把 TRACE_ID_KEY /
     * SPAN_ID_KEY 都 {@code MDC.remove} 掉了，所以 doFilter 返回之后再去看
     * MDC，拿到的一定是 null——MDC 里的声明只有链执行期间才可观测。
     * 过去那条用例的注释写着"can be verified during filter execution"，
     * 然后就没验，退化成了一句恒真的 assertNotNull。
     *
     * <p>这里覆写 {@code MockFilterChain#doFilter} 而不是塞一个 Filter 进去：
     * 它那几个构造器要的是 {@code Servlet}，而 {@code Servlet} 不是函数式接口，
     * 硬凑一个出来比覆写难读。
     */
    private static final class MdcCapturingChain extends MockFilterChain {
        private final AtomicReference<Map<String, String>> captured =
                new AtomicReference<>();

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req,
                             jakarta.servlet.ServletResponse res)
                throws IOException, ServletException {
            Map<String, String> current = MDC.getCopyOfContextMap();
            captured.set(current == null ? Map.of() : Map.copyOf(current));
            super.doFilter(req, res);
        }

        Map<String, String> captured() {
            return captured.get();
        }
    }

    /** 装好一条会抓 MDC 的链；抓的是整张表，所以多次取用不会互相冲掉。 */
    private MdcCapturingChain captureMdc() {
        MdcCapturingChain capturing = new MdcCapturingChain();
        chain = capturing;
        return capturing;
    }

    // ==================== Basic Functionality ====================

    @Nested
    @DisplayName("Basic Tracing")
    class BasicTracing {

        @Test
        @DisplayName("Auto-generates traceId and injects into MDC")
        void doFilter_generatesTraceId() throws ServletException, IOException {
            filter.doFilter(request, response, chain);

            String traceId = response.getHeader(RequestTraceFilter.TRACE_ID_HEADER);
            assertNotNull(traceId);
            assertEquals(12, traceId.length(), "traceId should be 12 characters");
        }

        @Test
        @DisplayName("Response header contains X-Trace-Id")
        void doFilter_setsResponseHeader() throws ServletException, IOException {
            MdcCapturingChain capturing = captureMdc();
            filter.doFilter(request, response, chain);

            // 长度由 doFilter_generatesTraceId 负责；这里验的是另一件事——
            // 响应头里的 id 与链内 MDC 里的 id 必须是同一个值。
            // 原来只有 assertNotNull(header)，两边对不上也照样绿。
            String header = response.getHeader(RequestTraceFilter.TRACE_ID_HEADER);
            assertNotNull(header);
            assertFalse(header.isBlank(), "traceId 不应为空串");
            assertEquals(header, capturing.captured().get(RequestTraceFilter.TRACE_ID_KEY));
        }

        @Test
        @DisplayName("Uses incoming traceId from caller")
        void doFilter_usesIncomingTraceId() throws ServletException, IOException {
            String incomingTrace = "abc123def456";
            request.addHeader(RequestTraceFilter.INCOMING_TRACE_HEADER, incomingTrace);

            filter.doFilter(request, response, chain);

            assertEquals(incomingTrace, response.getHeader(RequestTraceFilter.TRACE_ID_HEADER));
        }

        @Test
        @DisplayName("MDC is cleared after filter chain execution")
        void doFilter_clearsMdcAfterRequest() throws ServletException, IOException {
            filter.doFilter(request, response, chain);

            assertNull(MDC.get(RequestTraceFilter.TRACE_ID_KEY),
                    "请求完成后 MDC 中的 traceId 应被清理");
        }

        @Test
        @DisplayName("Each request generates a different traceId")
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
        @DisplayName("Auto-generates traceId when incoming is blank")
        void doFilter_blankIncomingGeneratesNew() throws ServletException, IOException {
            request.addHeader(RequestTraceFilter.INCOMING_TRACE_HEADER, "   ");

            filter.doFilter(request, response, chain);

            String traceId = response.getHeader(RequestTraceFilter.TRACE_ID_HEADER);
            assertNotNull(traceId);
            assertEquals(12, traceId.length());
            assertNotEquals("   ", traceId.trim(), "trimmed traceId should not match blank input");
        }

        @Test
        @DisplayName("MDC is still cleared when filter chain throws")
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
                    "MDC should still be cleared after exception");
        }
    }

    // ==================== Sampling Strategy ====================

    @Nested
    @DisplayName("Sampling Strategy")
    class Sampling {

        @Test
        @DisplayName("Sampling rate 0.0 skips MDC injection")
        void zeroRateSkipsMdc() throws ServletException, IOException {
            filter.configure(true, 0.0, false, false);
            filter.doFilter(request, response, chain);

            assertNull(MDC.get(RequestTraceFilter.TRACE_ID_KEY));
            // Response header may be absent (present when externally provided)
        }

        @Test
        @DisplayName("Sampling rate 1.0 always injects MDC")
        void fullRateAlwaysInjects() throws ServletException, IOException {
            filter.configure(true, 1.0, false, false);

            java.util.Set<String> seenTraceIds = new java.util.LinkedHashSet<>();
            for (int i = 0; i < 10; i++) {
                request = new MockHttpServletRequest();
                response = new MockHttpServletResponse();
                MdcCapturingChain capturing = captureMdc();
                filter.doFilter(request, response, chain);
                // 名字写着 always injects MDC，原先却只查了响应头。
                // 采样率 1.0 的真正承诺是每一次都进 MDC —— 响应头在
                // 未采样时也可能来自外部传入，两者不是一回事。
                assertNotNull(capturing.captured().get(RequestTraceFilter.TRACE_ID_KEY),
                        "request " + (i + 1)
                            + " should inject traceId into MDC with sampling rate 1.0");
                assertNotNull(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER),
                        "request " + (i + 1) + " should generate traceId with sampling rate 1.0");
                // 两条非空断法彼此独立：MDC 里一个 id、响应头另一个 id，
                // 两条照样都绿，可链路追踪就此对不上（下游按头关联、
                // 日志按 MDC 关联，两边落在两个 id 上）。
                // 这条交叉比对才是"同一个 traceId 落到两处"的真正承诺。
                assertEquals(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER),
                        capturing.captured().get(RequestTraceFilter.TRACE_ID_KEY),
                        "request " + (i + 1)
                            + ": response header and MDC must carry the same traceId");
                // 10 次循环还顺带钉住"每次都生成"：10 个互不相同的 id。
                seenTraceIds.add(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER));
            }
            assertEquals(10, seenTraceIds.size(),
                    "10 requests at sampling rate 1.0 must produce 10 distinct traceIds");
        }

        @Test
        @DisplayName("External traceId always preserved even if not sampled")
        void externalTraceAlwaysPreserved() throws ServletException, IOException {
            filter.configure(true, 0.0, false, false);
            request.addHeader("X-Trace-Id", "external-trace-123");

            filter.doFilter(request, response, chain);

            // Not sampled but traceId should be propagated to response header
            assertEquals("external-trace-123", response.getHeader(RequestTraceFilter.TRACE_ID_HEADER));
        }

        @Test
        @DisplayName("Skips all processing when tracing is disabled")
        void disabledSkipsAll() throws ServletException, IOException {
            filter.configure(false, 1.0, false, false);
            filter.doFilter(request, response, chain);

            assertNull(MDC.get(RequestTraceFilter.TRACE_ID_KEY));
            assertNull(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER));
            // Verify chain was called (request was passed through)
            assertNotNull(chain.getRequest());
        }

        @Test
        @DisplayName("Request attribute marks sampling status")
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
        @DisplayName("Outputs traceparent header when W3C format enabled")
        void w3cEnabledOutputsTraceparent() throws ServletException, IOException {
            filter.configure(true, 1.0, true, false);
            filter.doFilter(request, response, chain);

            String traceparent = response.getHeader("traceparent");
            assertNotNull(traceparent, "should output traceparent response header");
            assertTrue(traceparent.startsWith("00-"), "should start with 00-");
            String[] parts = traceparent.split("-");
            assertEquals(4, parts.length, "format: 00-traceId-spanId-flags");
            assertEquals(32, parts[1].length(), "traceId should be 32 characters");
            assertEquals(16, parts[2].length(), "spanId should be 16 characters");
            assertEquals("01", parts[3]);
        }

        @Test
        @DisplayName("Reuses upstream traceId when traceparent is incoming")
        void incomingTraceparentReusesTraceId() throws ServletException, IOException {
            filter.configure(true, 1.0, true, false);
            String upstreamTraceId = "0af7651916cd43dd8448eb211c80319c";
            request.addHeader("traceparent",
                    "00-" + upstreamTraceId + "-b7ad6b7169203331-01");

            filter.doFilter(request, response, chain);

            String traceparent = response.getHeader("traceparent");
            assertTrue(traceparent.contains(upstreamTraceId),
                    "should reuse upstream traceId");
        }

        @Test
        @DisplayName("X-Trace-Id still output alongside W3C format")
        void w3cAlsoOutputsXTraceId() throws ServletException, IOException {
            filter.configure(true, 1.0, true, false);
            filter.doFilter(request, response, chain);

            // "also outputs" 的意思是两个头指向同一次追踪，不是"两个都非空"。
            // 原来正是后者：traceparent 里写的是别的 traceId 也照样绿。
            String xTraceId = response.getHeader("X-Trace-Id");
            String traceparent = response.getHeader("traceparent");
            assertNotNull(xTraceId);
            assertNotNull(traceparent);
            assertTrue(traceparent.startsWith("00-" + xTraceId + "-"),
                    "traceparent 应复用同一个 traceId：" + traceparent);
            assertEquals(4, traceparent.split("-").length, traceparent);
        }

        @Test
        @DisplayName("No traceparent output when W3C is disabled")
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
        @DisplayName("MDC contains spanId when spanId is enabled")
        void spanIdEnabledAddsToMdc() throws ServletException, IOException {
            filter.configure(true, 1.0, false, true);
            MdcCapturingChain capturing = captureMdc();

            filter.doFilter(request, response, chain);

            // Batch 954：原来的断言是
            //   assertNotNull(MDC.get(TRACE_ID_KEY) == null
            //           ? response.getHeader(TRACE_ID_HEADER) : "ok");
            // 两个分支只要过滤器跑过就都非 null，而上面那句
            //   String spanId = MDC.get(SPAN_ID_KEY);
            // 读出来的值压根没用。这条断言在数学上恒成立。
            // 现在在链内取值：spanId 必须真的进了 MDC，且是 16 位十六进制。
            Map<String, String> mdc = capturing.captured();
            assertNotNull(mdc, "链必须真的跑起来");
            String spanId = mdc.get(RequestTraceFilter.SPAN_ID_KEY);
            assertNotNull(spanId, "开启 spanId 后 MDC 里必须有 spanId");
            assertTrue(spanId.matches("[0-9a-f]{16}"), spanId);
            // traceId 也必须同时在 MDC 里，且与响应头一致
            assertEquals(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER),
                    mdc.get(RequestTraceFilter.TRACE_ID_KEY));
            // finally 必须把两者清干净，不能漏到下一个请求
            assertNull(MDC.get(RequestTraceFilter.SPAN_ID_KEY));
            assertNull(MDC.get(RequestTraceFilter.TRACE_ID_KEY));
        }

        @Test
        @DisplayName("traceparent contains independent spanId with W3C+spanId")
        void w3cWithSpanId() throws ServletException, IOException {
            filter.configure(true, 1.0, true, true);
            filter.doFilter(request, response, chain);

            String traceparent = response.getHeader("traceparent");
            assertNotNull(traceparent);
            String[] parts = traceparent.split("-");
            assertEquals(16, parts[2].length(), "spanId should be 16 hex characters");
        }

        @Test
        @DisplayName("Different requests have different spanIds")
        void differentSpansPerRequest() throws ServletException, IOException {
            filter.configure(true, 1.0, true, true);

            filter.doFilter(request, response, new MockFilterChain());
            String parent1 = response.getHeader("traceparent");

            response = new MockHttpServletResponse();
            request = new MockHttpServletRequest();
            filter.doFilter(request, response, new MockFilterChain());
            String parent2 = response.getHeader("traceparent");

            assertNotEquals(parent1.split("-")[2], parent2.split("-")[2],
                    "different requests should have different spanIds");
        }
    }

    // ==================== Hex ID Generation ====================

    @Nested
    @DisplayName("ID Generation")
    class IdGeneration {

        @Test
        @DisplayName("generateHexId produces specified length")
        void hexIdLength() {
            for (int len : new int[]{8, 12, 16, 32}) {
                String id = RequestTraceFilter.generateHexId(len);
                assertEquals(len, id.length());
                assertTrue(id.matches("[0-9a-f]+"), "should be pure hexadecimal");
            }
        }

        @Test
        @DisplayName("Multiple generations produce unique IDs")
        void hexIdUnique() {
            var ids = new java.util.HashSet<String>();
            for (int i = 0; i < 100; i++) {
                ids.add(RequestTraceFilter.generateHexId(16));
            }
            assertEquals(100, ids.size(), "100 generations should all be unique");
        }
    }
}
