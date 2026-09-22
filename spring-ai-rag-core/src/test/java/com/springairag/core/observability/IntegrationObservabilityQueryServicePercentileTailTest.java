package com.springairag.core.observability;

import com.springairag.core.config.RagProperties;
import com.springairag.core.observability.IntegrationObservationRepository.Aggregate;
import com.springairag.core.observability.IntegrationObservationRepository.DimensionAggregate;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.springairag.api.dto.IntegrationObservabilityResponse;
import java.lang.reflect.Method;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * IntegrationObservabilityQueryService 百分位与状态分解长尾（Batch
 * 566，JaCoCo 驱动）：percentileUpperBound 的 count=0/各桶命中/溢出
 * 回退 max、toStatusBreakdown 的非法与非数字维度拒绝。
 */
class IntegrationObservabilityQueryServicePercentileTailTest {

    private IntegrationObservabilityQueryService service;

    @BeforeEach
    void setUp() {
        service = new IntegrationObservabilityQueryService(
                mock(IntegrationObservationRepository.class),
                new RagProperties(),
                mock(CollectionIdentityResolver.class),
                null);
    }

    private static Aggregate aggregate(long le25, long le50, long le100,
                                       long le250, long le500, long le1000,
                                       long le2500, long le5000,
                                       long over5000, long maxMs) {
        return new Aggregate(BigInteger.ONE, java.math.BigDecimal.ONE,
                BigInteger.valueOf(maxMs), BigInteger.valueOf(le25),
                BigInteger.valueOf(le50), BigInteger.valueOf(le100),
                BigInteger.valueOf(le250), BigInteger.valueOf(le500),
                BigInteger.valueOf(le1000), BigInteger.valueOf(le2500),
                BigInteger.valueOf(le5000), BigInteger.valueOf(over5000));
    }

    private static long percentileUpperBound(Aggregate aggregate, long count,
                                             int percentile) throws Exception {
        Method method = IntegrationObservabilityQueryService.class
                .getDeclaredMethod("percentileUpperBound",
                        Aggregate.class, long.class, int.class);
        method.setAccessible(true);
        return (long) method.invoke(null, aggregate, count, percentile);
    }

    private IntegrationObservabilityResponse.StatusBreakdown statusBreakdown(
            String dimension) throws Exception {
        Method method = IntegrationObservabilityQueryService.class
                .getDeclaredMethod("toStatusBreakdown",
                        DimensionAggregate.class);
        method.setAccessible(true);
        try {
            return (IntegrationObservabilityResponse.StatusBreakdown)
                    method.invoke(service, new DimensionAggregate(dimension,
                            aggregate(5, 5, 5, 5, 5, 5, 5, 5, 0, 9_000)));
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    @Test
    void percentileReturnsZeroWhenNoSamples() throws Exception {
        assertEquals(0L, percentileUpperBound(
                aggregate(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0, 50));
    }

    @Test
    void percentileMatchesEachLatencyBucket() throws Exception {
        // 桶计数全部达到 rank → 命中最小桶 25。
        assertEquals(25L, percentileUpperBound(
                aggregate(5, 5, 5, 5, 5, 5, 5, 5, 0, 9_000), 5, 50));
        // le25 为 0、le50 达标 → 50。
        assertEquals(50L, percentileUpperBound(
                aggregate(0, 5, 5, 5, 5, 5, 5, 5, 0, 9_000), 5, 50));
        // 只有 le2500 达标 → 2500。
        assertEquals(2_500L, percentileUpperBound(
                aggregate(0, 0, 0, 0, 0, 0, 5, 5, 0, 9_000), 5, 50));
        // 全部桶不足 → 回退 durationMax。
        assertEquals(9_000L, percentileUpperBound(
                aggregate(0, 0, 0, 0, 0, 0, 0, 0, 5, 9_000), 5, 50));
    }

    @Test
    void statusBreakdownMapsDimensionAndClass() throws Exception {
        var breakdown = statusBreakdown("200");

        assertEquals(200, breakdown.httpStatus());
        assertEquals("SUCCESS", breakdown.statusClass());
    }

    @Test
    void statusBreakdownRejectsNonNumericDimension() {
        assertThrows(RuntimeException.class,
                () -> statusBreakdown("abc"));
    }

    @Test
    void statusBreakdownRejectsOutOfRangeStatus() {
        assertThrows(RuntimeException.class,
                () -> statusBreakdown("42"));
        assertThrows(RuntimeException.class,
                () -> statusBreakdown("999"));
    }
}
