package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagClientError entity.
 */
class RagClientErrorTest {

    @Test
    void defaultConstructor_works() {
        RagClientError error = new RagClientError();
        assertNotNull(error);
        assertNull(error.getId());
    }

    @Test
    void twoArgConstructor_setsErrorTypeAndMessage() {
        RagClientError error = new RagClientError("TypeError", "Cannot read property 'x' of undefined");

        assertEquals("TypeError", error.getErrorType());
        assertEquals("Cannot read property 'x' of undefined", error.getErrorMessage());
        assertNotNull(error.getCreatedAt());
    }

    @Test
    void allFields_setAndGet() {
        Instant now = Instant.now();

        RagClientError error = new RagClientError();
        error.setId(1L);
        error.setErrorType("ReferenceError");
        error.setErrorMessage("x is not defined");
        error.setStackTrace("at Function.x (app.js:10:5)\nat Object.y (app.js:20:1)");
        error.setComponentStack("<ErrorBoundary>\n  <App>");
        error.setPageUrl("https://example.com/chat");
        error.setUserAgent("Mozilla/5.0 Chrome/120");
        error.setSessionId("sess-abc123");
        error.setUserId("user-42");
        error.setCreatedAt(now);

        assertEquals(1L, error.getId());
        assertEquals("ReferenceError", error.getErrorType());
        assertEquals("x is not defined", error.getErrorMessage());
        assertTrue(error.getStackTrace().contains("app.js:10"));
        assertTrue(error.getComponentStack().contains("ErrorBoundary"));
        assertEquals("https://example.com/chat", error.getPageUrl());
        assertEquals("Mozilla/5.0 Chrome/120", error.getUserAgent());
        assertEquals("sess-abc123", error.getSessionId());
        assertEquals("user-42", error.getUserId());
        assertEquals(now, error.getCreatedAt());
    }

    @Test
    void onCreate_setsCreatedAtWhenNull() {
        RagClientError error = new RagClientError();
        assertNull(error.getCreatedAt());
        error.onCreate();
        assertNotNull(error.getCreatedAt());
    }

    @Test
    void onCreate_preservesExistingCreatedAt() {
        Instant existing = Instant.parse("2024-01-01T00:00:00Z");
        RagClientError error = new RagClientError();
        error.setCreatedAt(existing);
        error.onCreate();
        assertEquals(existing, error.getCreatedAt());
    }

    @Test
    void optionalFields_canBeNull() {
        RagClientError error = new RagClientError("TypeError", "test");
        error.setStackTrace(null);
        error.setComponentStack(null);
        error.setPageUrl(null);
        error.setUserAgent(null);
        error.setSessionId(null);
        error.setUserId(null);

        assertNull(error.getStackTrace());
        assertNull(error.getComponentStack());
        assertNull(error.getPageUrl());
        assertNull(error.getUserAgent());
        assertNull(error.getSessionId());
        assertNull(error.getUserId());
    }

    @Test
    void twoArgConstructor_createdAtIsNotNull() {
        RagClientError error = new RagClientError("Error", "message");
        assertNotNull(error.getCreatedAt());
    }

    @Test
    void longStackTrace_handled() {
        RagClientError error = new RagClientError();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("    at function").append(i).append(" (file").append(i).append(".js:").append(i * 10).append(":").append(i * 5).append(")\n");
        }
        error.setStackTrace(sb.toString());
        assertTrue(error.getStackTrace().contains("function49"));
        assertTrue(error.getStackTrace().length() > 1000);
    }

    @Test
    void pageUrl_longUrl_handled() {
        RagClientError error = new RagClientError();
        String longUrl = "https://example.com/chat?q=search+term+here&page=1&filter=active&sort=date&order=desc&limit=50&offset=0";
        error.setPageUrl(longUrl);
        assertEquals(longUrl, error.getPageUrl());
    }

    @Test
    void userAgent_variousBrowsers() {
        RagClientError error = new RagClientError();

        error.setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)");
        assertEquals("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)", error.getUserAgent());

        error.setUserAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)");
        assertTrue(error.getUserAgent().contains("iPhone"));
    }
}
