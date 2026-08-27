package com.springairag.core.config;

/**
 * 模型 invocation 持久用量账本配置。
 */
public class RagUsageProperties {

    private boolean enabled = true;
    private String costUnit = "CONFIGURED_MODEL_COST";
    private int retentionDays = 400;
    private boolean cleanupEnabled = true;
    private int cleanupBatchSize = 1_000;
    private int cleanupMaxBatches = 20;
    private String cleanupCron = "0 20 * * * *";
    private int recorderThreads = 2;
    private int recorderQueueCapacity = 1_000;
    private int recordTimeoutMs = 2_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCostUnit() {
        return costUnit;
    }

    public void setCostUnit(String costUnit) {
        this.costUnit = costUnit;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    public void setCleanupEnabled(boolean cleanupEnabled) {
        this.cleanupEnabled = cleanupEnabled;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }

    public int getCleanupMaxBatches() {
        return cleanupMaxBatches;
    }

    public void setCleanupMaxBatches(int cleanupMaxBatches) {
        this.cleanupMaxBatches = cleanupMaxBatches;
    }

    public String getCleanupCron() {
        return cleanupCron;
    }

    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron;
    }

    public int getRecorderThreads() {
        return recorderThreads;
    }

    public void setRecorderThreads(int recorderThreads) {
        this.recorderThreads = recorderThreads;
    }

    public int getRecorderQueueCapacity() {
        return recorderQueueCapacity;
    }

    public void setRecorderQueueCapacity(int recorderQueueCapacity) {
        this.recorderQueueCapacity = recorderQueueCapacity;
    }

    public int getRecordTimeoutMs() {
        return recordTimeoutMs;
    }

    public void setRecordTimeoutMs(int recordTimeoutMs) {
        this.recordTimeoutMs = recordTimeoutMs;
    }

    public void validate() {
        if (retentionDays < 30 || retentionDays > 3_650) {
            throw new IllegalArgumentException(
                    "rag.usage.retention-days must be between 30 and 3650");
        }
        if (cleanupBatchSize < 100 || cleanupBatchSize > 10_000) {
            throw new IllegalArgumentException(
                    "rag.usage.cleanup-batch-size must be between 100 and 10000");
        }
        if (cleanupMaxBatches < 1 || cleanupMaxBatches > 100) {
            throw new IllegalArgumentException(
                    "rag.usage.cleanup-max-batches must be between 1 and 100");
        }
        if (recorderThreads < 1 || recorderThreads > 16) {
            throw new IllegalArgumentException(
                    "rag.usage.recorder-threads must be between 1 and 16");
        }
        if (recorderQueueCapacity < 100 || recorderQueueCapacity > 10_000) {
            throw new IllegalArgumentException(
                    "rag.usage.recorder-queue-capacity must be between 100 and 10000");
        }
        if (recordTimeoutMs < 100 || recordTimeoutMs > 10_000) {
            throw new IllegalArgumentException(
                    "rag.usage.record-timeout-ms must be between 100 and 10000");
        }
        if (costUnit == null || costUnit.isBlank() || costUnit.length() > 32
                || costUnit.chars().anyMatch(ch -> ch < 0x20 || ch > 0x7e)) {
            throw new IllegalArgumentException(
                    "rag.usage.cost-unit must be 1-32 printable ASCII characters");
        }
        if (cleanupCron == null || cleanupCron.isBlank()) {
            throw new IllegalArgumentException(
                    "rag.usage.cleanup-cron must not be blank");
        }
    }
}
