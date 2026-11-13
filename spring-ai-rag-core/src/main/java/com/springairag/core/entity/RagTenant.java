package com.springairag.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * WP9：租户主数据（平台全局表 {@code rag_tenant}，无 RLS、无 tenant_id 列）。
 *
 * <p>由平台 capability 端点经 {@code TenantLifecycleService} 读写；业务请求链路只读
 * {@code tenantId} 与 {@code status}（跨租户 discovery 的最小投影）。</p>
 */
@Entity
@Table(name = "rag_tenant")
public class RagTenant {

    @Id
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** WP9：软删标记（不物理删除，保留审计与历史数据）。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
