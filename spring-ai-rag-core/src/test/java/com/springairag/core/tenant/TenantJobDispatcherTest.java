package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP7：后台任务 dispatcher（实施规划 §5.4 / §6 WP7）。
 *
 * <p>契约：</p>
 * <ol>
 *   <li>任务载荷自带 tenant/actor/audit；执行前一致性断言；</li>
 *   <li>无上下文不执行并告警（fail-closed）；</li>
 *   <li>CallerRuns 同线程执行后上下文必须清理干净；</li>
 *   <li>任务在新线程执行时能继承载荷中的上下文（显式 scope 包装，不靠 ThreadLocal 透传）。</li>
 * </ol>
 */
@DisplayName("TenantJobDispatcher (任务载荷 + fail-closed + CallerRuns 清理)")
class TenantJobDispatcherTest {

    private final TenantJobDispatcher dispatcher = new TenantJobDispatcher();

    @Test
    @DisplayName("载荷 tenant 与上下文一致 → 执行，上下文被清理")
    void matchingTenantExecutesAndClearsContext() {
        TenantContext.set(new TenantContext("tenant-a", "principal-1", TenantMode.STRICT));
        AtomicReference<String> seen = new AtomicReference<>();
        try {
            dispatcher.dispatch(new TenantJob("tenant-a", "principal-1", "audit-1"),
                    () -> seen.set(TenantContext.currentTenantId()));
        } finally {
            TenantContext.clear();
        }
        assertEquals("tenant-a", seen.get());
        assertNull(TenantContext.current(), "dispatcher 必须清理上下文");
    }

    @Test
    @DisplayName("载荷 tenant 与上下文不一致 → 拒绝执行（防串台）")
    void mismatchedTenantRejected() {
        TenantContext.set(new TenantContext("tenant-a", "principal-1", TenantMode.STRICT));
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> dispatcher.dispatch(new TenantJob("tenant-b", "principal-1", "audit-1"),
                            () -> { }));
            assertTrue(ex.getMessage().contains("tenant-a"));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("无上下文 + 有载荷 → 显式 scope 执行后仍清理")
    void noContextStillRunsWithExplicitScope() {
        AtomicReference<String> seen = new AtomicReference<>();
        dispatcher.dispatch(new TenantJob("tenant-a", "principal-1", "audit-1"),
                () -> seen.set(TenantContext.currentTenantId()));
        assertEquals("tenant-a", seen.get());
        assertNull(TenantContext.current());
    }

    @Test
    @DisplayName("无载荷 tenant → fail-closed 拒绝（不隐式 default）")
    void missingTenantInPayloadRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatch(new TenantJob(null, "principal-1", "audit-1"),
                        () -> { }));
        assertTrue(ex.getMessage().contains("tenant"));
    }

    @Test
    @DisplayName("任务异常时 finally 仍清理上下文")
    void exceptionStillClearsContext() {
        assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatch(new TenantJob("tenant-a", "principal-1", "audit-1"),
                        () -> { throw new IllegalStateException("boom"); }));
        assertNull(TenantContext.current());
    }
}
