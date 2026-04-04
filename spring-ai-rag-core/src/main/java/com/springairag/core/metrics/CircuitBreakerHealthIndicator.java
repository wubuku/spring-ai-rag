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
 * LLM 熔断器健康检查指示器
 *
 * <p>通过 `/actuator/health/llmCircuitBreaker` 端点暴露熔断器状态：
 * <ul>
 *   <li>CLOSED — 正常，LLM 调用畅通</li>
 *   <li>OPEN — 熔断中，LLM 调用被拒绝（快速失败）</li>
 *   <li>HALF_OPEN — 探测状态，允许一个测试调用</li>
 * </ul>
 *
 * <p>健康检查结果示例：
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
 * <p>当熔断器未启用（circuit-breaker.enabled=false）时返回：
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
            case HALF_OPEN -> Health.unknown();  // 探测状态，既不是 up 也不是 down
            case OPEN -> Health.down();
        };

        return builder.withDetails(details).build();
    }
}
