package com.springairag.core.tenant;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WP8：durable callback evidence（receipt / 幂等 intent / replay nonce / audit principal）。
 *
 * <p>对齐参考项目案例 05 的 durable store 合同：重建 store/processor 后 owner、
 * receipt、replay nonce、response-lost intent 仍有效；callback bootstrap 必须从
 * operation/provider/task 解析出 durable owner，不得由回调 body 自选 tenant。</p>
 *
 * <p>v1 用进程内并发结构表达「持久化证据」的语义合同；生产接线须由数据库唯一约束
 * 承载（本仓已有 {@code rag_long_running_operations} 的 owner + idempotency 唯一约束、
 * {@code rag_alert_notification_delivery} 的 delivery receipt 唯一约束）。WP8 不新增
 * 第三方 Provider 回调端点，不引入真实外部调用。</p>
 */
public final class TenantDurableCallbackEvidenceStore {

    /** 回调引用（response-lost 恢复的 durable intent）。 */
    public record CallbackReference(
            String operation,
            String providerName,
            String clientToken,
            String providerTaskId,
            String ownerReference,
            String tenantId,
            String actorId,
            String status) {
    }

    private final Map<String, CallbackReference> references = new LinkedHashMap<>();
    private final Set<String> replayNonces = ConcurrentHashMap.newKeySet();

    public synchronized Optional<CallbackReference> findReference(
            String tenantId, String operation, String providerName, String clientToken) {
        return Optional.ofNullable(references.get(key(
                tenantId, operation, providerName, clientToken)));
    }

    /** 先查已有 PENDING intent；只有查不到且 claim 成功才允许一次 create。 */
    public synchronized boolean claimReference(CallbackReference pending) {
        Objects.requireNonNull(pending, "pending must not be null");
        String key = key(pending.tenantId(), pending.operation(),
                pending.providerName(), pending.clientToken());
        if (references.containsKey(key)) {
            return false;
        }
        references.put(key, pending);
        return true;
    }

    public synchronized void bindProviderTask(
            String tenantId, String operation, String providerName,
            String clientToken, String providerTaskId) {
        String key = key(tenantId, operation, providerName, clientToken);
        CallbackReference current = references.get(key);
        if (current == null) {
            throw new IllegalStateException("durable callback reference not found");
        }
        references.put(key, new CallbackReference(
                current.operation(), current.providerName(), current.clientToken(),
                providerTaskId, current.ownerReference(), current.tenantId(),
                current.actorId(), "ACCEPTED"));
    }

    /** replay nonce 首次 claim 成功，重复即拒绝（幂等）。 */
    public boolean claimReplayNonce(
            String tenantId, String operation, String providerName, String nonce,
            String actorId, String auditPrincipal, String subjectId, String action) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(auditPrincipal, "auditPrincipal must not be null");
        Objects.requireNonNull(action, "action must not be null");
        return replayNonces.add(key(tenantId, operation, providerName, nonce));
    }

    private static String key(String... parts) {
        return String.join("\u0000", parts);
    }
}
