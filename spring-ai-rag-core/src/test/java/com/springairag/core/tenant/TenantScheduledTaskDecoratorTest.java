package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP7：调度任务逐租户驱动合同（实施规划 §5.4）。
 *
 * <p>关键点：STRICT 下租户私有定时任务必须<b>逐租户各跑一次</b>，
 * 否则 RLS 会让它读 0 行而静默停摆。</p>
 */
@DisplayName("TenantScheduledTaskDecorator (逐租户驱动调度任务)")
class TenantScheduledTaskDecoratorTest {

    @TenantScopedJob
    static class TenantScopedTask {
        @Scheduled(fixedDelay = 1000)
        void run() {
        }
    }

    static class PlatformTask {
        @Scheduled(fixedDelay = 1000)
        void run() {
        }
    }

    @Test
    @DisplayName("STRICT：租户私有调度任务逐租户各执行一次，且各自在对应租户作用域内")
    void strictRunsPerTenant() throws Exception {
        List<String> seen = new ArrayList<>();
        TenantScheduledTaskDecorator decorator = new TenantScheduledTaskDecorator(
                TenantMode.STRICT, () -> () -> List.of("tenant-a", "tenant-b"));

        Runnable task = () -> seen.add(TenantContext.currentTenantId());
        decorator.runPerTenant(task);

        assertEquals(List.of("tenant-a", "tenant-b"), seen,
                "每个 ACTIVE 租户都要各跑一次");
    }

    @Test
    @DisplayName("STRICT：单租户失败不阻断其余租户")
    void strictFailureDoesNotBlockOthers() {
        List<String> seen = new ArrayList<>();
        TenantScheduledTaskDecorator decorator = new TenantScheduledTaskDecorator(
                TenantMode.STRICT, () -> () -> List.of("tenant-a", "tenant-b"));

        decorator.runPerTenant(() -> {
            String tenant = TenantContext.currentTenantId();
            seen.add(tenant);
            if ("tenant-a".equals(tenant)) {
                throw new IllegalStateException("boom");
            }
        });

        assertEquals(List.of("tenant-a", "tenant-b"), seen);
    }

    @Test
    @DisplayName("SINGLE：只跑一次 default（N=1 特例，不隐式丢租户）")
    void singleRunsDefaultOnce() {
        List<String> seen = new ArrayList<>();
        TenantScheduledTaskDecorator decorator = new TenantScheduledTaskDecorator(
                TenantMode.SINGLE, () -> () -> List.of("tenant-a", "tenant-b"));

        decorator.runPerTenant(() -> seen.add(TenantContext.currentTenantId()));

        assertEquals(List.of("default"), seen);
    }

    @Test
    @DisplayName("STRICT：目录枚举失败 → 跳过本轮（fail-closed，不降级 default）")
    void enumerationFailureIsFailClosed() {
        List<String> seen = new ArrayList<>();
        TenantScheduledTaskDecorator decorator = new TenantScheduledTaskDecorator(
                TenantMode.STRICT, () -> () -> {
                    throw new IllegalStateException("db down");
                });

        decorator.runPerTenant(() -> seen.add(TenantContext.currentTenantId()));

        assertTrue(seen.isEmpty(), "枚举失败不得降级为 default 跑一遍：" + seen);
    }

    @Test
    @DisplayName("只有标注 @TenantScopedJob 的任务才逐租户执行")
    void detectsTenantScopedTarget() throws Exception {
        Method scoped = TenantScopedTask.class.getDeclaredMethod("run");
        Method platform = PlatformTask.class.getDeclaredMethod("run");
        assertTrue(TenantScheduledTaskDecorator.isTenantScoped(
                new ScheduledMethodRunnable(new TenantScopedTask(), scoped)));
        assertTrue(!TenantScheduledTaskDecorator.isTenantScoped(
                new ScheduledMethodRunnable(new PlatformTask(), platform)));
    }
}
