package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record AnswerRequirements(List<Rule> rules) {
    public AnswerRequirements { rules = List.copyOf(rules); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rule(String id, String type, String value, String unit, String field) { }
}
