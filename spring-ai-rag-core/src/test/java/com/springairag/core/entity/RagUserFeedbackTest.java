package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagUserFeedback entity.
 */
class RagUserFeedbackTest {

    @Test
    void defaultValues_createdAtIsNotNull() {
        RagUserFeedback feedback = new RagUserFeedback();
        assertNotNull(feedback.getCreatedAt());
    }

    @Test
    void allFields_setAndGet() {
        ZonedDateTime now = ZonedDateTime.now();

        RagUserFeedback feedback = new RagUserFeedback();
        feedback.setId(1L);
        feedback.setSessionId("session-456");
        feedback.setQuery("How does vector search work?");
        feedback.setRetrievedDocumentIds("[101, 102, 103]");
        feedback.setFeedbackType("THUMBS_UP");
        feedback.setRating(5);
        feedback.setComment("Very helpful answer!");
        feedback.setSelectedDocumentIds("[101]");
        feedback.setDwellTimeMs(15000L);
        feedback.setMetadata(Map.of("page", "/search", "browser", "Chrome"));
        feedback.setCreatedAt(now);

        assertEquals(1L, feedback.getId());
        assertEquals("session-456", feedback.getSessionId());
        assertEquals("How does vector search work?", feedback.getQuery());
        assertEquals("[101, 102, 103]", feedback.getRetrievedDocumentIds());
        assertEquals("THUMBS_UP", feedback.getFeedbackType());
        assertEquals(5, feedback.getRating());
        assertEquals("Very helpful answer!", feedback.getComment());
        assertEquals("[101]", feedback.getSelectedDocumentIds());
        assertEquals(15000L, feedback.getDwellTimeMs());
        assertEquals("/search", feedback.getMetadata().get("page"));
        assertEquals("Chrome", feedback.getMetadata().get("browser"));
        assertEquals(now, feedback.getCreatedAt());
    }

    @Test
    void feedbackTypes() {
        RagUserFeedback feedback = new RagUserFeedback();
        feedback.setFeedbackType("THUMBS_UP");
        assertEquals("THUMBS_UP", feedback.getFeedbackType());

        feedback.setFeedbackType("THUMBS_DOWN");
        assertEquals("THUMBS_DOWN", feedback.getFeedbackType());

        feedback.setFeedbackType("RATING");
        assertEquals("RATING", feedback.getFeedbackType());
    }

    @Test
    void ratingRange() {
        RagUserFeedback feedback = new RagUserFeedback();
        feedback.setRating(1);
        assertEquals(1, feedback.getRating());

        feedback.setRating(3);
        assertEquals(3, feedback.getRating());

        feedback.setRating(5);
        assertEquals(5, feedback.getRating());
    }

    @Test
    void dwellTimeMs_optional() {
        RagUserFeedback feedback = new RagUserFeedback();
        assertNull(feedback.getDwellTimeMs());

        feedback.setDwellTimeMs(30000L);
        assertEquals(30000L, feedback.getDwellTimeMs());
    }

    @Test
    void metadata_jsonMapSerialization() {
        RagUserFeedback feedback = new RagUserFeedback();
        Map<String, Object> metadata = Map.of(
                "page", "/chat",
                "scrollDepth", 0.75,
                "shared", true
        );
        feedback.setMetadata(metadata);

        assertEquals("/chat", feedback.getMetadata().get("page"));
        assertEquals(0.75, feedback.getMetadata().get("scrollDepth"));
        assertEquals(true, feedback.getMetadata().get("shared"));
    }

    @Test
    void optionalFields_canBeNull() {
        RagUserFeedback feedback = new RagUserFeedback();
        feedback.setRating(null);
        feedback.setComment(null);
        feedback.setSelectedDocumentIds(null);
        feedback.setDwellTimeMs(null);
        feedback.setMetadata(null);
        feedback.setRetrievedDocumentIds(null);

        assertNull(feedback.getRating());
        assertNull(feedback.getComment());
        assertNull(feedback.getSelectedDocumentIds());
        assertNull(feedback.getDwellTimeMs());
        assertNull(feedback.getMetadata());
        assertNull(feedback.getRetrievedDocumentIds());
    }

    @Test
    void defaultConstructor_works() {
        RagUserFeedback feedback = new RagUserFeedback();
        assertNotNull(feedback);
        assertNull(feedback.getId());
    }
}
