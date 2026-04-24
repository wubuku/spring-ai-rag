package com.springairag.core.service;

import com.springairag.core.entity.RagUserFeedback;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * User feedback service interface.
 *
 * <p>Collects and analyzes user feedback on RAG retrieval results, forming a quality feedback loop:
 * <ul>
 *   <li>Submit feedback (thumbs up / thumbs down / rating)</li>
 *   <li>Feedback distribution statistics</li>
 *   <li>Satisfaction metrics computation</li>
 * </ul>
 */
public interface UserFeedbackService {

    /**
     * Submits user feedback.
     *
     * @param sessionId           session ID
     * @param query               query text
     * @param feedbackType        feedback type: THUMBS_UP / THUMBS_DOWN / RATING
     * @param rating              rating (1-5, optional)
     * @param comment             user comment (optional)
     * @param retrievedDocumentIds retrieved document IDs (optional)
     * @param selectedDocumentIds document IDs user found useful (optional)
     * @param dwellTimeMs         user dwell time in milliseconds (optional)
     * @return saved feedback record
     */
    RagUserFeedback submitFeedback(String sessionId, String query, String feedbackType,
                                   Integer rating, String comment,
                                   List<Long> retrievedDocumentIds, List<Long> selectedDocumentIds,
                                   Long dwellTimeMs);

    /**
     * Gets feedback statistics.
     *
     * @param startDate start date
     * @param endDate   end date
     * @return feedback statistics
     */
    FeedbackStats getStats(ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * Gets feedback history with pagination.
     *
     * @param page page number (0-based)
     * @param size page size
     * @return feedback list
     */
    List<RagUserFeedback> getHistory(int page, int size);

    /**
     * Queries feedback by type.
     *
     * @param feedbackType feedback type
     * @param page        page number
     * @param size        page size
     * @return feedback list
     */
    List<RagUserFeedback> getByType(String feedbackType, int page, int size);

    // ==================== Inner Classes ====================

    /**
     * Feedback statistics.
     */
    class FeedbackStats {
        private long totalFeedbacks;
        private long thumbsUp;
        private long thumbsDown;
        private long ratings;
        private double avgRating;
        private double satisfactionRate;
        private double avgDwellTimeMs;

        public long getTotalFeedbacks() { return totalFeedbacks; }
        public void setTotalFeedbacks(long totalFeedbacks) { this.totalFeedbacks = totalFeedbacks; }
        public long getThumbsUp() { return thumbsUp; }
        public void setThumbsUp(long thumbsUp) { this.thumbsUp = thumbsUp; }
        public long getThumbsDown() { return thumbsDown; }
        public void setThumbsDown(long thumbsDown) { this.thumbsDown = thumbsDown; }
        public long getRatings() { return ratings; }
        public void setRatings(long ratings) { this.ratings = ratings; }
        public double getAvgRating() { return avgRating; }
        public void setAvgRating(double avgRating) { this.avgRating = avgRating; }
        public double getSatisfactionRate() { return satisfactionRate; }
        public void setSatisfactionRate(double satisfactionRate) { this.satisfactionRate = satisfactionRate; }
        public double getAvgDwellTimeMs() { return avgDwellTimeMs; }
        public void setAvgDwellTimeMs(double avgDwellTimeMs) { this.avgDwellTimeMs = avgDwellTimeMs; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FeedbackStats that = (FeedbackStats) o;
            return totalFeedbacks == that.totalFeedbacks
                    && thumbsUp == that.thumbsUp
                    && thumbsDown == that.thumbsDown
                    && ratings == that.ratings
                    && Double.compare(that.avgRating, avgRating) == 0
                    && Double.compare(that.satisfactionRate, satisfactionRate) == 0
                    && Double.compare(that.avgDwellTimeMs, avgDwellTimeMs) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(totalFeedbacks, thumbsUp, thumbsDown, ratings, avgRating, satisfactionRate, avgDwellTimeMs);
        }

        @Override
        public String toString() {
            return "FeedbackStats{" +
                    "totalFeedbacks=" + totalFeedbacks +
                    ", thumbsUp=" + thumbsUp +
                    ", thumbsDown=" + thumbsDown +
                    ", ratings=" + ratings +
                    ", satisfactionRate=" + satisfactionRate +
                    '}';
        }
    }
}
