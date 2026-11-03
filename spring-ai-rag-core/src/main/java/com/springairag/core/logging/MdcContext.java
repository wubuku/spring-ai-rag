package com.springairag.core.logging;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MDC 上下文传递工具。
 *
 * <p>HTTP 线程上的 traceId 只有在跨线程时显式复制才不会丢：
 * {@code @Async}、依赖调用线程池、调度任务都属于这类边界。
 * 本工具只做「抓取 → 覆盖 → 还原」，不产生业务语义。
 *
 * <p>约定：traceId 只进日志与响应，不进指标标签（避免时间序列爆炸）。
 */
public final class MdcContext {

    /** MDC key：调度任务名。 */
    public static final String JOB_KEY = "job";

    /** MDC key：请求 traceId，与 RequestTraceFilter 保持一致。 */
    public static final String TRACE_ID_KEY = "traceId";

    private MdcContext() {
    }

    /** 抓取当前线程的 MDC 快照；无上下文时返回空 Map。 */
    public static Map<String, String> capture() {
        Map<String, String> current = MDC.getCopyOfContextMap();
        return current == null ? Map.of() : new HashMap<>(current);
    }

    /** 用快照覆盖当前线程 MDC；空快照等价于清空。 */
    public static void restore(Map<String, String> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(snapshot);
    }

    /**
     * 包装异步任务：进入任务时使用提交方的上下文，退出时还原线程原有上下文。
     *
     * <p>还原而不是清空，是为了兼容执行器复用的线程池线程。
     */
    public static Runnable wrap(Runnable task) {
        Map<String, String> captured = capture();
        return () -> {
            Map<String, String> previous = capture();
            restore(captured);
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    /** {@link #wrap(Runnable)} 的 Callable 版本。 */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Map<String, String> captured = capture();
        return () -> {
            Map<String, String> previous = capture();
            restore(captured);
            try {
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }

    /**
     * 包装一次调度任务：每次运行生成独立 traceId，并标记 {@code job=scheduled}。
     *
     * <p>调度任务没有 HTTP 请求上下文，但同一轮的日志仍需要能串起来；
     * traceId 形如 {@code job-<10位十六进制>}，可与请求 traceId 区分。
     */
    public static Runnable wrapScheduled(Runnable task) {
        return () -> {
            Map<String, String> previous = capture();
            MDC.clear();
            MDC.put(TRACE_ID_KEY, "job-" + randomHex(10));
            MDC.put(JOB_KEY, "scheduled");
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    private static String randomHex(int length) {
        byte[] bytes = new byte[(length + 1) / 2];
        ThreadLocalRandom.current().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(length);
        for (byte value : bytes) {
            hex.append(String.format("%02x", value));
        }
        return hex.substring(0, length);
    }
}
