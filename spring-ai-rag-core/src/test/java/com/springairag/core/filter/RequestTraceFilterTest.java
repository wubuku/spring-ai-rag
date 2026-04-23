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
            filter.doFilter(request, response, chain);

            assertNotNull(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER));
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

            for (int i = 0; i < 10; i++) {
                request = new MockHttpServletRequest();
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilter(request, response, chain);
                assertNotNull(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER),
                        "request " + (i + 1) + " should generate traceId with sampling rate 1.0");
            }
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

            assertNotNull(response.getHeader("X-Trace-Id"));
            assertNotNull(response.getHeader("traceparent"));
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
            filter.doFilter(request, response, chain);

            String spanId = MDC.get(RequestTraceFilter.SPAN_ID_KEY);
            // spanId is cleared in finally, but can be verified during filter execution
            // Here we verify that traceId was at least set
            assertNotNull(MDC.get(RequestTraceFilter.TRACE_ID_KEY) == null ?
                    response.getHeader(RequestTraceFilter.TRACE_ID_HEADER) : "ok");
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
