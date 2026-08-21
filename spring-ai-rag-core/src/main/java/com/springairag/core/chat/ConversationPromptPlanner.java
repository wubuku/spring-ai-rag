package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.exception.RagException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 按候选模型窗口为历史、工具定义和模式证据分配 prompt 预算。
 */
public final class ConversationPromptPlanner {

    private final RagChatProperties properties;
    private final PromptTokenEstimator estimator;

    public ConversationPromptPlanner(
            RagChatProperties properties,
            PromptTokenEstimator estimator) {
        this.properties = properties;
        this.estimator = estimator;
    }

    public ConversationPromptPlan plan(
            ChatModelRouter.ChatModelCandidate candidate,
            ChatCommand command,
            String mandatoryText,
            List<Message> baseline,
            String selectedSummary,
            List<ToolCallback> callbacks) {
        RagChatProperties.ContextProperties context =
                properties.getContext();
        int contextWindow = candidate.contextWindow() != null
                ? candidate.contextWindow()
                : context.getFallbackContextWindow();
        boolean estimatedWindow = candidate.estimatedModelLimits();
        if (contextWindow < 1) {
            throw contextExceeded("model context window is not positive");
        }
        int outputReserve = candidate.maxTokens() != null
                ? Math.min(context.getOutputReserveTokens(),
                        Math.max(1, candidate.maxTokens()))
                : context.getOutputReserveTokens();
        int safety = context.getSafetyMarginTokens();
        int mandatory = estimator.estimate(mandatoryText);
        int schemaTokens = callbacks == null
                ? 0
                : callbacks.stream()
                        .map(callback -> estimator.estimate(
                                callback.getToolDefinition()))
                        .mapToInt(Integer::intValue)
                        .sum();
        if (schemaTokens > context.getMaxToolSchemaTokens()) {
            throw contextExceeded("tool schema exceeds configured token budget");
        }
        int fixed = mandatory + outputReserve + safety + schemaTokens;
        if (fixed >= contextWindow) {
            throw contextExceeded("mandatory chat prompt exceeds model context window");
        }

        List<String> degraded = new ArrayList<>();
        if (!context.isAdaptivePlanningEnabled()) {
            List<Message> legacyMessages = baseline == null
                    ? List.of()
                    : baseline.stream().filter(java.util.Objects::nonNull).toList();
            int legacyTokens = estimate(legacyMessages);
            if (!legacyMessages.isEmpty() && legacyTokens > context.getMaxHistoryTokens()) {
                degraded.add("adaptive_planning_disabled_history_over_limit");
            }
            return new ConversationPromptPlan(
                    contextWindow,
                    estimatedWindow,
                    outputReserve,
                    safety,
                    mandatory,
                    schemaTokens,
                    modeEvidenceTargetFor(command, context),
                    command.mode() == ChatMode.AGENT
                            ? modeEvidenceTargetFor(command, context)
                            : 0,
                    0,
                    legacyTokens,
                    "",
                    legacyMessages,
                    append(degraded, "adaptive_planning_disabled"));
        }
        int remaining = contextWindow - fixed;
        int modeEvidenceTarget = switch (command.mode()) {
            case KNOWLEDGE -> context.getMinimumModeEvidenceTokens();
            case AGENT -> context.getMinimumModeEvidenceTokens();
            case PLAIN -> 0;
        };
        int modeEvidence = Math.min(modeEvidenceTarget, remaining);
        if (modeEvidence < modeEvidenceTarget) {
            degraded.add("mode_evidence_reserve_reduced");
        }
        remaining -= modeEvidence;

        List<List<Message>> turns = turns(baseline);
        int recentTarget = Math.max(0, context.getMinimumRecentTurns());
        Selection selection = selectRecentTurns(
                turns, recentTarget, remaining, context.getMaxHistoryTokens(),
                degraded);
        remaining -= selection.tokens();

        String summary = selectedSummary == null ? "" : selectedSummary;
        summary = fitText(summary, context.getMaxSummaryTokens());
        int summaryTokens = estimator.estimate(summary);
        if (summaryTokens > 0 && summaryTokens <= remaining) {
            remaining -= summaryTokens;
        } else if (summaryTokens > 0) {
            summary = "";
            summaryTokens = 0;
            degraded.add("summary_omitted");
        }

        Selection additional = selectAdditionalTurns(
                turns, selection.selectedTurnCount(), remaining,
                context.getMaxHistoryTokens() - selection.tokens());
        remaining -= additional.tokens();
        int extraModeEvidence = Math.min(
                Math.max(0, remaining),
                Math.max(0, modeEvidenceMaximum(command, context) - modeEvidence));
        modeEvidence += extraModeEvidence;
        List<Message> selected = new ArrayList<>();
        selected.addAll(additional.messages());
        selected.addAll(selection.messages());
        if (selected.isEmpty() && !turns.isEmpty()) {
            degraded.add("recent_history_omitted");
        }
        return new ConversationPromptPlan(
                contextWindow,
                estimatedWindow,
                outputReserve,
                safety,
                mandatory,
                schemaTokens,
                command.mode() == ChatMode.KNOWLEDGE ? modeEvidence : 0,
                command.mode() == ChatMode.AGENT ? modeEvidence : 0,
                summaryTokens,
                selection.tokens() + additional.tokens(),
                summary,
                selected,
                degraded);
    }

