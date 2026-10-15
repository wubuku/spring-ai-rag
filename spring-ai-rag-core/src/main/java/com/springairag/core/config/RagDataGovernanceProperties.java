package com.springairag.core.config;

/**
 * Retention and bounded-cleanup policy for data categories that did not
 * previously have a single externally discoverable policy.
 */
public class RagDataGovernanceProperties {

    private boolean enabled = true;
    private int auditLogRetentionDays = 400;
    private int clientErrorRetentionDays = 30;
    private int evaluationRetentionDays = 400;
    private boolean cleanupEnabled = true;
    private int cleanupBatchSize = 500;
    private int cleanupMaxBatches = 10;
    private String cleanupCron = "0 40 * * * *";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getAuditLogRetentionDays() { return auditLogRetentionDays; }
    public void setAuditLogRetentionDays(int value) { this.auditLogRetentionDays = value; }
    public int getClientErrorRetentionDays() { return clientErrorRetentionDays; }
    public void setClientErrorRetentionDays(int value) { this.clientErrorRetentionDays = value; }
    public int getEvaluationRetentionDays() { return evaluationRetentionDays; }
    public void setEvaluationRetentionDays(int value) { this.evaluationRetentionDays = value; }
    public boolean isCleanupEnabled() { return cleanupEnabled; }
    public void setCleanupEnabled(boolean cleanupEnabled) { this.cleanupEnabled = cleanupEnabled; }
    public int getCleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int cleanupBatchSize) { this.cleanupBatchSize = cleanupBatchSize; }
    public int getCleanupMaxBatches() { return cleanupMaxBatches; }
    public void setCleanupMaxBatches(int cleanupMaxBatches) { this.cleanupMaxBatches = cleanupMaxBatches; }
    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }

    public void validate() {
        requireDays("audit-log-retention-days", auditLogRetentionDays);
        requireDays("client-error-retention-days", clientErrorRetentionDays);
        requireDays("evaluation-retention-days", evaluationRetentionDays);
        if (cleanupBatchSize < 100 || cleanupBatchSize > 10_000) {
            throw new IllegalArgumentException(
                    "rag.data-governance.cleanup-batch-size must be between 100 and 10000");
        }
        if (cleanupMaxBatches < 1 || cleanupMaxBatches > 100) {
            throw new IllegalArgumentException(
                    "rag.data-governance.cleanup-max-batches must be between 1 and 100");
        }
        if (cleanupCron == null || cleanupCron.isBlank()) {
            throw new IllegalArgumentException(
                    "rag.data-governance.cleanup-cron must not be blank");
        }
    }

    private void requireDays(String name, int value) {
        if (value < 1 || value > 3_650) {
            throw new IllegalArgumentException(
                    "rag.data-governance." + name + " must be between 1 and 3650");
        }
    }
}
