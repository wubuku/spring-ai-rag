package com.springairag.api.dto;

import java.util.Map;

/**
 * Model detail response
 *
 * @param available Whether the model is available
 * @param details Model details (provider/name/displayName, etc.)
 */
public record ModelDetailResponse(
        boolean available,
        Map<String, Object> details
) {
    public static ModelDetailResponse of(boolean available, Map<String, Object> details) {
        return new ModelDetailResponse(available, details);
    }
}
