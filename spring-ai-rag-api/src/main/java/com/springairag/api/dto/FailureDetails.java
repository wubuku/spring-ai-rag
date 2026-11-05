package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 上游/依赖失败的诊断信息。
 *
 * <p>{@code upstreamMessage} 是上游错误正文里经过脱敏与截断的一小段真实原因
 * （例如 provider 返回的 {@code rate_limit_error: quota exceeded}）。它只在能
 * 提取到内容时出现，用于区分“429 限流”和“429 配额耗尽/凭据失效”这类
 * 同名状态码的不同根因；不包含请求体，也不会原样透传整段上游响应。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FailureDetails(
        String origin,
        String reason,
        boolean retryable,
        Long retryAfterMs,
        Integer upstreamStatus,
        String upstreamMessage) {

    /** 兼容既有调用点与旧快照：没有上游正文时保持 5 字段语义。 */
    public FailureDetails(String origin, String reason, boolean retryable,
                          Long retryAfterMs, Integer upstreamStatus) {
        this(origin, reason, retryable, retryAfterMs, upstreamStatus, null);
    }
}