    private Selection selectRecentTurns(
            List<List<Message>> turns,
            int minimumTurns,
            int available,
            int maxHistory,
            List<String> degraded) {
        Deque<List<Message>> selected = new ArrayDeque<>();
        int tokens = 0;
        int count = 0;
        for (int index = turns.size() - 1; index >= 0; index--) {
            List<Message> turn = turns.get(index);
            int turnTokens = estimate(turn);
            if (tokens + turnTokens > available
                    || tokens + turnTokens > maxHistory) {
                if (count < minimumTurns) {
                    degraded.add("history_truncated");
                }
                break;
            }
            selected.addFirst(turn);
            tokens += turnTokens;
            count++;
            if (count >= minimumTurns) {
                break;
            }
        }
        return new Selection(flatten(selected), tokens, count);
    }

    private Selection selectAdditionalTurns(
            List<List<Message>> turns,
            int selectedTurnCount,
            int available,
            int maxHistory) {
        if (turns.isEmpty() || available <= 0 || maxHistory <= 0) {
            return new Selection(List.of(), 0, 0);
        }
        int tokens = 0;
        List<Message> messages = new ArrayList<>();
        int start = Math.max(0, turns.size() - selectedTurnCount - 1);
        for (int index = start; index >= 0; index--) {
            List<Message> turn = turns.get(index);
            int turnTokens = estimate(turn);
            if (tokens + turnTokens > available
                    || tokens + turnTokens > maxHistory) {
                break;
            }
            messages.addAll(0, turn);
            tokens += turnTokens;
        }
        return new Selection(messages, tokens, messages.isEmpty()
                ? 0
                : countTurns(messages));
    }

    private List<List<Message>> turns(List<Message> messages) {
        List<List<Message>> result = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return result;
        }
        List<Message> current = new ArrayList<>();
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            if (message instanceof UserMessage && !current.isEmpty()) {
                result.add(List.copyOf(current));
                current.clear();
            }
            current.add(message);
            if (message instanceof AssistantMessage) {
                result.add(List.copyOf(current));
                current.clear();
            }
        }
        if (!current.isEmpty()) {
            result.add(List.copyOf(current));
        }
        return result;
    }

    private int estimate(List<Message> messages) {
        return messages.stream()
                .mapToInt(estimator::estimate)
                .sum();
    }

    private int countTurns(List<Message> messages) {
        return turns(messages).size();
    }

    private int modeEvidenceTargetFor(
            ChatCommand command,
            RagChatProperties.ContextProperties context) {
        return command.mode() == ChatMode.PLAIN
                ? 0
                : context.getMinimumModeEvidenceTokens();
    }

    private int modeEvidenceMaximum(
            ChatCommand command,
            RagChatProperties.ContextProperties context) {
        return switch (command.mode()) {
            case KNOWLEDGE -> context.getMaxRagContextTokens();
            case AGENT -> Math.max(
                    context.getMinimumModeEvidenceTokens(),
                    estimator.estimate("tool_result_too_large"));
            case PLAIN -> 0;
        };
    }

    private String fitText(String text, int tokenLimit) {
        if (text == null || text.isEmpty() || tokenLimit <= 0) {
            return "";
        }
        if (estimator.estimate(text) <= tokenLimit) {
            return text;
        }
        int low = 0;
        int high = text.length();
        String best = "";
        while (low <= high) {
            int middle = (low + high) >>> 1;
            String candidate = text.substring(0, middle);
            if (estimator.estimate(candidate) <= tokenLimit) {
                best = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return result;
    }

    private List<Message> flatten(Deque<List<Message>> turns) {
        List<Message> result = new ArrayList<>();
        turns.forEach(result::addAll);
        return result;
    }

    private RagException contextExceeded(String message) {
        return new RagException(ErrorCode.CHAT_CONTEXT_BUDGET_EXCEEDED, message);
    }

    private record Selection(List<Message> messages, int tokens, int selectedTurnCount) {
    }
}
