package com.springairag.core.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP7 契约红测（实施规划 §5.4 / §6 WP7；外部经验 ../seedance-research/... 案例 03）。
 *
 * <p>覆盖参考项目验证过的四条合同：</p>
 * <ol>
 *   <li>异步/CallerRuns：显式 bootstrap—assert—restore—clear，同线程执行后不残留；</li>
 *   <li>STRICT 无上下文：拒绝执行（不静默读 0 行）；SINGLE：N=1 走 default；</li>
 *   <li>逐租户分发：SINGLE 只跑 default，STRICT 枚举 ACTIVE 租户并汇总失败；</li>
 *   <li>TenantContext.wrap：跨线程传播载荷 tenant，不依赖 ThreadLocal 隐式透传。</li>
 * </ol>
 */
@DisplayName("WP7 后台任务租户作用域契约")
class TenantWp7ContractTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("CallerRuns 同线程：任务看到调用方 tenant，结束后恢复原上下文")
    void callerRunsRestoresCallerContext() {
        TenantContext caller = new TenantContext(
                "tenant-a", "principal-1", TenantMode.STRICT, "audit-1");
        TenantContext.set(caller);

        TenantTaskScopeDecorator decorator =
                new TenantTaskScopeDecorator(TenantMode.STRICT);
        AtomicReference<String> seen = new AtomicReference<>();
        decorator.decorate(() -> seen.set(TenantContext.currentTenantId())).run();

        assertEquals("tenant-a", seen.get());
        assertEquals(caller, TenantContext.current(),
                "CallerRuns 在同线程执行后必须还原调用方上下文");
    }

    @Test
    @DisplayName("SINGLE 无上下文：显式 bootstrap default，结束后清理")
    void singleModeBootstrapsDefaultAndClears() {
        TenantTaskScopeDecorator decorator =
                new TenantTaskScopeDecorator(TenantMode.SINGLE);
        AtomicReference<String> seen = new AtomicReference<>();
        decorator.decorate(() -> seen.set(TenantContext.currentTenantId())).run();

        assertEquals("default", seen.get());
        assertNull(TenantContext.current(), "任务结束后必须清理，不能污染复用线程");
    }

    @Test
    @DisplayName("STRICT 无上下文：拒绝执行（fail-closed），不静默读 0 行")
    void strictModeWithoutContextRefusesExecution() {
        TenantTaskScopeDecorator decorator =
                new TenantTaskScopeDecorator(TenantMode.STRICT);
        AtomicReference<String> seen = new AtomicReference<>("not-run");
        decorator.decorate(() -> seen.set("ran")).run();

        assertEquals("not-run", seen.get());
        assertNull(TenantContext.current());
    }

    @Test
    @DisplayName("scoped() 不得把缺失 tenant 隐式兜成 default")
    void scopedRejectsMissingTenant() {
        TenantJobDispatcher dispatcher = new TenantJobDispatcher();
        assertThrows(IllegalStateException.class,
                () -> dispatcher.scoped(new TenantJob(null, null, null), () -> { }).run());
    }

    @Test
    @DisplayName("dispatchForEachActiveTenant：SINGLE 只跑 default")
    void dispatchForEachActiveTenantSingleOnlyDefault() {
        TenantJobDispatcher dispatcher = new TenantJobDispatcher();
        List<String> seen = new ArrayList<>();
        dispatcher.dispatchForEachActiveTenant(
                () -> List.of("tenant-a", "tenant-b"),
                TenantMode.SINGLE, "principal-1", "audit-1",
                () -> seen.add(TenantContext.currentTenantId()));

        assertEquals(List.of("default"), seen);
        assertNull(TenantContext.current());
    }

    @Test
    @DisplayName("dispatchForEachActiveTenant：STRICT 枚举 ACTIVE 租户，异常汇总、上下文清干净")
    void dispatchForEachActiveTenantStrictEnumeratesAndAggregates() {
        TenantJobDispatcher dispatcher = new TenantJobDispatcher();
        List<String> seen = new ArrayList<>();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatchForEachActiveTenant(
                        () -> List.of("tenant-a", "tenant-b"),
                        TenantMode.STRICT, "principal-1", "audit-1",
                        () -> {
                            String tenant = TenantContext.currentTenantId();
                            seen.add(tenant);
                            if ("tenant-a".equals(tenant)) {
                                throw new IllegalStateException("boom-a");
                            }
                        }));

        assertEquals(List.of("tenant-a", "tenant-b"), seen,
                "单租户失败不得阻断其余租户");
        assertTrue(ex.getMessage().contains("tenant-a"));
        assertNull(TenantContext.current(), "逐租户分发结束后不得残留上下文");
    }

    @Test
    @DisplayName("TenantContext.wrap：新线程拿到提交时的 tenant，结束还原")
    void wrapPropagatesTenantAcrossThreads() throws Exception {
        TenantContext.set(new TenantContext(
                "tenant-a", "principal-1", TenantMode.STRICT, "audit-1"));
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable wrapped = TenantContext.wrap(
                () -> seen.set(TenantContext.currentTenantId()));

        Thread thread = new Thread(wrapped, "wp7-wrap-probe");
        thread.start();
        thread.join(5_000);

        assertEquals("tenant-a", seen.get());
        assertEquals("tenant-a", TenantContext.currentTenantId(),
                "提交线程上下文不得被包装任务改写");
    }
}
