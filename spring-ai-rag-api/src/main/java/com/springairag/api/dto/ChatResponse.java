package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponse(
    String answer,
    String traceId,
    List<Map<String, Object>> sources,
    Map<String, Object> metadata
) {}
