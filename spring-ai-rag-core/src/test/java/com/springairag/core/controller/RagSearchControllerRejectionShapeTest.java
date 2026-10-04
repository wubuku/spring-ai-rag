package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The four argument rejections in {@link RagSearchController#search}, and the
 * shape of the body they return.
 *
 * <p>Batch 873. All four used to build their body out of {@code detail} alone:
 *
 * <pre>{@code
 * ErrorResponse.builder().detail("Query must not be blank").build()
 * }</pre>
 *
 * which serialises to {@code {detail, message, timestamp}} — no {@code type}, no
 * {@code title}, no {@code status}, no {@code instance}, while
 * {@code ErrorResponse}'s own Javadoc names those five fields and calls itself the
 * format "all API errors" use. A client that switches on {@code error} or
 * {@code type} had no way to tell that apart from a body that was never a
 * problem detail at all.
 *
 * <p>Nothing asserted any of this. A survey for the words in those four messages
 * found no test in the repository, so the shape could have been lost again by any
 * edit. These tests exist so that it cannot be.
 */
class RagSearchControllerRejectionShapeTest {

    private static final String PATH = "/api/v1/rag/search";

    private RagSearchController controller;

    @BeforeEach
    void setUp() {
        controller = new RagSearchController(
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                mock(CollectionRetrievalScopeResolver.class));
    }

    /** Drive the endpoint the way Spring does, with a real request in hand. */
    private ResponseEntity<ErrorResponse> search(
            String query, int limit, double vectorWeight, double fulltextWeight) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        ResponseEntity<?> response = controller.search(
                query, limit, true, vectorWeight, fulltextWeight,
                null, null, request);
        @SuppressWarnings("unchecked")
        ResponseEntity<ErrorResponse> typed = (ResponseEntity<ErrorResponse>) response;
        return typed;
    }

    @Nested
    @DisplayName("every rejection returns a complete problem detail")
    class Shape {

        @Test
        @DisplayName("blank query")
        void blankQuery() {
            ErrorResponse body = search("   ", 10, 0.5, 0.5).getBody();
            assertProblemDetail(body, "Query must not be blank");
        }

        @Test
        @DisplayName("vectorWeight above 1")
        void vectorWeightTooHigh() {
            ErrorResponse body = search("q", 10, 1.5, 0.5).getBody();
            assertProblemDetail(body, "vectorWeight must be between 0.0 and 1.0");
        }

        @Test
        @DisplayName("vectorWeight below 0")
        void vectorWeightTooLow() {
            ErrorResponse body = search("q", 10, -0.1, 0.5).getBody();
            assertProblemDetail(body, "vectorWeight must be between 0.0 and 1.0");
        }

        @Test
        @DisplayName("vectorWeight is not a number")
        void vectorWeightNotFinite() {
            // Double.isFinite, not a range check: NaN compares false against both
            // bounds, so a naive `weight < 0 || weight > 1` would wave it through.
            ErrorResponse body = search("q", 10, Double.NaN, 0.5).getBody();
            assertProblemDetail(body, "vectorWeight must be between 0.0 and 1.0");
        }

        @Test
        @DisplayName("fulltextWeight out of range")
        void fulltextWeightOutOfRange() {
            ErrorResponse body = search("q", 10, 0.5, 2.0).getBody();
            assertProblemDetail(body, "fulltextWeight must be between 0.0 and 1.0");
        }

        @Test
        @DisplayName("limit above the ceiling")
        void limitTooHigh() {
            ErrorResponse body = search("q", 1001, 0.5, 0.5).getBody();
            assertProblemDetail(body, "limit must be between 1 and 1000");
        }

        @Test
        @DisplayName("limit below one")
        void limitTooLow() {
            ErrorResponse body = search("q", 0, 0.5, 0.5).getBody();
            assertProblemDetail(body, "limit must be between 1 and 1000");
        }

        @Test
        @DisplayName("null query")
        void nullQuery() {
            ErrorResponse body = search(null, 10, 0.5, 0.5).getBody();
            assertProblemDetail(body, "Query must not be blank");
        }

        private void assertProblemDetail(ErrorResponse body, String expectedDetailFragment) {
            assertNotNull(body);
            // The three fields ErrorResponse's Javadoc promises, which the old
            // bodies left null.
            assertEquals(ErrorCode.BAD_REQUEST.getCode(), body.getError());
            assertEquals(ErrorCode.BAD_REQUEST.getTitle(), body.getTitle());
            assertEquals(ErrorCode.BAD_REQUEST.getProblemTypeUri(), body.getType());
            assertEquals(400, body.getStatus());
            assertEquals(PATH, body.getInstance());
            // ...and the two backward-compatible aliases, which build() mirrors
            // onto. A client reading either pair sees the same thing.
            assertEquals(PATH, body.getPath());
            assertEquals(body.getDetail(), body.getMessage());
            assertNotNull(body.getTimestamp());
            assertTrue(body.getDetail().contains(expectedDetailFragment),
                    () -> "expected the detail to mention " + expectedDetailFragment
                            + ", got: " + body.getDetail());
        }
    }

    @Nested
    @DisplayName("all four rejections are one condition, not four")
    class SameCondition {

        @Test
        @DisplayName("each rejection reports the same code, title, type and status")
        void oneCodeForOneCondition() {
            ErrorResponse blank = search("  ", 10, 0.5, 0.5).getBody();
            ErrorResponse badWeight = search("q", 10, 9.0, 0.5).getBody();
            ErrorResponse badLimit = search("q", 0, 0.5, 0.5).getBody();

            for (ErrorResponse body : new ErrorResponse[] { blank, badWeight, badLimit }) {
                assertEquals(blank.getError(), body.getError());
                assertEquals(blank.getTitle(), body.getTitle());
                assertEquals(blank.getType(), body.getType());
                assertEquals(blank.getStatus(), body.getStatus());
            }
            // ...while the sentence, which is the useful half, still differs.
            assertTrue(!blank.getDetail().equals(badWeight.getDetail()));
        }

        @Test
        @DisplayName("agrees with what GlobalExceptionHandler says for a 400")
        void agreesWithTheGlobalHandler() {
            // The same condition reached by a different route: a bad argument
            // thrown rather than rejected inline. Two writers, one answer — the
            // reason the controller reaches for ErrorCode instead of writing the
            // strings itself.
            ErrorResponse fromController = search("  ", 10, 0.5, 0.5).getBody();
            ErrorResponse fromHandler = ErrorResponse.of(
                    ErrorCode.BAD_REQUEST, "anything");

            assertEquals(fromHandler.getError(), fromController.getError());
            assertEquals(fromHandler.getTitle(), fromController.getTitle());
            assertEquals(fromHandler.getType(), fromController.getType());
            assertEquals(fromHandler.getStatus(), fromController.getStatus());
        }
    }

    @Nested
    @DisplayName("the request-less overload still answers")
    class NoRequestInHand {

        @Test
        @DisplayName("a null request yields a complete body rather than an NPE")
        void nullRequestIsSurvivable() {
            // Several existing tests reach the endpoint through the five-argument
            // convenience overload, which passes no request. Before Batch 873 that
            // was harmless because the body never asked for the path; now it does,
            // so "no request" has to be a supported answer and not a crash.
            ResponseEntity<?> response = controller.search("  ", 10, true, 0.5, 0.5);
            assertEquals(400, response.getStatusCode().value());

            ErrorResponse body = (ErrorResponse) response.getBody();
            assertNotNull(body);
            assertEquals(ErrorCode.BAD_REQUEST.getCode(), body.getError());
            assertEquals(400, body.getStatus());
            assertEquals(null, body.getInstance());
            assertTrue(body.getDetail().contains("Query must not be blank"));
        }
    }
}
