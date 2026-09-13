package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.exception.ChatTurnInProgressException;
import com.springairag.core.exception.RagException;
import com.springairag.core.security.ApiCapabilitySupport;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GlobalExceptionHandler 覆盖缺口收尾（Batch 363）：媒体类型不
 * 支持、缺失 multipart part、持久化能力策略损坏 503、
 * ChatTurnInProgress 的 Retry-After 头、SecurityException 空消息
 * 兜底。
 */
class GlobalExceptionHandlerCoverageGapsTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        ((MockHttpServletRequest) request).setRequestURI("/api/v1/rag/test");
    }

    @Test
    void mediaTypeNotSupportedReturnsBadRequest() {
        ResponseEntity<ErrorResponse> response = handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException("application/xml"), request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", response.getBody().getTitle());
        assertTrue(response.getBody().getDetail().contains("application/xml"));
    }

    @Test
    void missingMultipartPartReturnsBadRequest() {
        ResponseEntity<ErrorResponse> response = handler.handleMissingPart(
                new MissingServletRequestPartException("file"), request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("MISSING_PART", response.getBody().getTitle());
        assertTrue(response.getBody().getDetail().contains("file"));
    }

    @Test
    void invalidPersistedCapabilitiesReturnsServiceUnavailable() {
        ResponseEntity<ErrorResponse> response =
                handler.handleInvalidPersistedCapabilities(
                        new ApiCapabilitySupport.InvalidPersistedCapabilitiesException(
                                "{\"broken\""), request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("POLICY_SERVICE_UNAVAILABLE", response.getBody().getTitle());
    }

    @Test
    void ragExceptionInProgressTurnAddsRetryAfterHeader() {
        ResponseEntity<ErrorResponse> response = handler.handleRagException(
                new ChatTurnInProgressException(17), request);

        assertEquals("17",
                response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void ragExceptionWithoutInProgressOmitsRetryAfterHeader() {
        ResponseEntity<ErrorResponse> response = handler.handleRagException(
                new RagException(
                        com.springairag.api.enums.ErrorCode.INTERNAL_ERROR,
                        "plain failure"), request);

        assertFalse(response.getHeaders().containsKey(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void securityExceptionNullMessageFallsBackToAccessDenied() {
        ResponseEntity<ErrorResponse> response = handler.handleSecurityException(
                new SecurityException(), request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("FORBIDDEN", response.getBody().getTitle());
        assertEquals("Access denied", response.getBody().getDetail());
    }
}
