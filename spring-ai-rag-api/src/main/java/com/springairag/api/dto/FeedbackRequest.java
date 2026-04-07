package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * User feedback request DTO
 *
 * <p>Used for user feedback on RAG retrieval results and answer quality.
 */
@Schema(description = "User feedback request")
public class FeedbackRequest {

    @NotBlank(message = "Session ID must not be blank")
    @Schema(description = "Session ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sessionId;

    @NotBlank(message = "Query text must not be blank")
    @Size(max = 10000, message = "Query text must not exceed 10000 characters")
    @Schema(description = "Query text", example = "How to configure vector database with Spring AI?", requiredMode = Schema.RequiredMode.REQUIRED)
    private String query;

    @NotBlank(message = "Feedback type must not be blank")
    @Schema(description = "Feedback type: THUMBS_UP / THUMBS_DOWN / RATING", example = "THUMBS_UP", requiredMode = Schema.RequiredMode.REQUIRED)
    private String feedbackType;

    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating must not exceed 5")
    @Schema(description = "Rating (1-5, used when feedbackType=RATING)", example = "4")
    private Integer rating;

    @Size(max = 2000, message = "Comment must not exceed 2000 characters")
    @Schema(description = "User comment")
    private String comment;

    @Schema(description = "List of retrieved document IDs")
    private List<Long> retrievedDocumentIds;

    @Schema(description = "List of document IDs the user found useful")
    private List<Long> selectedDocumentIds;

    @Schema(description = "User dwell time in milliseconds")
    private Long dwellTimeMs;

    public FeedbackRequest() {
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getFeedbackType() { return feedbackType; }
    public void setFeedbackType(String feedbackType) { this.feedbackType = feedbackType; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public List<Long> getRetrievedDocumentIds() { return retrievedDocumentIds; }
    public void setRetrievedDocumentIds(List<Long> retrievedDocumentIds) { this.retrievedDocumentIds = retrievedDocumentIds; }
    public List<Long> getSelectedDocumentIds() { return selectedDocumentIds; }
    public void setSelectedDocumentIds(List<Long> selectedDocumentIds) { this.selectedDocumentIds = selectedDocumentIds; }
    public Long getDwellTimeMs() { return dwellTimeMs; }
    public void setDwellTimeMs(Long dwellTimeMs) { this.dwellTimeMs = dwellTimeMs; }
}
