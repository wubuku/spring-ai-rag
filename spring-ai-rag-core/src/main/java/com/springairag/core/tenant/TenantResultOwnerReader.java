package com.springairag.core.tenant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * WP8：结果 owner 合同（实施规划 §5.4 / §6 WP8「任意 task ID 不得触发外部 Provider」）。
 *
 * <p>对齐参考项目 {@code ../seedance-research} 案例 06 的推荐合同：结果 URL 是敏感数据，
 * URL 中的 task ID 不是「调用者有权读取结果」的证明。合同：</p>
 * <ul>
 *   <li>结果必须有持久化 tenant/collection/owner/receipt 关联（缺一拒绝构造）；</li>
 *   <li>先授权 actor 与 tenant，再返回 durable result；</li>
 *   <li>无权、跨租户、未知 ID 统一返回 empty（not-found 语义）；</li>
 *   <li>registry miss / 任意 path ID <b>绝不</b>触发外部 Provider 查询。</li>
 * </ul>
 *
 * <p>本类是合同载体（持久化 receipt 的只读视图）。生产接线点是
 * {@code LongRunningOperationService.load()} 的 {@code owner_principal_id} 过滤 +
 * {@code authorizeResource()} Collection ACL；本仓已有该 owner-scoped SQL，
 * WP8 用反模式/推荐红绿测试把合同锁死。</p>
 */
public final class TenantResultOwnerReader {

    private final Map<String, TenantResultReceipt> receipts;

    public TenantResultOwnerReader(List<TenantResultReceipt> receipts) {
        Objects.requireNonNull(receipts, "receipts must not be null");
        this.receipts = new LinkedHashMap<>();
        for (TenantResultReceipt receipt : receipts) {
            this.receipts.put(receipt.operationId(), receipt);
        }
    }

    /**
     * 注册「不可调用的 Provider 查询」探针。仅用于测试断言推荐路径绝不触发外部查询；
     * 生产实现不得在授权前调用它。
     */
    public TenantResultOwnerReader withProviderQuery(Supplier<?> forbiddenProviderQuery) {
        Objects.requireNonNull(forbiddenProviderQuery, "probe must not be null");
        return this;
    }

    /** 读取结果；无权、跨租户、未知 ID 一律 empty，且绝不触发外部 Provider 查询。 */
    public Optional<String> read(String tenantId, String actorId, String operationId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(operationId, "operationId must not be null");
        TenantResultReceipt receipt = receipts.get(operationId);
        if (receipt == null) {
            return Optional.empty();
        }
        if (!receipt.tenantId().equals(tenantId)) {
            return Optional.empty();
        }
        if (!receipt.ownerPrincipalId().equals(actorId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(receipt.resultUrl());
    }
}
