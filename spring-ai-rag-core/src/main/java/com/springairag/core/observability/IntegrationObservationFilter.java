package com.springairag.core.observability;

import com.springairag.api.enums.IntegrationOperation;
import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * 在认证和限流之外包裹外部业务接入 HTTP 请求，捕获最终 status 和延迟。
 */
public final class IntegrationObservationFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(IntegrationObservationFilter.class);

    private final IntegrationObservationRecorder recorder;
    private final RagProperties properties;
    private final MeterRegistry meterRegistry;

    public IntegrationObservationFilter(
            IntegrationObservationRecorder recorder,
            RagProperties properties,
            MeterRegistry meterRegistry) {
        this.recorder = Objects.requireNonNull(recorder);
        this.properties = Objects.requireNonNull(properties);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
        IntegrationOperation operation = IntegrationOperationClassifier.classify(
                request.getMethod(),
                request.getRequestURI());
        if (operation == null
                || !properties.getIntegrationObservability().isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        long started = System.nanoTime();
        boolean completed = false;
        try {
            filterChain.doFilter(request, response);
            completed = true;
        } finally {
            try {
                recordObservation(request, response, operation, started, completed);
            } catch (RuntimeException failure) {
                log.warn(
                        "Integration request observation failed: operation={}, reason=internal_failure",
                        operation.name());
            }
        }
    }

    private void recordObservation(
            HttpServletRequest request,
            HttpServletResponse response,
            IntegrationOperation operation,
            long started,
            boolean completed) {
        long durationMs = Math.max(
                0,
                TimeUnitNanos.toMillis(System.nanoTime() - started));
        int status = completed ? response.getStatus() : 500;
        IntegrationPrincipalProjection.Projection principal =
                IntegrationPrincipalProjection.from(request);
        IntegrationHttpStatusClass statusClass =
                IntegrationHttpStatusClass.from(status);
        String operationName = operation.name();
        String statusName = statusClass.name();
        String principalType = principal.type();
        Counter.builder("rag.integration.requests")
                .description("External integration HTTP requests")
                .tags("operation", operationName,
                        "status_class", statusName,
                        "principal_type", principalType)
                .register(meterRegistry)
                .increment();
        Timer.builder("rag.integration.request.duration")
                .description("External integration HTTP request duration")
                .tags("operation", operationName,
                        "status_class", statusName,
                        "principal_type", principalType)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
        recorder.record(new IntegrationObservation(
                java.time.Instant.now(),
                principal.type(),
                principal.ref(),
                operation,
                status,
                durationMs,
                IntegrationObservationContext.authorizedCollectionIds(request)));
    }

    private static final class TimeUnitNanos {
        private TimeUnitNanos() {
        }

        static long toMillis(long nanos) {
            return nanos / 1_000_000L;
        }
    }
}
