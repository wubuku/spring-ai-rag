package com.springairag.core.usage;

import com.springairag.api.dto.LlmUsageResponse;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.RagProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Validates and assembles the principal-scoped usage aggregation response.
 */
@Service
public class LlmUsageQueryService {

    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int MAX_RANGE_DAYS = 366;

    private final LlmUsageQueryRepository repository;
    private final RagProperties properties;
    private final LlmUsageRecorder recorder;
    private final Clock clock;

    @Autowired
    public LlmUsageQueryService(
            LlmUsageQueryRepository repository,
            RagProperties properties,
            @Autowired(required = false) LlmUsageRecorder recorder) {
        this(repository, properties, recorder, Clock.systemUTC());
    }

    LlmUsageQueryService(
            LlmUsageQueryRepository repository,
            RagProperties properties,
            LlmUsageRecorder recorder,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.recorder = recorder != null ? recorder : LlmUsageRecorder.NOOP;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public LlmUsageResponse query(
            ChatPrincipal caller,
            String fromText,
            String toText,
            String requestedPrincipalId) {
        ChatPrincipal effectiveCaller = caller != null
                ? caller
                : ChatPrincipal.local();
        QueryWindow window = parseWindow(fromText, toText);
        ScopeResolution scope = resolveScope(effectiveCaller, requestedPrincipalId);
        Instant from = window.from().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toExclusive = window.to().plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        LlmUsageQueryRepository.UsageAggregate totals =
                repository.totals(from, toExclusive, scope.queryPrincipalId());
        return new LlmUsageResponse(
                properties.getUsage().isEnabled(),
                recorder.lostEvents(),
                new LlmUsageResponse.Scope(
                        scope.type(),
                        scope.responsePrincipalId()),
                window.from(),
                window.to(),
                totals(totals),
                repository.costs(from, toExclusive, scope.queryPrincipalId()).stream()
                        .map(cost -> new LlmUsageResponse.CostBreakdown(
                                cost.unit(),
                                cost.configuredCost(),
                                cost.invocationCount(),
                                cost.costAvailableCount()))
                        .toList(),
                repository.byModel(from, toExclusive, scope.queryPrincipalId()).stream()
                        .map(item -> new LlmUsageResponse.ModelBreakdown(
                                item.dimension(), totals(item.aggregate())))
                        .toList(),
                repository.byPurpose(from, toExclusive, scope.queryPrincipalId()).stream()
                        .map(item -> new LlmUsageResponse.PurposeBreakdown(
                                item.dimension(), totals(item.aggregate())))
                        .toList(),
                repository.byMode(from, toExclusive, scope.queryPrincipalId()).stream()
                        .map(item -> new LlmUsageResponse.ModeBreakdown(
                                item.dimension(), totals(item.aggregate())))
                        .toList(),
                repository.byDay(from, toExclusive, scope.queryPrincipalId()).stream()
                        .map(item -> new LlmUsageResponse.DayBreakdown(
                                LocalDate.parse(item.dimension()), totals(item.aggregate())))
                        .toList());
    }

    private QueryWindow parseWindow(String fromText, String toText) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate from = parseDate(
                fromText,
                today.minusDays(DEFAULT_RANGE_DAYS - 1),
                "from");
        LocalDate to = parseDate(toText, today, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must be on or after from");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "usage date range must not exceed 366 UTC days");
        }
        return new QueryWindow(from, to);
    }

    private LocalDate parseDate(
            String value,
            LocalDate fallback,
            String parameter) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(
                    parameter + " must use YYYY-MM-DD format");
        }
    }

    private ScopeResolution resolveScope(
            ChatPrincipal caller,
            String requestedPrincipalId) {
        if (requestedPrincipalId == null || requestedPrincipalId.isBlank()) {
            return caller.admin()
                    ? new ScopeResolution("ALL", null, null)
                    : new ScopeResolution("SELF", caller.id(), caller.id());
        }
        validatePrincipalId(requestedPrincipalId);
        if (!caller.admin() && !caller.id().equals(requestedPrincipalId)) {
            throw new SecurityException(
                    "Only root or ADMIN principals can query another principal");
        }
        return new ScopeResolution(
                caller.admin() ? "PRINCIPAL" : "SELF",
                requestedPrincipalId,
                requestedPrincipalId);
    }

    private static void validatePrincipalId(String principalId) {
        if (principalId.length() > 128
                || principalId.chars().anyMatch(ch -> ch < 0x20 || ch > 0x7e)) {
            throw new IllegalArgumentException(
                    "principalId must contain 1-128 printable ASCII characters");
        }
    }

    private static LlmUsageResponse.Totals totals(
            LlmUsageQueryRepository.UsageAggregate aggregate) {
        return new LlmUsageResponse.Totals(
                aggregate.logicalExecutionCount(),
                aggregate.invocationCount(),
                aggregate.succeededCount(),
                aggregate.failedCount(),
                aggregate.cancelledCount(),
                aggregate.promptTokens(),
                aggregate.completionTokens(),
                aggregate.totalTokens(),
                aggregate.usageAvailableCount(),
                aggregate.usageUnavailableCount(),
                aggregate.pricingUnavailableCount(),
                aggregate.costUnavailableCount());
    }

    private record QueryWindow(LocalDate from, LocalDate to) {
    }

    private record ScopeResolution(
            String type,
            String responsePrincipalId,
            String queryPrincipalId) {
    }
}
