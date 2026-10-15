package com.springairag.core.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
