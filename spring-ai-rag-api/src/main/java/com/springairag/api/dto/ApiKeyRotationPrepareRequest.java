package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 分阶段 credential 轮换的准备请求。 */
@Schema(description = "Prepare a bounded staged API credential rotation")
public class ApiKeyRotationPrepareRequest {

    @Min(1)
    @Max(86400)
    @Schema(
            description = "Requested overlap window in seconds. Omit to use the server default.",
            minimum = "1",
            maximum = "86400",
            nullable = true)
    private Integer overlapSeconds;

    public Integer getOverlapSeconds() {
        return overlapSeconds;
    }

    public void setOverlapSeconds(Integer overlapSeconds) {
        this.overlapSeconds = overlapSeconds;
    }
}
