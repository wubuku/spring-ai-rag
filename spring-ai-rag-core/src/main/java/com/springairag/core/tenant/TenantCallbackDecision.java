package com.springairag.core.tenant;

/**
 * WP8：回调处理决策。
 *
 * <ul>
 *   <li>{@code APPLIED}：权威 Provider 结果已应用（body 不参与判定）；</li>
 *   <li>{@code WAKE_UP_ONLY}：unsigned operation，仅唤醒，不采信 body；</li>
 *   <li>{@code REJECTED}：验签失败 / replay / 过期；</li>
 *   <li>{@code UNKNOWN}：无 receipt 或 identity 错配，保持未知不写事实。</li>
 * </ul>
 *
 * <p>callback 与 manual resolution 使用不同 audit principal，避免「回调重试」与
 * 「人工裁决」在审计上不可区分。</p>
 */
public record TenantCallbackDecision(
        Outcome outcome,
        String status,
        String resultUrl,
        TenantCallbackReceipt wakeUpReceipt,
        String auditPrincipal,
        String manualAuditPrincipal) {

    public enum Outcome { APPLIED, WAKE_UP_ONLY, REJECTED, UNKNOWN }

    /** 人工裁决的 audit principal 前缀（与 callback actor 分离）。 */
    public static final String MANUAL_AUDIT_PRINCIPAL_PREFIX = "manual-review:";

    /** 回调 actor 的 audit principal 前缀。 */
    public static final String CALLBACK_AUDIT_PRINCIPAL_PREFIX = "callback:";
}
