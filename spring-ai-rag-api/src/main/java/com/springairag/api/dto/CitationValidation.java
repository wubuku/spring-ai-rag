package com.springairag.api.dto;

import java.util.List;

/**
 * 协议级 citation 合法性检查结果，不是自然语言覆盖率分数。
 */
public record CitationValidation(
        String status,
        List<String> availableIds,
        List<String> citedIds,
        List<String> invalidIds,
        int citedSourceCount,
        int sourceCount) {

    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String VALID = "VALID";
    public static final String MISSING_CITATION = "MISSING_CITATION";
    public static final String INVALID_CITATION = "INVALID_CITATION";
    public static final String PARTIAL = "PARTIAL";
}
