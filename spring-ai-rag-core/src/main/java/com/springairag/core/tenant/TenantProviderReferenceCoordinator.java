package com.springairag.core.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * WP8：response-lost 重试的「先查已有 attempt，再允许一次 create」协调器。
 *
 * <p>参考项目案例 05：Provider 已接受但响应丢失时，同一 client token 只能找回原
 * task，不能创建第二个 task；durable PENDING intent 是权威，不信任请求体。</p>
 */
public final class TenantProviderReferenceCoordinator {

    private final TenantDurableCallbackEvidenceStore store;

    public TenantProviderReferenceCoordinator(TenantDurableCallbackEvidenceStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public TenantDurableCallbackEvidenceStore.CallbackReference recoverOrCreate(
            String operation,
            String providerName,
            String clientToken,
            String tenantId,
            String actorId,
            String ownerReference,
            Supplier<Optional<String>> providerQueryByClientToken,
            Supplier<String> providerCreate) {
        Objects.requireNonNull(providerQueryByClientToken, "providerQueryByClientToken must not be null");
        Objects.requireNonNull(providerCreate, "providerCreate must not be null");
        TenantDurableCallbackEvidenceStore.CallbackReference existing = store
                .findReference(tenantId, operation, providerName, clientToken)
                .orElse(null);
        if (existing != null) {
            return recoverPending(existing, providerQueryByClientToken);
        }
        TenantDurableCallbackEvidenceStore.CallbackReference pending =
                new TenantDurableCallbackEvidenceStore.CallbackReference(
                        operation, providerName, clientToken, null,
                        ownerReference, tenantId, actorId, "PENDING");
        if (!store.claimReference(pending)) {
            return recoverPending(store.findReference(
                    tenantId, operation, providerName, clientToken).orElseThrow(),
                    providerQueryByClientToken);
        }
        // 失败是歧义的：PENDING 行留存，重启后只能 query，不得二次 create。
        String providerTaskId = providerCreate.get();
        store.bindProviderTask(tenantId, operation, providerName,
                clientToken, providerTaskId);
        return store.findReference(tenantId, operation, providerName,
                clientToken).orElseThrow();
    }

    private TenantDurableCallbackEvidenceStore.CallbackReference recoverPending(
            TenantDurableCallbackEvidenceStore.CallbackReference existing,
            Supplier<Optional<String>> providerQueryByClientToken) {
        if (existing.providerTaskId() != null) {
            return existing;
        }
        Optional<String> recovered = providerQueryByClientToken.get();
        if (recovered.isPresent()) {
            store.bindProviderTask(existing.tenantId(), existing.operation(),
                    existing.providerName(), existing.clientToken(), recovered.get());
            return store.findReference(existing.tenantId(), existing.operation(),
                    existing.providerName(), existing.clientToken()).orElseThrow();
        }
        return existing;
    }
}
