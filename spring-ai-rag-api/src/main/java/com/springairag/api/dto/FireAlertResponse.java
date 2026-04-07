package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
}
