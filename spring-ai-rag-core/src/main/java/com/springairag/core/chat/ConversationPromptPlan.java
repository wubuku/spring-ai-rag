package com.springairag.core.chat;

import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次模型候选的确定性 prompt 分配结果。
 */
public record ConversationPromptPlan(
        int contextWindow,
        boolean estimatedContextWindow,
        int outputReserveTokens,
        int safetyMarginTokens,
        int mandatoryTokens,
        int toolSchemaTokens,
        int ragReserveTokens,
        int toolResultReserveTokens,
        int summaryTokens,
        int recentHistoryTokens,
        String selectedSummary,
        List<Message> selectedRecentMessages,
        List<String> degradedReasons) {

    public ConversationPromptPlan {
        selectedSummary = selectedSummary == null ? "" : selectedSummary;
        selectedRecentMessages = selectedRecentMessages == null
                ? List.of()
                : List.copyOf(selectedRecentMessages);
        degradedReasons = degradedReasons == null
                ? List.of()
                : List.copyOf(degradedReasons);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contextWindow", contextWindow);
        result.put("estimated", estimatedContextWindow);
        result.put("adaptivePlanningEnabled",
                degradedReasons.stream()
                        .noneMatch("adaptive_planning_disabled"::equals));
        result.put("summaryUsed", !selectedSummary.isBlank());
        result.put("summaryTokens", summaryTokens);
        result.put("historyTokens", recentHistoryTokens);
        result.put("mandatoryTokens", mandatoryTokens);
        result.put("toolSchemaTokens", toolSchemaTokens);
        result.put("ragContextTokens", ragReserveTokens);
        result.put("toolResultTokens", toolResultReserveTokens);
        result.put("outputReserveTokens", outputReserveTokens);
        result.put("safetyMarginTokens", safetyMarginTokens);
        result.put("degradedReasons", new ArrayList<>(degradedReasons));
        return Map.copyOf(result);
    }
}
