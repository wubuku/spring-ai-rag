package com.springairag.core.retrieval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个检索阶段的可观察摘要。不保存 stack 或 provider 原始响应。
 */
public record RetrievalBranchStage(
        String branch,
        String provider,
        String status,
        long elapsedMs,
        int candidateCount,
        int resultCount,
        String errorCode) {

    public static final String VECTOR = "VECTOR";
    public static final String FULLTEXT = "FULLTEXT";
    public static final String FUSION = "FUSION";
    public static final String RERANK = "RERANK";

    public static final String SUCCESS = "SUCCESS";
    public static final String DISABLED = "DISABLED";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String ERROR = "ERROR";

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("branch", branch);
        map.put("provider", provider);
        map.put("status", status);
        map.put("elapsedMs", elapsedMs);
        map.put("candidateCount", candidateCount);
        map.put("resultCount", resultCount);
        if (errorCode != null && !errorCode.isBlank()) {
            map.put("errorCode", errorCode);
        }
        return Map.copyOf(map);
    }

    public boolean succeeded() {
        return SUCCESS.equals(status);
    }

    public boolean timedOut() {
        return TIMEOUT.equals(status);
    }

    public boolean failed() {
        return ERROR.equals(status);
    }

    public boolean attempted() {
        return !DISABLED.equals(status);
    }
}
