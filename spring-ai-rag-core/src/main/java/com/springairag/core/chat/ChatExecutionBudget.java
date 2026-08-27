package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.http.HttpToolExecutionState;
import com.springairag.core.usage.ChatExecutionAttribution;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 逻辑 Chat 请求共享的有界执行预算。
 *
 * <p>该对象只保存计数和 deadline，不保存 request prompt、工具参数或响应正文。
 * candidate、retry、模型辅助调用和工具循环必须共享同一个实例。</p>
 */
public final class ChatExecutionBudget {

    public static final String CONTEXT_KEY =
            "com.springairag.core.chat.execution-budget";
    public static final String TOOL_RESULT_CHARACTER_LIMITS_CONTEXT_KEY =
            "com.springairag.core.chat.tool-result-character-limits";

    private final Instant deadline;
    private final int maxCandidateAttempts;
    private final int maxModelCalls;
    private final int maxToolRounds;
    private final int maxToolCalls;
    private final int maxToolCallsPerName;
    private final int maxToolResultCharactersTotal;
    private final UUID logicalExecutionId;
    private final String principalId;
    private final String sessionId;
    private final String requestTraceId;
    private final ChatMode chatMode;
    private final AtomicInteger candidateAttempts = new AtomicInteger();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger invocationOrdinals = new AtomicInteger();
    private final AtomicInteger toolRounds = new AtomicInteger();
    private final AtomicInteger totalToolCalls = new AtomicInteger();
    private final AtomicInteger summaryCalls = new AtomicInteger();
    private final AtomicLong toolResultCharacters = new AtomicLong();
    private final AtomicLong toolResultTokens = new AtomicLong();
    private final Map<String, AtomicInteger> toolCallsByName =
            new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> policyToolCallsByName =
            new ConcurrentHashMap<>();
    private final AtomicReference<Map<String, Object>> contextPlan =
            new AtomicReference<>(Map.of());
    private final AtomicReference<HttpToolExecutionState> httpToolState =
            new AtomicReference<>();
    private int reservedToolResultCharacters;

    public ChatExecutionBudget(
            Instant deadline,
            int maxCandidateAttempts,
            int maxModelCalls,
            int maxToolRounds,
            int maxToolCalls,
            int maxToolCallsPerName,
            int maxToolResultCharactersTotal) {
        this(
                deadline,
                maxCandidateAttempts,
                maxModelCalls,
                maxToolRounds,
                maxToolCalls,
                maxToolCallsPerName,
                maxToolResultCharactersTotal,
                UUID.randomUUID(),
                ChatPrincipal.local().id(),
                "legacy",
                null,
                ChatMode.PLAIN);
    }

    public ChatExecutionBudget(
            Instant deadline,
            int maxCandidateAttempts,
            int maxModelCalls,
            int maxToolRounds,
            int maxToolCalls,
            int maxToolCallsPerName,
            int maxToolResultCharactersTotal,
            UUID logicalExecutionId,
            String principalId,
            String sessionId,
            String requestTraceId,
            ChatMode chatMode) {
        this.deadline = deadline != null
                ? deadline
                : Instant.now().plus(Duration.ofMinutes(2));
        this.maxCandidateAttempts = positive(maxCandidateAttempts);
        this.maxModelCalls = positive(maxModelCalls);
        this.maxToolRounds = positive(maxToolRounds);
        this.maxToolCalls = positive(maxToolCalls);
        this.maxToolCallsPerName = positive(maxToolCallsPerName);
        this.maxToolResultCharactersTotal = positive(maxToolResultCharactersTotal);
        this.logicalExecutionId = logicalExecutionId != null
                ? logicalExecutionId : UUID.randomUUID();
        this.principalId = requiredAttribution(principalId, 128, "principalId");
        this.sessionId = requiredAttribution(sessionId, 255, "sessionId");
        this.requestTraceId = optionalAttribution(requestTraceId, 128, "requestTraceId");
        this.chatMode = chatMode != null ? chatMode : ChatMode.PLAIN;
    }

    public boolean tryReserveCandidateAttempt() {
        if (isExpired()) {
            return false;
        }
        return reserve(candidateAttempts, maxCandidateAttempts);
    }

    public int reserveModelCall() {
        ensureDeadline();
        if (!reserve(modelCalls, maxModelCalls)) {
            throw exhausted("model call budget exhausted");
        }
        return invocationOrdinals.incrementAndGet();
    }

    public void recordSummaryCall() {
        summaryCalls.incrementAndGet();
    }

    /**
     * Atomically reserves one tool round and every call in the batch.
     */
    public synchronized void reserveToolBatch(
            List<String> toolNames,
            int maxResultCharactersPerCall) {
        reserveToolBatch(toolNames, Map.of(), maxResultCharactersPerCall);
    }

