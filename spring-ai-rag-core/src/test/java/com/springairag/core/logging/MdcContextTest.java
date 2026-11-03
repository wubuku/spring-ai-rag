package com.springairag.core.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MDC 跨线程传播的回归测试。
 *
 * <p>这些断言对应真实排查场景：请求线程生成的 traceId 必须能在
 * @Async / 依赖调用线程池里看到；调度任务每轮要有自己的 traceId。
 */
class MdcContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void wrapRunnableCarriesTraceIdIntoPoolThreadAndRestoresPoolContext() throws Exception {
        MDC.put(MdcContext.TRACE_ID_KEY, "req-abc");
        MDC.put(PrincipalKey.PRINCIPAL, "db:42");

        AtomicReference<String> insideTrace = new AtomicReference<>();
        AtomicReference<String> insidePrincipal = new AtomicReference<>();
        Runnable wrapped = MdcContext.wrap(() -> {
            insideTrace.set(MDC.get(MdcContext.TRACE_ID_KEY));
            insidePrincipal.set(MDC.get(PrincipalKey.PRINCIPAL));
        });

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            pool.submit(wrapped).get(5, TimeUnit.SECONDS);
            // 第二次提交一个不读上下文的空任务，确认池线程已被还原而不是残留上一轮。
            pool.submit(() -> {
                assertNull(MDC.get(MdcContext.TRACE_ID_KEY));
                assertNull(MDC.get(PrincipalKey.PRINCIPAL));
            }).get(5, TimeUnit.SECONDS);
        }

        assertEquals("req-abc", insideTrace.get());
        assertEquals("db:42", insidePrincipal.get());
        assertEquals("req-abc", MDC.get(MdcContext.TRACE_ID_KEY),
                "提交线程自己的 MDC 不能被异步任务改写");
    }

    @Test
    void wrapCallableReturnsValueWithCallerContext() throws Exception {
        MDC.put(MdcContext.TRACE_ID_KEY, "req-callable");
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<String> result = pool.submit(MdcContext.wrap(
                    () -> MDC.get(MdcContext.TRACE_ID_KEY)));
            assertEquals("req-callable", result.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void scheduledRunGetsFreshJobTraceIdAndClearsAfterwards() throws Exception {
        AtomicReference<String> trace = new AtomicReference<>();
        AtomicReference<String> job = new AtomicReference<>();
        Runnable wrapped = MdcContext.wrapScheduled(() -> {
            trace.set(MDC.get(MdcContext.TRACE_ID_KEY));
            job.set(MDC.get(MdcContext.JOB_KEY));
        });

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            pool.submit(wrapped).get(5, TimeUnit.SECONDS);
            assertNull(MDC.get(MdcContext.TRACE_ID_KEY),
                    "调度任务结束必须清掉 MDC，避免污染复用线程");
        }

        assertTrue(trace.get() != null && trace.get().startsWith("job-"),
                "调度任务 traceId 应以 job- 开头：" + trace.get());
        assertEquals("scheduled", job.get());
    }

    private interface PrincipalKey {
        String PRINCIPAL = "principalId";
    }
}
