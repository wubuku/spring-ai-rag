package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * Fire alert response
 *
 * @param alertId Alert ID
 * @param message Operation result message
 */
@Schema(description = "Fire alert response")
public record FireAlertResponse(
        Long alertId,
        String message
) {
    public static FireAlertResponse of(Long alertId) {
        return new FireAlertResponse(alertId, "Alert triggered");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof FireAlertResponse that
                && Objects.equals(this.alertId, that.alertId)
                && Objects.equals(this.message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(alertId, message);
    }

    @Override
    public String toString() {
        return "FireAlertResponse{alertId=" + alertId + ", message=" + message + "}";
    }
}
