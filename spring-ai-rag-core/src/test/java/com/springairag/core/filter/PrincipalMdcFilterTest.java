package com.springairag.core.filter;

import com.springairag.core.chat.ChatPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 认证后 principal id 写入 MDC，请求结束必须还原，避免线程池串号。
 */
class PrincipalMdcFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void writesAuthenticatedPrincipalAndRestoresPreviousValue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "42");
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);

        AtomicReference<String> seen = new AtomicReference<>();
        MDC.put(PrincipalMdcFilter.PRINCIPAL_ID_KEY, "previous");
        try {
            new PrincipalMdcFilter().doFilter(request, new MockHttpServletResponse(),
                    (req, res) -> seen.set(MDC.get(PrincipalMdcFilter.PRINCIPAL_ID_KEY)));
        } finally {
            MDC.clear();
        }

        assertEquals(ChatPrincipal.DATABASE_KEY_ID_PREFIX + "42", seen.get());
    }

    @Test
    void clearsKeyWhenItWasAbsentBeforeRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");

        new PrincipalMdcFilter().doFilter(request, new MockHttpServletResponse(),
                (req, res) -> assertEquals(ChatPrincipal.AUTH_DISABLED_ID,
                        MDC.get(PrincipalMdcFilter.PRINCIPAL_ID_KEY)));

        assertNull(MDC.get(PrincipalMdcFilter.PRINCIPAL_ID_KEY));
    }
}
