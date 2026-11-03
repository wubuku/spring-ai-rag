package com.springairag.core.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureClassResponseHeaderFilterTest {

    @Test
    void addsFailureClassHeaderToNativeErrorResponses() throws ServletException, IOException {
        FailureClassResponseHeaderFilter filter = new FailureClassResponseHeaderFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/rag/search");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (ignoredRequest, ignoredResponse) ->
                response.setStatus(503);

        filter.doFilter(request, response, chain);

        assertEquals("DEPENDENCY_UNAVAILABLE",
                response.getHeader(FailureClassHttpSupport.HEADER));
    }

    @Test
    void appliesOnlyToErrorStatusesAndNeverOverwritesAnExistingHeader() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        FailureClassHttpSupport.apply(response, 399, null);
        assertFalse(response.containsHeader(FailureClassHttpSupport.HEADER));

        FailureClassHttpSupport.apply(response, 400, null);
        assertTrue(response.containsHeader(FailureClassHttpSupport.HEADER));

        MockHttpServletResponse preset = new MockHttpServletResponse();
        preset.setHeader(FailureClassHttpSupport.HEADER, "EXISTING");
        FailureClassHttpSupport.apply(preset, 503, null);
        assertEquals("EXISTING", preset.getHeader(FailureClassHttpSupport.HEADER));

        FailureClassHttpSupport.apply(null, 503, null);
    }
}
