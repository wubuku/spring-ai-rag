package com.springairag.core.tenant;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * WP8：回调权威处理器（实施规划 §5.4 / §6 WP8）。
 *
 * <p>处理顺序（参考项目 {@code ../seedance-research} 案例 05 推荐合同）：</p>
 * <ol>
 *   <li>解析 operation 对应 contract；无 contract → UNKNOWN；</li>
 *   <li>时间窗校验（replay window）；过期 → REJECTED（对外 UNKNOWN 风格）；</li>
 *   <li>signed contract 先验签再查 receipt（避免未认证请求探测 task 登记，
 *       task-existence oracle）；</li>
 *   <li>receipt 不存在 → UNKNOWN，不触发权威查询；</li>
 *   <li>replay nonce 已消费 → REJECTED（不二次应用）；</li>
 *   <li>unsigned contract → WAKE_UP_ONLY（不采信 body 状态/URL）；</li>
 *   <li>权威 Provider 查询并核对 identity 与 receipt 一致；错配 → UNKNOWN；</li>
 *   <li>audit principal：callback 与 manual resolution 分离。</li>
 * </ol>
 *
 * <p>replay nonce 防护由 {@link TenantDurableCallbackEvidenceStore} 承担
 * （并发/重启下仍幂等）；本处理器只编排顺序。</p>
 */
public final class TenantCallbackProcessor {

    private final Map<String, TenantCallbackContract> contracts = new LinkedHashMap<>();
    private final Map<String, TenantCallbackReceipt> receipts = new LinkedHashMap<>();
    private final Function<TenantCallbackReceipt, TenantProviderResult> authoritativeQuery;
    private final Duration replayWindow;
    private final TenantDurableCallbackEvidenceStore durableStore;

    public TenantCallbackProcessor(
            List<TenantCallbackContract> contracts,
            List<TenantCallbackReceipt> receipts,
            Function<TenantCallbackReceipt, TenantProviderResult> authoritativeQuery,
            Duration replayWindow) {
        // replay nonce 防护是合同的一部分，默认构造也必须自带 durable evidence，
        // 不得留「没有 store 就不防重放」的旁路。
        this(contracts, receipts, authoritativeQuery, replayWindow,
                new TenantDurableCallbackEvidenceStore());
    }

    public TenantCallbackProcessor(
            List<TenantCallbackContract> contracts,
            List<TenantCallbackReceipt> receipts,
            Function<TenantCallbackReceipt, TenantProviderResult> authoritativeQuery,
            Duration replayWindow,
            TenantDurableCallbackEvidenceStore durableStore) {
        Objects.requireNonNull(contracts, "contracts must not be null");
        Objects.requireNonNull(receipts, "receipts must not be null");
        Objects.requireNonNull(authoritativeQuery, "authoritativeQuery must not be null");
        Objects.requireNonNull(replayWindow, "replayWindow must not be null");
        for (TenantCallbackContract contract : contracts) {
            this.contracts.put(contract.operation(), contract);
        }
        for (TenantCallbackReceipt receipt : receipts) {
            this.receipts.put(receiptKey(receipt), receipt);
        }
        this.authoritativeQuery = authoritativeQuery;
        this.replayWindow = replayWindow;
        this.durableStore = durableStore;
    }

    public TenantCallbackDecision process(TenantCallbackRequest request, Instant now) {
        TenantCallbackContract contract =
                request == null ? null : contracts.get(request.operation());
        if (contract == null) {
            return unknown();
        }
        if (request.receivedAt() == null
                || Duration.between(request.receivedAt(), now).abs()
                        .compareTo(replayWindow) > 0) {
            return rejected();
        }
        // 先验签后查 receipt：未认证请求不得探测 task 是否登记。
        if (contract.signed()
                && !TenantCallbackSignature.verify(contract.secret(), request)) {
            return rejected();
        }
        TenantCallbackReceipt receipt = receipts.get(receiptKey(
                request.operation(), request.providerName(), request.providerTaskId()));
        if (receipt == null) {
            return unknown();
        }
        if (!durableStore.claimReplayNonce(
                receipt.tenantId(), request.operation(),
                request.providerName(), request.nonce(),
                receipt.actorId(), auditPrincipal(receipt),
                receipt.ownerReference(),
                contract.signed()
                        ? "CALLBACK_AUTHENTICATED" : "CALLBACK_WAKE_UP")) {
            return rejected();
        }
        if (!contract.signed()) {
            return new TenantCallbackDecision(
                    TenantCallbackDecision.Outcome.WAKE_UP_ONLY, "UNKNOWN", null,
                    receipt, auditPrincipal(receipt), manualAuditPrincipal(receipt));
        }
        TenantProviderResult result = authoritativeQuery.apply(receipt);
        if (result == null
                || !receipt.operation().equals(result.operation())
                || !receipt.providerName().equals(result.providerName())
                || !receipt.providerTaskId().equals(result.providerTaskId())) {
            return unknown();
        }
        return new TenantCallbackDecision(
                TenantCallbackDecision.Outcome.APPLIED, result.status(),
                result.resultUrl(), null, auditPrincipal(receipt),
                manualAuditPrincipal(receipt));
    }

    private static String receiptKey(TenantCallbackReceipt receipt) {
        return receiptKey(receipt.operation(), receipt.providerName(),
                receipt.providerTaskId());
    }

    private static String receiptKey(String operation, String providerName,
                                     String providerTaskId) {
        return operation + "\u0000" + providerName + "\u0000" + providerTaskId;
    }

    private static String auditPrincipal(TenantCallbackReceipt receipt) {
        return TenantCallbackDecision.CALLBACK_AUDIT_PRINCIPAL_PREFIX
                + receipt.providerName();
    }

    private static String manualAuditPrincipal(TenantCallbackReceipt receipt) {
        return TenantCallbackDecision.MANUAL_AUDIT_PRINCIPAL_PREFIX
                + receipt.ownerReference();
    }

    private static TenantCallbackDecision unknown() {
        return new TenantCallbackDecision(
                TenantCallbackDecision.Outcome.UNKNOWN, "UNKNOWN", null,
                null, null, null);
    }

    private static TenantCallbackDecision rejected() {
        return new TenantCallbackDecision(
                TenantCallbackDecision.Outcome.REJECTED, "UNKNOWN", null,
                null, null, null);
    }
}
