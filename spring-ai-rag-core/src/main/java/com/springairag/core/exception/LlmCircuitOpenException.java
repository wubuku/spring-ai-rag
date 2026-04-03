package com.springairag.core.exception;

/**
 * LLM 熔断器打开异常
 *
 * <p>当 LLM 熔断器处于 OPEN 状态时，所有 LLM 调用会立即抛出此异常，
 * 避免请求堆积和资源耗尽。
 *
 * <p>HTTP 状态码：503 Service Unavailable
 * <p>错误码：LLM_CIRCUIT_OPEN
 */
public class LlmCircuitOpenException extends RagException {

    public LlmCircuitOpenException() {
        super("LLM_CIRCUIT_OPEN",
                "LLM service temporarily unavailable due to high failure rate. Please retry later.",
                503);
    }

    public LlmCircuitOpenException(String message) {
        super("LLM_CIRCUIT_OPEN", message, 503);
    }
}
