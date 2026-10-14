package com.springairag.core.config;

import java.time.Duration;

public class RagChunkUpgradeRecoveryProperties {
    private Duration cooldown = Duration.ofHours(1);
    private Duration maxCooldown = Duration.ofHours(24);
    private int retryDocumentsPerHour = 12;

    public Duration getCooldown() { return cooldown; }
    public void setCooldown(Duration value) { cooldown = value; }
    public Duration getMaxCooldown() { return maxCooldown; }
    public void setMaxCooldown(Duration value) { maxCooldown = value; }
    public int getRetryDocumentsPerHour() { return retryDocumentsPerHour; }
    public void setRetryDocumentsPerHour(int value) { retryDocumentsPerHour = value; }

    public void validate() {
        if (cooldown == null || maxCooldown == null
                || cooldown.compareTo(Duration.ofMinutes(1)) < 0
                || cooldown.compareTo(Duration.ofHours(24)) > 0
                || maxCooldown.compareTo(cooldown) < 0
                || maxCooldown.compareTo(Duration.ofDays(7)) > 0
                || retryDocumentsPerHour < 1 || retryDocumentsPerHour > 1000) {
            throw new IllegalArgumentException("Invalid chunking upgrade recovery budgets");
        }
    }
}
