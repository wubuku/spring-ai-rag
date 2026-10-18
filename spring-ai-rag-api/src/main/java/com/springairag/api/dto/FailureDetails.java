package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FailureDetails(
        String origin,
        String reason,
        boolean retryable,
        Long retryAfterMs,
        Integer upstreamStatus) {
}
