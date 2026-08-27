package com.springairag.core.observability;

import com.springairag.api.dto.IntegrationObservabilityResponse;
import com.springairag.api.enums.IntegrationObservabilityBucket;
import com.springairag.api.enums.IntegrationOperation;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.CollectionIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Authorizes and assembles the bounded integration-operation observability view.
 *
 * <p>The service deliberately resolves Collection keys against the current
 * authorization policy before it touches the rollup query. Historical rows do
 * not grant access to a Collection that is no longer visible.</p>
 */
@Service
public final class IntegrationObservabilityQueryService {

    private static final String DATABASE_PRINCIPAL_TYPE =
            ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY;
    private static final String LOCAL_AUTH_DISABLED =
            "LOCAL_AUTH_DISABLED";
    private static final String BEST_EFFORT = "BEST_EFFORT";
    private static final int PRINCIPAL_ID_MAX_LENGTH = 64;

    private final IntegrationObservationRepository repository;
    private final RagProperties properties;
    private final CollectionIdentityResolver collectionIdentityResolver;
    private final IntegrationObservationRecorder recorder;
    private final Clock clock;

    @Autowired
    public IntegrationObservabilityQueryService(
            IntegrationObservationRepository repository,
            RagProperties properties,
            CollectionIdentityResolver collectionIdentityResolver,
            @Autowired(required = false)
            IntegrationObservationRecorder recorder) {
        this(
                repository,
                properties,
                collectionIdentityResolver,
                recorder,
                Clock.systemUTC());
    }

