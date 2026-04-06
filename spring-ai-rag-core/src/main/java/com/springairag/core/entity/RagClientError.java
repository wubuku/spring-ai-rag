package com.springairag.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entity for recording client-side errors reported from the WebUI.
 * Enables server-side aggregation and analysis of frontend error patterns.
 */
@Entity
@Table(name = "rag_client_error", indexes = {
    @Index(name = "idx_client_error_timestamp", columnList = "created_at"),
    @Index(name = "idx_client_error_error_type", columnList = "error_type"),
    @Index(name = "idx_client_error_session_id", columnList = "session_id")
})
public class RagClientError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "error_type", nullable = false, length = 256)
    private String errorType;

    @Column(name = "error_message", nullable = false, length = 1024)
    private String errorMessage;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Column(name = "component_stack", columnDefinition = "TEXT")
    private String componentStack;

    @Column(name = "page_url", length = 512)
    private String pageUrl;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public RagClientError() {}

    public RagClientError(String errorType, String errorMessage) {
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        this.createdAt = Instant.now();
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }

    public String getComponentStack() { return componentStack; }
    public void setComponentStack(String componentStack) { this.componentStack = componentStack; }

    public String getPageUrl() { return pageUrl; }
    public void setPageUrl(String pageUrl) { this.pageUrl = pageUrl; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
