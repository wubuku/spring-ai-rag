package com.springairag.core.config;

/**
 * 检索诊断配置。
 */
public class RagRetrievalDiagnosticsProperties {

    private boolean enabled = true;
    private boolean persist = true;
    private int retentionDays = 7;
    private boolean storeQueryText;
    private int maxDetailBytes = 32_768;
    private int probeTimeoutMs = 1500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPersist() {
        return persist;
    }

    public void setPersist(boolean persist) {
        this.persist = persist;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = Math.max(0, Math.min(365, retentionDays));
    }

    public boolean isStoreQueryText() {
        return storeQueryText;
    }

    public void setStoreQueryText(boolean storeQueryText) {
        this.storeQueryText = storeQueryText;
    }

    public int getMaxDetailBytes() {
        return maxDetailBytes;
    }

    public void setMaxDetailBytes(int maxDetailBytes) {
        this.maxDetailBytes = Math.max(1024, Math.min(262_144, maxDetailBytes));
    }

    public int getProbeTimeoutMs() {
        return probeTimeoutMs;
    }

    public void setProbeTimeoutMs(int probeTimeoutMs) {
        this.probeTimeoutMs = Math.max(100, Math.min(10_000, probeTimeoutMs));
    }
}