    IntegrationObservabilityQueryService(
            IntegrationObservationRepository repository,
            RagProperties properties,
            CollectionIdentityResolver collectionIdentityResolver,
            IntegrationObservationRecorder recorder,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.collectionIdentityResolver = collectionIdentityResolver;
        this.recorder = recorder;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public IntegrationObservabilityResponse query(
            HttpServletRequest request,
            String fromText,
            String toText,
            String bucketText,
            String operationText,
            String collectionKey,
            String principalId) {
        if (!properties.getIntegrationObservability().isEnabled()) {
            throw new RagException(
                    ErrorCode.INTEGRATION_OBSERVABILITY_DISABLED,
                    "Integration observability is disabled");
        }

        QueryWindow window = parseWindow(fromText, toText);
        IntegrationObservabilityBucket bucket = parseBucket(bucketText);
        IntegrationOperation operation = parseOperation(operationText);
        String requestedCollectionKey = optionalParameter(
                collectionKey, "collectionKey");
        String requestedPrincipalId = optionalParameter(
                principalId, "principalId");

        ScopeResolution scope = resolveScope(
                request, requestedPrincipalId, requestedCollectionKey);
        boolean collectionScoped = requestedCollectionKey != null;

        IntegrationObservationRepository.Aggregate totals =
                repository.totals(
                        window.from(),
                        window.to(),
                        scope.queryPrincipalType(),
                        scope.queryPrincipalRef(),
                        operation,
                        collectionScoped,
                        scope.collectionIds());
        List<IntegrationObservationRepository.DimensionAggregate> statuses =
                repository.byStatus(
                        window.from(),
                        window.to(),
                        scope.queryPrincipalType(),
                        scope.queryPrincipalRef(),
                        operation,
                        collectionScoped,
                        scope.collectionIds());
        List<IntegrationObservationRepository.DimensionAggregate> operations =
                repository.byOperation(
                        window.from(),
                        window.to(),
                        scope.queryPrincipalType(),
                        scope.queryPrincipalRef(),
                        operation,
                        collectionScoped,
                        scope.collectionIds());
        List<IntegrationObservationRepository.CollectionAggregate> collections =
                repository.collectionContributions(
                        window.from(),
                        window.to(),
                        scope.queryPrincipalType(),
                        scope.queryPrincipalRef(),
                        operation,
                        scope.collectionIds(),
                        properties.getIntegrationObservability()
                                .getMaxCollectionBreakdownItems());
        List<IntegrationObservationRepository.TimelineAggregate> timeline =
                repository.timeline(
                        window.from(),
                        window.to(),
                        scope.queryPrincipalType(),
                        scope.queryPrincipalRef(),
                        operation,
                        bucket,
                        collectionScoped,
                        scope.collectionIds());

        Instant oldest = repository.oldestBucket(
                window.from(),
                window.to(),
                scope.queryPrincipalType(),
                scope.queryPrincipalRef(),
                operation,
                collectionScoped,
                scope.collectionIds());

        List<OperationAggregate> validatedOperations = operations.stream()
                .map(this::toOperationAggregate)
                .sorted(Comparator.comparingInt(
                        item -> item.operation().ordinal()))
                .toList();

        return new IntegrationObservabilityResponse(
                new IntegrationObservabilityResponse.Scope(
                        window.from(),
                        window.to(),
                        bucket,
                        scope.responsePrincipalId(),
                        requestedCollectionKey,
                        operation == null ? null : operation.name()),
                new IntegrationObservabilityResponse.Completeness(
                        BEST_EFFORT,
                        true,
                        properties.getIntegrationObservability().retentionDays(),
                        recorder == null ? 0 : recorder.droppedEvents(),
                        oldest),
                toTotals(totals),
                statuses.stream()
                        .map(this::toStatusBreakdown)
                        .sorted(Comparator.comparingInt(
                                IntegrationObservabilityResponse.StatusBreakdown::httpStatus))
                        .toList(),
                validatedOperations.stream()
                        .map(item -> new IntegrationObservabilityResponse.OperationBreakdown(
                                item.operation().name(),
                                toTotals(item.aggregate())))
                        .toList(),
                collections.stream()
                        .map(item -> new IntegrationObservabilityResponse.CollectionContribution(
                                item.collectionKey(),
                                toTotals(item.aggregate())))
                        .toList(),
                timeline.stream()
                        .map(item -> new IntegrationObservabilityResponse.TimelineBucket(
                                item.bucketStart(),
                                toTotals(item.aggregate())))
                        .toList());
    }

    private ScopeResolution resolveScope(
            HttpServletRequest request,
            String requestedPrincipalId,
            String requestedCollectionKey) {
        Object rawPrincipalType = request == null
                ? null
                : request.getAttribute(
                        ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        if (DATABASE_PRINCIPAL_TYPE.equals(rawPrincipalType)
                && !(request.getAttribute(
                        ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE)
                instanceof AuthenticatedApiPrincipal)) {
            throw serviceUnavailable();
        }
        IntegrationPrincipalProjection.Projection caller =
                IntegrationPrincipalProjection.from(request);
        String type = caller.type();

        if (ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC.equals(type)
                || "ANONYMOUS".equals(type)) {
            throw forbidden();
        }

        if (LOCAL_AUTH_DISABLED.equals(type)) {
            if (requestedPrincipalId != null) {
                throw forbidden();
            }
            return new ScopeResolution(
                    null,
                    null,
                    null,
                    resolveCollectionFilter(
                            requestedCollectionKey,
                            null,
                            null));
        }

        if (ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(type)) {
            String queryType = requestedPrincipalId == null
                    ? null : DATABASE_PRINCIPAL_TYPE;
            return new ScopeResolution(
                    queryType,
                    requestedPrincipalId,
                    requestedPrincipalId,
                    resolveCollectionFilter(
                            requestedCollectionKey,
                            null,
                            null));
        }

        if (!DATABASE_PRINCIPAL_TYPE.equals(type)
                || !(request.getAttribute(
                        ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE)
                instanceof AuthenticatedApiPrincipal principal)
                || !caller.ref().equals(principal.getPrincipalId())) {
            throw serviceUnavailable();
        }

        boolean admin = principal.getRole() == ApiKeyRole.ADMIN;
        if (!admin && requestedPrincipalId != null
                && !requestedPrincipalId.equals(principal.getPrincipalId())) {
            throw forbidden();
        }
        String effectivePrincipalId = admin
                ? requestedPrincipalId
                : principal.getPrincipalId();
        String effectivePrincipalType = admin && requestedPrincipalId == null
                ? null : DATABASE_PRINCIPAL_TYPE;
        List<Long> collectionIds = resolveCollectionFilter(
                requestedCollectionKey,
                principal,
                admin ? null : principal);
        return new ScopeResolution(
                effectivePrincipalType,
                effectivePrincipalId,
                effectivePrincipalId,
                collectionIds);
    }

    private List<Long> resolveCollectionFilter(
            String requestedCollectionKey,
            ApiAccessPolicy policy,
            ApiAccessPolicy restrictedPolicy) {
        if (requestedCollectionKey != null) {
            try {
                return List.of(
                        ApiKeyCollectionAccess.requireActiveCollectionByKey(
                                requestedCollectionKey,
                                policy,
                                collectionIdentityResolver).getId());
            } catch (SecurityException error) {
                throw forbidden();
            }
        }
        if (restrictedPolicy == null
                || ApiKeyCollectionAccess.isUnrestricted(restrictedPolicy)) {
            return null;
        }
        try {
            List<Long> allowed = ApiKeyCollectionAccess.parseAllowedIds(
                    restrictedPolicy.getAllowedCollectionIds());
            if (collectionIdentityResolver.mapKeys(allowed).size() != allowed.size()) {
                throw serviceUnavailable();
            }
            return allowed;
        } catch (IllegalStateException error) {
            throw serviceUnavailable();
        }
    }

    private QueryWindow parseWindow(String fromText, String toText) {
        Instant now = clock.instant();
        Instant to = parseInstant(toText, now, "to");
        Instant from = parseInstant(
                fromText,
                to.minus(Duration.ofHours(24)),
                "from");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        Duration maxRange = properties.getIntegrationObservability()
                .getMaxQueryRange();
        try {
            if (Duration.between(from, to).compareTo(maxRange) > 0) {
                throw new IllegalArgumentException(
                        "observability query range must not exceed "
                                + properties.getIntegrationObservability()
                                .maxQueryRangeDays() + " days");
            }
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("observability query range is invalid");
        }
        return new QueryWindow(from, to);
    }

    private Instant parseInstant(
            String value,
            Instant fallback,
            String parameter) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(
                    parameter + " must use an ISO-8601 instant");
        }
    }

