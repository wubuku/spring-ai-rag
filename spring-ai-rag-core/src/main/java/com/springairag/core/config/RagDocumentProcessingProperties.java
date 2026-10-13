package com.springairag.core.config;

/** 文档处理运行账本和 stuck-task housekeeping 配置。 */
public class RagDocumentProcessingProperties {

    private boolean enabled = true;
    private boolean housekeepingEnabled = true;
    private long housekeepingIntervalMs = 60_000;
    private int stuckThresholdSeconds = 300;
    private int maxHousekeepingRunsPerScan = 100;
    private long stageProgressWriteIntervalMs = 1_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }

    public boolean isHousekeepingEnabled() { return housekeepingEnabled; }
    public void setHousekeepingEnabled(boolean value) { housekeepingEnabled = value; }

    public long getHousekeepingIntervalMs() { return housekeepingIntervalMs; }
    public void setHousekeepingIntervalMs(long value) {
        housekeepingIntervalMs = Math.max(10_000L, value);
    }

    public int getStuckThresholdSeconds() { return stuckThresholdSeconds; }
    public void setStuckThresholdSeconds(int value) {
        stuckThresholdSeconds = Math.max(60, Math.min(3_600, value));
    }

    public int getMaxHousekeepingRunsPerScan() { return maxHousekeepingRunsPerScan; }
    public void setMaxHousekeepingRunsPerScan(int value) {
        maxHousekeepingRunsPerScan = Math.max(1, Math.min(1_000, value));
    }

    public long getStageProgressWriteIntervalMs() { return stageProgressWriteIntervalMs; }
    public void setStageProgressWriteIntervalMs(long value) {
        stageProgressWriteIntervalMs = Math.max(1_000L, value);
    }
}
