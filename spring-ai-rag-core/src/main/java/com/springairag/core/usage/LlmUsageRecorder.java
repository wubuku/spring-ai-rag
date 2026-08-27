package com.springairag.core.usage;

/**
 * 模型 invocation 终态记录器。
 *
 * <p>实现必须 fail-open：任何记录故障都不能替换原始模型结果或触发 provider retry。</p>
 */
public interface LlmUsageRecorder {

    LlmUsageRecorder NOOP = new LlmUsageRecorder() {
        @Override
        public void record(LlmUsageEvent event) {
        }

        @Override
        public void recordAsync(LlmUsageEvent event) {
        }
    };

    void record(LlmUsageEvent event);

    void recordAsync(LlmUsageEvent event);

    /**
     * Number of events this recorder could not confirm since startup.
     *
     * <p>The value is intentionally instance-local and is not an authorization
     * or durable ledger fact.</p>
     */
    default long lostEvents() {
        return 0L;
    }
}