    private static IntegrationObservabilityBucket parseBucket(String value) {
        if (value == null || value.isBlank()) {
            return IntegrationObservabilityBucket.HOUR;
        }
        try {
            return IntegrationObservabilityBucket.valueOf(
                    value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("bucket must be HOUR or DAY");
        }
    }

    private static IntegrationOperation parseOperation(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return IntegrationOperation.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("operation is not supported");
        }
    }

    private static String optionalParameter(String value, String name) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > (name.equals("principalId")
                ? PRINCIPAL_ID_MAX_LENGTH : 128)
                || value.chars().anyMatch(ch -> ch < 0x20 || ch > 0x7e)) {
            throw new IllegalArgumentException(
                    name + " must contain printable ASCII characters within its limit");
        }
        return value;
    }

    private IntegrationObservabilityResponse.StatusBreakdown toStatusBreakdown(
            IntegrationObservationRepository.DimensionAggregate item) {
        int status;
        try {
            status = Integer.parseInt(item.dimension());
        } catch (NumberFormatException error) {
            throw serviceUnavailable();
        }
        if (status < 100 || status > 599) {
            throw serviceUnavailable();
        }
        return new IntegrationObservabilityResponse.StatusBreakdown(
                status,
                IntegrationHttpStatusClass.from(status).name(),
                toTotals(item.aggregate()));
    }

    private OperationAggregate toOperationAggregate(
            IntegrationObservationRepository.DimensionAggregate item) {
        try {
            return new OperationAggregate(
                    IntegrationOperation.valueOf(item.dimension()),
                    item.aggregate());
        } catch (IllegalArgumentException error) {
            throw serviceUnavailable();
        }
    }

    private static IntegrationObservabilityResponse.Totals toTotals(
            IntegrationObservationRepository.Aggregate aggregate) {
        long count = toLong(aggregate.requestCount(), "requestCount");
        long durationMax = toLong(
                aggregate.durationMaxMs(), "durationMaxMs");
        BigDecimal average = count == 0
                ? BigDecimal.ZERO
                : aggregate.durationSumMs()
                        .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        return new IntegrationObservabilityResponse.Totals(
                count,
                average,
                durationMax,
                percentileUpperBound(aggregate, count, 50),
                percentileUpperBound(aggregate, count, 95),
                true);
    }

    private static long percentileUpperBound(
            IntegrationObservationRepository.Aggregate aggregate,
            long count,
            int percentile) {
        if (count == 0) {
            return 0;
        }
        BigInteger rank = BigInteger.valueOf(count)
                .multiply(BigInteger.valueOf(percentile))
                .add(BigInteger.valueOf(99))
                .divide(BigInteger.valueOf(100));
        if (aggregate.le25().compareTo(rank) >= 0) return 25;
        if (aggregate.le50().compareTo(rank) >= 0) return 50;
        if (aggregate.le100().compareTo(rank) >= 0) return 100;
        if (aggregate.le250().compareTo(rank) >= 0) return 250;
        if (aggregate.le500().compareTo(rank) >= 0) return 500;
        if (aggregate.le1000().compareTo(rank) >= 0) return 1_000;
        if (aggregate.le2500().compareTo(rank) >= 0) return 2_500;
        if (aggregate.le5000().compareTo(rank) >= 0) return 5_000;
        return toLong(aggregate.durationMaxMs(), "durationMaxMs");
    }

    private static long toLong(BigInteger value, String field) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException error) {
            throw new RagException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "Observability " + field + " exceeds the response range");
        }
    }

    private static RagException forbidden() {
        return new RagException(
                ErrorCode.FORBIDDEN,
                "The current principal is not allowed to query this scope");
    }

    private static RagException serviceUnavailable() {
        return new RagException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "The integration observability scope cannot be resolved completely");
    }

    private record QueryWindow(Instant from, Instant to) {
    }

    private record ScopeResolution(
            String queryPrincipalType,
            String queryPrincipalRef,
            String responsePrincipalId,
            List<Long> collectionIds) {
    }

    private record OperationAggregate(
            IntegrationOperation operation,
            IntegrationObservationRepository.Aggregate aggregate) {
    }
}
