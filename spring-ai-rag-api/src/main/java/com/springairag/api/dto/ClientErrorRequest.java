package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Objects;

@Schema(description = "Client-side error report from WebUI")
public class ClientErrorRequest {

    @NotBlank(message = "Error type is required")
    @Size(max = 256, message = "Error type must not exceed 256 characters")
    @Schema(description = "Error type (e.g. Error, TypeError, ReferenceError)", example = "Error")
    private String errorType;

    @NotBlank(message = "Error message is required")
    @Size(max = 1024, message = "Error message must not exceed 1024 characters")
    @Schema(description = "Error message", example = "Cannot read properties of undefined")
    private String errorMessage;

    @Size(max = 8192, message = "Stack trace must not exceed 8192 characters")
    @Schema(description = "JavaScript stack trace")
    private String stackTrace;

    @Size(max = 4096, message = "Component stack must not exceed 4096 characters")
    @Schema(description = "React component stack trace")
    private String componentStack;

    @Size(max = 512, message = "Page URL must not exceed 512 characters")
    @Schema(description = "Page URL where error occurred", example = "/webui/chat")
    private String pageUrl;

    @Size(max = 64, message = "Session ID must not exceed 64 characters")
    @Schema(description = "WebUI session ID")
    private String sessionId;

    @Size(max = 64, message = "User ID must not exceed 64 characters")
    @Schema(description = "User identifier if authenticated")
    private String userId;

    @Schema(description = "Browser user agent")
    private String userAgent;

    public ClientErrorRequest() {}

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

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClientErrorRequest that = (ClientErrorRequest) o;
        return Objects.equals(errorType, that.errorType) &&
                Objects.equals(errorMessage, that.errorMessage) &&
                Objects.equals(stackTrace, that.stackTrace) &&
                Objects.equals(componentStack, that.componentStack) &&
                Objects.equals(pageUrl, that.pageUrl) &&
                Objects.equals(sessionId, that.sessionId) &&
                Objects.equals(userId, that.userId) &&
                Objects.equals(userAgent, that.userAgent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(errorType, errorMessage, stackTrace,
                componentStack, pageUrl, sessionId, userId, userAgent);
    }

    @Override
    public String toString() {
        return "ClientErrorRequest{" +
                "errorType='" + errorType + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                ", pageUrl='" + pageUrl + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", userId='" + userId + '\'' +
                // stackTrace/componentStack/userAgent intentionally excluded from toString (verbose)
                '}';
    }
}
