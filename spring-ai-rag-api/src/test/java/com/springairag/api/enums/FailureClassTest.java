package com.springairag.api.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FailureClassTest {

    @Test
    void classifiesStableHttpBoundaries() {
        assertEquals(FailureClass.CLIENT_REQUEST, FailureClass.from(400, null));
        assertEquals(FailureClass.AUTHENTICATION, FailureClass.from(401, null));
        assertEquals(FailureClass.AUTHORIZATION, FailureClass.from(403, null));
        assertEquals(FailureClass.NOT_FOUND, FailureClass.from(404, null));
        assertEquals(FailureClass.CONFLICT, FailureClass.from(409, null));
        assertEquals(FailureClass.RATE_LIMITED, FailureClass.from(429, null));
        assertEquals(FailureClass.DEPENDENCY_UNAVAILABLE, FailureClass.from(503, null));
        assertEquals(FailureClass.TIMEOUT, FailureClass.from(504, null));
    }

    @Test
    void classifiesKnownErrorCodeWithoutInspectingProviderText() {
        assertEquals(FailureClass.TIMEOUT,
                FailureClass.fromErrorCode("provider_timeout"));
        assertEquals(FailureClass.CONFLICT,
                FailureClass.fromErrorCode("IDEMPOTENCY_OPERATION_IN_PROGRESS"));
        assertEquals(FailureClass.INTERNAL,
                FailureClass.fromErrorCode("provider returned an opaque message"));
    }
}