    /**
     * Atomically reserves one batch using the strictest available per-tool
     * output reservation. Unknown tools use the supplied fallback cap.
     *
     * @return the exact character reservation that must later be settled
     */
    public synchronized int reserveToolBatch(
            List<String> toolNames,
            Map<String, Integer> resultCharacterLimits,
            int fallbackMaxResultCharactersPerCall) {
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
        long reservationLong = 0;
        for (String rawName : names) {
            String name = rawName == null || rawName.isBlank()
                    ? "<unknown>"
                    : rawName;
            int limit = resultCharacterLimits != null
                    ? resultCharacterLimits.getOrDefault(
                            name, fallbackMaxResultCharactersPerCall)
                    : fallbackMaxResultCharactersPerCall;
            reservationLong = Math.addExact(
                    reservationLong, Math.max(1, limit));
        }
        if (reservationLong > Integer.MAX_VALUE) {
            throw exhausted("tool result character reservation overflow");
        }
        int reservation = (int) reservationLong;
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
        return reservation;
    }

    /**
     * Applies a stricter provider policy without retaining completed request
     * budgets in a singleton registry.
     */
    public boolean tryReservePolicyToolCall(String toolName, int maximum) {
        if (toolName == null || toolName.isBlank() || maximum < 1) {
            return false;
        }
        ensureDeadline();
        AtomicInteger counter = policyToolCallsByName
                .computeIfAbsent(toolName, ignored -> new AtomicInteger());
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

    public void recordContextPlan(Map<String, Object> plan) {
        contextPlan.set(plan == null ? Map.of() : Map.copyOf(plan));
    }

    /**
     * Returns the one HTTP byte-budget state shared by every candidate and
     * retry attempt in this logical Chat request.
     */
    HttpToolExecutionState httpToolExecutionState(long maxResponseBytes) {
        long normalizedMaximum = Math.max(1L, maxResponseBytes);
        HttpToolExecutionState existing = httpToolState.get();
        if (existing != null) {
            if (existing.maxResponseBytes() != normalizedMaximum) {
                throw new IllegalStateException(
                        "HTTP tool response budget changed within one Chat request");
            }
            return existing;
        }
        HttpToolExecutionState created =
                new HttpToolExecutionState(normalizedMaximum);
        if (httpToolState.compareAndSet(null, created)) {
            return created;
        }
        return httpToolExecutionState(normalizedMaximum);
    }

    public Map<String, Object> contextPlan() {
        return contextPlan.get();
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

    public UUID logicalExecutionId() {
        return logicalExecutionId;
    }

    public String principalId() {
        return principalId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String requestTraceId() {
        return requestTraceId;
    }

    public ChatMode chatMode() {
        return chatMode;
    }

    public ChatExecutionAttribution attribution(int callOrdinal) {
        return new ChatExecutionAttribution(
                logicalExecutionId,
                callOrdinal,
                principalId,
                sessionId,
                requestTraceId,
                chatMode);
    }

    public boolean hasModelCallCapacity() {
        return !isExpired() && modelCalls.get() < maxModelCalls;
    }

    public int toolRounds() {
        return toolRounds.get();
    }

    public int totalToolCalls() {
        return totalToolCalls.get();
    }

    public int summaryCalls() {
        return summaryCalls.get();
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateAttempts", candidateAttempts());
        result.put("modelCalls", modelCalls());
        result.put("logicalExecutionId", logicalExecutionId.toString());
        result.put("principalId", principalId);
        result.put("sessionId", sessionId);
        if (requestTraceId != null) {
            result.put("requestTraceId", requestTraceId);
        }
        result.put("chatMode", chatMode.name());
        result.put("toolRounds", toolRounds());
        result.put("toolCalls", totalToolCalls());
        result.put("summaryCalls", summaryCalls());
        result.put("toolCallsByName", toolCallsByName());
        result.put("toolResultCharacters", toolResultCharacters());
        result.put("toolResultTokens", toolResultTokens());
        HttpToolExecutionState httpState = httpToolState.get();
        if (httpState != null) {
            result.put("httpResponseBytes", httpState.responseBytes());
            result.put("httpResponseBytesRemaining", httpState.remainingBytes());
        }
        result.put("contextBudget", contextPlan());
        return Map.copyOf(result);
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

    private static String requiredAttribution(
            String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(ch -> ch < 0x20 || ch > 0x7e)) {
            throw new IllegalArgumentException(
                    name + " must contain printable ASCII characters within 1-"
                            + maximum);
        }
        return value;
    }

    private static String optionalAttribution(
            String value, int maximum, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requiredAttribution(value, maximum, name);
    }
}
