package com.springairag.core.config;

/**
 * 文档业务生命周期和外部来源同步兼容配置。
 */
public class RagDocumentLifecycleProperties {

    private boolean strictExternalCas = true;
    private boolean allowNonDefaultNamespace = true;
    private int idempotencyTtlHours = 24;
    private boolean syncRunsEnabled;
    private boolean versionRestoreEnabled;
    private int syncRunMaxMissingAbsolute = 1_000;
    private int syncRunMaxMissingPercent = 20;

    public boolean isStrictExternalCas() { return strictExternalCas; }
    public void setStrictExternalCas(boolean value) { strictExternalCas = value; }

    public boolean isAllowNonDefaultNamespace() {
        return allowNonDefaultNamespace;
    }
    public void setAllowNonDefaultNamespace(boolean value) {
        allowNonDefaultNamespace = value;
    }

    public int getIdempotencyTtlHours() { return idempotencyTtlHours; }
    public void setIdempotencyTtlHours(int value) {
        idempotencyTtlHours = Math.max(1, Math.min(168, value));
    }

    public boolean isSyncRunsEnabled() { return syncRunsEnabled; }
    public void setSyncRunsEnabled(boolean value) { syncRunsEnabled = value; }

    public boolean isVersionRestoreEnabled() { return versionRestoreEnabled; }
    public void setVersionRestoreEnabled(boolean value) { versionRestoreEnabled = value; }

    public int getSyncRunMaxMissingAbsolute() { return syncRunMaxMissingAbsolute; }
    public void setSyncRunMaxMissingAbsolute(int value) {
        syncRunMaxMissingAbsolute = Math.max(1, Math.min(100_000, value));
    }

    public int getSyncRunMaxMissingPercent() { return syncRunMaxMissingPercent; }
    public void setSyncRunMaxMissingPercent(int value) {
        syncRunMaxMissingPercent = Math.max(1, Math.min(100, value));
    }
}
