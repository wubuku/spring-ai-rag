package com.springairag.api.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link ErrorCode} enum.
 */
class ErrorCodeTest {

    @Test
    @DisplayName("All error codes have valid HTTP status in 4xx/5xx range")
    void allCodesHaveValidHttpStatus() {
        for (var code : ErrorCode.values()) {
            int status = code.getHttpStatus();
            assertTrue(status >= 400 && status < 600,
                    () -> code + " has invalid HTTP status: " + status);
        }
    }

    @Test
    @DisplayName("All error codes have non-blank title")
    void allCodesHaveNonBlankTitle() {
        for (var code : ErrorCode.values()) {
            assertNotNull(code.getTitle());
            assertFalse(code.getTitle().isBlank());
        }
    }

    @Test
    @DisplayName("All error codes have valid problem type URI")
    void allCodesHaveValidProblemTypeUri() {
        for (var code : ErrorCode.values()) {
            String uri = code.getProblemTypeUri();
            assertTrue(uri.startsWith("https://springairag.dev/problems/"));
            String suffix = uri.substring("https://springairag.dev/problems/".length());
            assertEquals(code.name().toLowerCase().replace('_', '-'), suffix);
        }
    }

    @Test
    @DisplayName("getCode() returns the enum name")
    void getCodeReturnsEnumName() {
        assertEquals("DOCUMENT_NOT_FOUND", ErrorCode.DOCUMENT_NOT_FOUND.getCode());
        assertEquals("RETRIEVAL_FAILED", ErrorCode.RETRIEVAL_FAILED.getCode());
        assertEquals("LLM_CIRCUIT_OPEN", ErrorCode.LLM_CIRCUIT_OPEN.getCode());
    }

    @Test
    @DisplayName("getTitle() returns human-readable title")
    void getTitleReturnsHumanReadableTitle() {
        assertEquals("Document Not Found", ErrorCode.DOCUMENT_NOT_FOUND.getTitle());
        assertEquals("Retrieval Failed", ErrorCode.RETRIEVAL_FAILED.getTitle());
        assertEquals("LLM Circuit Breaker Open", ErrorCode.LLM_CIRCUIT_OPEN.getTitle());
        assertEquals("Bad Request", ErrorCode.BAD_REQUEST.getTitle());
    }

    @Test
    @DisplayName("ErrorCode can be looked up by name via valueOf")
    void errorCodeValueOfLookup() {
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, ErrorCode.valueOf("DOCUMENT_NOT_FOUND"));
        assertEquals(ErrorCode.BAD_REQUEST, ErrorCode.valueOf("BAD_REQUEST"));
        assertEquals(ErrorCode.LLM_CIRCUIT_OPEN, ErrorCode.valueOf("LLM_CIRCUIT_OPEN"));
    }



    @Test
    @DisplayName("DOCUMENT_NOT_FOUND HTTP status is 404")
    void documentNotFoundIs404() {
        assertEquals(404, ErrorCode.DOCUMENT_NOT_FOUND.getHttpStatus());
    }

    @Test
    @DisplayName("LLM_CIRCUIT_OPEN HTTP status is 503")
    void llmCircuitOpenIs503() {
        assertEquals(503, ErrorCode.LLM_CIRCUIT_OPEN.getHttpStatus());
    }

    @Test
    @DisplayName("INTERNAL_ERROR HTTP status is 500")
    void internalErrorIs500() {
        assertEquals(500, ErrorCode.INTERNAL_ERROR.getHttpStatus());
    }

    @Test
    @DisplayName("TOO_MANY_REQUESTS HTTP status is 429")
    void tooManyRequestsIs429() {
        assertEquals(429, ErrorCode.TOO_MANY_REQUESTS.getHttpStatus());
    }

    @Test
    @DisplayName("TOO_MANY_REQUESTS is the only 429, and it is the name the rate limit filter writes")
    void tooManyRequestsIsTheOnlyRateLimitCode() {
        List<ErrorCode> rateLimits = Arrays.stream(ErrorCode.values())
                .filter(code -> code.getHttpStatus() == 429)
                .toList();
        assertEquals(List.of(ErrorCode.TOO_MANY_REQUESTS), rateLimits);
        // RateLimitFilter's literal, and the value three test classes read back
        // out of `body.get("error")`.
        assertEquals("TOO_MANY_REQUESTS", ErrorCode.TOO_MANY_REQUESTS.getCode());
    }

    // ---- byCodeOrNull -------------------------------------------------------
    //
    // Batch 873 added this for GlobalExceptionHandler, and until this batch
    // nothing tested it directly. Its two interesting branches — a null name,
    // and a name the enum has never heard of — are unreachable from that caller,
    // because every code the handler passes is one the enum does know. A method
    // that "works" only on the path its single caller happens to take is
    // untested, not tested.

    @Test
    @DisplayName("byCodeOrNull resolves every code in the enum")
    void byCodeOrNullResolvesEveryCode() {
        for (ErrorCode code : ErrorCode.values()) {
            assertEquals(code, ErrorCode.byCodeOrNull(code.name()),
                    () -> "round trip failed for " + code);
            assertEquals(code, ErrorCode.byCodeOrNull(code.getCode()),
                    () -> "getCode() name did not resolve for " + code);
        }
    }

    @Test
    @DisplayName("byCodeOrNull returns null for a name the enum does not declare")
    void byCodeOrNullReturnsNullForUnknownName() {
        assertNull(ErrorCode.byCodeOrNull("RATE_LIMIT_EXCEEDED"),
                "the 429 was renamed in Batch 873; the old name must no longer resolve");
        assertNull(ErrorCode.byCodeOrNull("NOT_A_REAL_CODE"));
        assertNull(ErrorCode.byCodeOrNull(""));
    }

    @Test
    @DisplayName("byCodeOrNull returns null for null rather than throwing")
    void byCodeOrNullReturnsNullForNull() {
        assertNull(ErrorCode.byCodeOrNull(null));
    }

    @Test
    @DisplayName("byCodeOrNull is case sensitive, matching valueOf rather than forgiving")
    void byCodeOrNullIsCaseSensitive() {
        // Deliberate: this is a lookup for a wire value the server itself wrote,
        // not user input. Being lenient here would let a typo pass silently in
        // the one place that must not be lenient.
        assertNull(ErrorCode.byCodeOrNull("unauthorized"));
        assertNull(ErrorCode.byCodeOrNull("Unauthorized"));
        assertEquals(ErrorCode.UNAUTHORIZED, ErrorCode.byCodeOrNull("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("every code in the catalog is unique and non-blank")
    void everyCodeIsUniqueAndNonBlank() {
        Set<String> names = new HashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(names.add(code.name()), () -> "duplicate code: " + code.name());
            assertFalse(code.getCode().isBlank());
        }
        assertEquals(ErrorCode.values().length, names.size());
    }
}
