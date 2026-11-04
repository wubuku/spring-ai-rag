package com.springairag.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时区/Locale 约定的回归测试：
 * - 进程默认时区必须由显式配置决定，而不是宿主机 TZ；
 * - cron 解释必须带显式 zone，且同一表达式在不同 JVM 默认时区下指向同一瞬时；
 * - UTC 墙上时间做 TTL 日算术时不受夏令时影响。
 */
class TimeZoneEnvironmentPostProcessorTest {

    private final ZoneId originalZone = ZoneId.systemDefault();
    private final Locale originalLocale = Locale.getDefault();

    @AfterEach
    void restore() {
        TimeZone.setDefault(TimeZone.getTimeZone(originalZone));
        Locale.setDefault(originalLocale);
    }

    @Test
    void explicitTimeZoneOverridesHostTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

        ZoneId applied = TimeZoneEnvironmentPostProcessor.apply("America/New_York");

        assertEquals(ZoneId.of("America/New_York"), applied);
        assertEquals(ZoneId.of("America/New_York"), ZoneId.systemDefault(),
                "JVM 默认时区必须完全由显式配置决定");
        assertEquals(Locale.ROOT, Locale.getDefault(),
                "默认 Locale 固定 ROOT，避免大小写/数字格式化随宿主机语言漂移");

        // LocalDateTime.now() 现在应该等于纽约墙上时间，与宿主 TZ 无关。
        LocalDateTime expected = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDateTime();
        assertTrue(Math.abs(java.time.Duration.between(expected, LocalDateTime.now()).getSeconds()) <= 1);
    }

    @Test
    void invalidConfiguredZoneFallsBackToUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));

        assertEquals(ZoneId.of("UTC"), TimeZoneEnvironmentPostProcessor.resolve(null));
        assertEquals(ZoneId.of("UTC"), TimeZoneEnvironmentPostProcessor.resolve("  "));
        assertEquals(ZoneId.of("UTC"), TimeZoneEnvironmentPostProcessor.resolve("Not/AZone"));
        assertEquals(ZoneId.of("Asia/Shanghai"), TimeZoneEnvironmentPostProcessor.resolve("Asia/Shanghai"));
    }

    @Test
    void cronWithExplicitZoneTargetsTheSameInstantRegardlessOfJvmDefault() {
        String expression = "0 30 3 * * *"; // 每天 03:30
        CronTrigger scheduled = new CronTrigger(expression, ZoneId.of("Asia/Shanghai"));

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Instant fromUtc = nextFire(scheduled, ZonedDateTime.of(2026, 1, 15, 0, 0, 0, 0, ZoneOffset.UTC).toInstant());

        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        Instant fromNewYork = nextFire(scheduled, ZonedDateTime.of(2026, 1, 15, 0, 0, 0, 0, ZoneOffset.UTC).toInstant());

        assertEquals(fromUtc, fromNewYork,
                "带显式 zone 的 cron 不应随 JVM 默认时区改变触发时刻");
        assertEquals(Instant.parse("2026-01-15T19:30:00Z"), fromUtc);
    }

    /**
     * 夏令时切换日（纽约 2026-03-08 春季前跳、2026-11-01 秋季回拨）对 UTC 日算术没有影响：
     * 清理 cutoff 是“UTC 墙上时间 - N 天”，始终等于 N×24 小时，不会多跑/少跑一小时。
     */
    @Test
    void utcDayArithmeticIsDstNeutral() {
        LocalDate beforeSpringForward = LocalDate.of(2026, 3, 8);
        LocalDate afterSpringForward = LocalDate.of(2026, 3, 9);
        LocalDate beforeFallBack = LocalDate.of(2026, 11, 1);
        LocalDate afterFallBack = LocalDate.of(2026, 11, 2);

        assertEquals(24, java.time.Duration.between(
                beforeSpringForward.atStartOfDay(ZoneOffset.UTC),
                afterSpringForward.atStartOfDay(ZoneOffset.UTC)).toHours());
        assertEquals(24, java.time.Duration.between(
                beforeFallBack.atStartOfDay(ZoneOffset.UTC),
                afterFallBack.atStartOfDay(ZoneOffset.UTC)).toHours());

        // 对照：同样两天的纽约墙钟跨度分别是 23h/25h，这正是不能用本地墙钟做 TTL 的原因。
        ZoneId newYork = ZoneId.of("America/New_York");
        assertEquals(23, java.time.Duration.between(
                beforeSpringForward.atStartOfDay(newYork),
                afterSpringForward.atStartOfDay(newYork)).toHours());
        assertEquals(25, java.time.Duration.between(
                beforeFallBack.atStartOfDay(newYork),
                afterFallBack.atStartOfDay(newYork)).toHours());
    }

    @Test
    void everyCronScheduledMethodDeclaresAnExplicitZone() {
        org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider scanner =
                new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(Scheduled.class));
        scanner.findCandidateComponents("com.springairag.core").forEach(bean -> {
            try {
                Class<?> type = Class.forName(bean.getBeanClassName());
                for (var method : type.getDeclaredMethods()) {
                    Scheduled scheduled = method.getAnnotation(Scheduled.class);
                    if (scheduled != null && !scheduled.cron().isEmpty()) {
                        assertTrue(!scheduled.zone().isEmpty(),
                                method + " 使用 cron 但没有显式 zone，触发时刻会随 JVM 默认时区漂移");
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static Instant nextFire(CronTrigger trigger, Instant from) {
        SimpleTriggerContext context = new SimpleTriggerContext();
        context.update(from, from, from);
        Instant next = trigger.nextExecution(context);
        if (next == null) {
            throw new IllegalStateException("cron 没有下一次触发时间");
        }
        return next;
    }
}
