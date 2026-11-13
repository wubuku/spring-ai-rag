package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5：租户上下文（实施规划 §5.3 / §6 WP5）。
 *
 * <p>契约：</p>
 * <ol>
 *   <li>上下文是线程绑定（ThreadLocal），请求结束必须清理；</li>
 *   <li>事务内禁止切换租户（规划 §4.5）：已进入事务作用域后再 set 另一租户必须拒绝；</li>
 *   <li>无上下文时 {@code current()} 返回 null，供 fail-closed 判定使用。</li>
 * </ol>
 */
@DisplayName("TenantContext (线程绑定 + 事务内禁止切租户)")
class TenantContextTest {

    @Test
    @DisplayName("设置后可读，清理后为 null")
    void setCurrentAndClear() {
        TenantContext context = new TenantContext("tenant-a", "principal-1", TenantMode.STRICT);
        try {
            TenantContext.set(context);
            assertSame(context, TenantContext.current());
            assertEquals("tenant-a", TenantContext.currentTenantId());
        } finally {
            TenantContext.clear();
        }
        assertNull(TenantContext.current());
        assertNull(TenantContext.currentTenantId());
    }

    @Test
    @DisplayName("事务内切换租户必须被拒绝")
    void switchingTenantInsideTransactionIsRejected() {
        TenantContext first = new TenantContext("tenant-a", "principal-1", TenantMode.STRICT);
        TenantContext second = new TenantContext("tenant-b", "principal-2", TenantMode.STRICT);
        try {
            TenantContext.set(first);
            TenantContext.markTransactionScope();
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> TenantContext.set(second));
            assertTrue(ex.getMessage().contains("tenant-a"));
            assertTrue(ex.getMessage().contains("tenant-b"));
            // 拒绝后原上下文必须保持不变
            assertSame(first, TenantContext.current());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("事务作用域外允许切换租户")
    void switchingOutsideTransactionIsAllowed() {
        TenantContext first = new TenantContext("tenant-a", "principal-1", TenantMode.STRICT);
        TenantContext second = new TenantContext("tenant-b", "principal-2", TenantMode.STRICT);
        try {
            TenantContext.set(first);
            TenantContext.set(second);
            assertSame(second, TenantContext.current());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("线程绑定：另一个线程看不到本线程上下文")
    void contextIsThreadBound() throws Exception {
        try {
            TenantContext.set(new TenantContext("tenant-a", "principal-1", TenantMode.STRICT));
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                assertNull(pool.submit(TenantContext::current).get(5, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("清理后事务作用域标记也一并复位")
    void clearResetsTransactionScope() {
        try {
            TenantContext.set(new TenantContext("tenant-a", "principal-1", TenantMode.STRICT));
            TenantContext.markTransactionScope();
            TenantContext.clear();
            // 清理后可以再次设置任意租户
            TenantContext.set(new TenantContext("tenant-b", "principal-2", TenantMode.STRICT));
            assertEquals("tenant-b", TenantContext.currentTenantId());
        } finally {
            TenantContext.clear();
        }
    }
}
