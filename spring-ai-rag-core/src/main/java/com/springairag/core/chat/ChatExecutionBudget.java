package com.springairag.core.chat;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 逻辑 Chat 请求共享的有界执行预算。
 *
 * <p>该对象只保存计数和 deadline，不保存 request prompt、工具参数或响应正文。
 * candidate、retry、模型辅助调用和工具循环必须共享同一个实例。</p>
 */
public final class ChatExecutionBudget {

    public static final String CONTEXT_KEY =
            "com.springairag.core.chat.execution-budget";

    private final Instant deadline;
    private final int maxCandidateAttempts;
    private final int maxModelCalls;
    private final int maxToolRounds;
    private final int maxToolCalls;
    private final int maxToolCallsPerName;
    private final int maxToolResultCharactersTotal;
    private final AtomicInteger candidateAttempts = new AtomicInteger();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolRounds = new AtomicInteger();
    private final AtomicInteger totalToolCalls = new AtomicInteger();
    private final AtomicLong toolResultCharacters = new AtomicLong();
    private final AtomicLong toolResultTokens = new AtomicLong();
    private final Map<String, AtomicInteger> toolCallsByName =
            new ConcurrentHashMap<>();
    private int reservedToolResultCharacters;

    public ChatExecutionBudget(
            Instant deadline,
            int maxCandidateAttempts,
            int maxModelCalls,
            int maxToolRounds,
            int maxToolCalls,
            int maxToolCallsPerName,
            int maxToolResultCharactersTotal) {
        this.deadline = deadline != null
                ? deadline
                : Instant.now().plus(Duration.ofMinutes(2));
        this.maxCandidateAttempts = positive(maxCandidateAttempts);
        this.maxModelCalls = positive(maxModelCalls);
        this.maxToolRounds = positive(maxToolRounds);
        this.maxToolCalls = positive(maxToolCalls);
        this.maxToolCallsPerName = positive(maxToolCallsPerName);
        this.maxToolResultCharactersTotal = positive(maxToolResultCharactersTotal);
    }

    public boolean tryReserveCandidateAttempt() {
        if (isExpired()) {
            return false;
        }
        return reserve(candidateAttempts, maxCandidateAttempts);
    }

    public void reserveModelCall() {
        ensureDeadline();
        if (!reserve(modelCalls, maxModelCalls)) {
            throw exhausted("model call budget exhausted");
        }
    }

    /**
     * Atomically reserves one tool round and every call in the batch.
     */
    public synchronized void reserveToolBatch(
            List<String> toolNames,
            int maxResultCharactersPerCall) {
        ensureDeadline();
        List<String> names = toolNames == null ? List.of() : toolNames;
        if (names.isEmpty() || names.size() > maxToolCalls) {
            throw exhausted("tool call batch exceeds the request budget");
        }
        if (toolRounds.get() >= maxToolRounds
                || totalToolCalls.get() + names.size() > maxToolCalls) {
            throw exhausted("tool round or total tool-call budget exhausted");
        }
        Map<String, Integer> additions = new LinkedHashMap<>();
        for (String rawName : names) {
            String name = rawName == null || rawName.isBlank()
                    ? "<unknown>"
                    : rawName;
            int current = toolCallsByName
                    .getOrDefault(name, new AtomicInteger())
                    .get();
            int next = additions.getOrDefault(name, 0) + 1;
            if (current + next > maxToolCallsPerName) {
                throw exhausted("tool-call budget exhausted for " + name);
            }
            additions.put(name, next);
        }
        int reservation = Math.multiplyExact(
                names.size(), Math.max(1, maxResultCharactersPerCall));
        if (toolResultCharacters.get()
                        + reservedToolResultCharacters
                        + reservation
                > maxToolResultCharactersTotal) {
            throw exhausted("tool result character budget exhausted");
        }
        toolRounds.incrementAndGet();
        totalToolCalls.addAndGet(names.size());
        additions.forEach((name, count) ->
                toolCallsByName
                        .computeIfAbsent(name, ignored -> new AtomicInteger())
                        .addAndGet(count));
        reservedToolResultCharacters += reservation;
    }

    /**
     * Settles a previously reserved batch. Counts are deliberately not rolled back
     * when callback execution fails.
     */
    public synchronized void settleToolResults(
            int actualCharacters,
            int actualTokens,
            int reservedCharacters) {
        reservedToolResultCharacters = Math.max(
                0, reservedToolResultCharacters - Math.max(0, reservedCharacters));
        toolResultCharacters.addAndGet(Math.max(0, actualCharacters));
        toolResultTokens.addAndGet(Math.max(0, actualTokens));
    }

    public synchronized void releaseToolReservation(int reservedCharacters) {
        reservedToolResultCharacters = Math.max(
                0, reservedToolResultCharacters - Math.max(0, reservedCharacters));
    }

    public boolean isExpired() {
        return !Instant.now().isBefore(deadline);
    }

    public Instant deadline() {
        return deadline;
    }

    public int candidateAttempts() {
        return candidateAttempts.get();
    }

    public int modelCalls() {
        return modelCalls.get();
    }

    public int toolRounds() {
        return toolRounds.get();
    }

    public int totalToolCalls() {
        return totalToolCalls.get();
    }

    public long toolResultCharacters() {
        return toolResultCharacters.get();
    }

    public long toolResultTokens() {
        return toolResultTokens.get();
    }

    public Map<String, Integer> toolCallsByName() {
        Map<String, Integer> result = new LinkedHashMap<>();
        toolCallsByName.keySet().stream().sorted().forEach(name ->
                result.put(name, toolCallsByName.get(name).get()));
        return Map.copyOf(result);
    }

    public Map<String, Object> snapshot() {
        return Map.of(
                "candidateAttempts", candidateAttempts(),
                "modelCalls", modelCalls(),
                "toolRounds", toolRounds(),
                "toolCalls", totalToolCalls(),
                "toolCallsByName", toolCallsByName(),
                "toolResultCharacters", toolResultCharacters(),
                "toolResultTokens", toolResultTokens());
    }

    private void ensureDeadline() {
        if (isExpired()) {
            throw new RagException(
                    ErrorCode.CHAT_BUDGET_EXHAUSTED,
                    "Chat execution deadline exceeded");
        }
    }

    private RagException exhausted(String message) {
        return new RagException(ErrorCode.CHAT_BUDGET_EXHAUSTED, message);
    }

    private static boolean reserve(AtomicInteger counter, int maximum) {
        while (true) {
            int current = counter.get();
            if (current >= maximum) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private static int positive(int value) {
        return Math.max(1, value);
    }
}
