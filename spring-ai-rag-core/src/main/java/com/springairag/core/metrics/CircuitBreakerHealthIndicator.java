package com.springairag.core.metrics;

import com.springairag.core.config.RagChatService;
import com.springairag.core.resilience.LlmCircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM circuit breaker health check indicator.
 *
 * <p>Exposes circuit breaker state via `/actuator/health/llmCircuitBreaker`:
 * <ul>
 *   <li>CLOSED — normal, LLM calls pass through</li>
 *   <li>OPEN — circuit open, LLM calls rejected (fast failure)</li>
 *   <li>HALF_OPEN — probe state, allows one test call</li>
 * </ul>
 *
 * <p>Health check response example:
 * <pre>{@code
 * {
 *   "status": "UP",
 *   "details": {
 *     "state": "CLOSED",
 *     "successes": 150,
 *     "failures": 3,
 *     "failureRate": "2.0%",
 *     "failureRateThreshold": "50%",
 *     "minimumNumberOfCalls": 10,
 *     "windowSize": 20,
 *     "filledSlots": 20,
 *     "lastFailureAgeMs": 0
 *   }
 * }
 * }</pre>
 *
 * <p>When circuit breaker is disabled (circuit-breaker.enabled=false) returns:
 * <pre>{@code
 * {
 *   "status": "UNKNOWN",
 *   "details": { "enabled": false }
 * }
 * }</pre>
 */
@Component("llmCircuitBreaker")
public class CircuitBreakerHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerHealthIndicator.class);

    private final RagChatService ragChatService;

    public CircuitBreakerHealthIndicator(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    @Override
    public Health health() {
        LlmCircuitBreaker cb = ragChatService.getCircuitBreaker();

        if (cb == null) {
            return Health.unknown()
                    .withDetail("enabled", false)
                    .withDetail("state", "NOT_CONFIGURED")
                    .build();
        }

        LlmCircuitBreaker.State state = cb.getState();
        int successes = cb.getSuccesses();
        int failures = cb.getFailures();
        int total = successes + failures;
        double failureRate = total > 0 ? (double) failures / total * 100 : 0.0;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("state", state.name());
        details.put("successes", successes);
        details.put("failures", failures);
        details.put("failureRate", String.format("%.1f%%", failureRate));
        details.put("failureRateThreshold", "50%");
        details.put("minimumNumberOfCalls", 10);
        details.put("windowSize", cb.getFilledSlots() > 0 ? 20 : 0);
        details.put("filledSlots", cb.getFilledSlots());
        details.put("lastFailureAgeMs", state == LlmCircuitBreaker.State.CLOSED
                ? 0 : System.currentTimeMillis() - cb.getLastFailureTimeMillis());

        Health.Builder builder = switch (state) {
            case CLOSED -> Health.up();
            case HALF_OPEN -> Health.unknown();  // probe state: neither up nor down
            case OPEN -> Health.down();
        };

        return builder.withDetails(details).build();
    }
}
