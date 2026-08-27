package com.springairag.core.config;

import java.time.Duration;

/**
 * Collection 永久清理与退役的安全边界。
 */
public class RagCollectionPurgeProperties {

    private boolean enabled;
    private boolean allowAuthDisabled;
    private Duration confirmationWindow = Duration.ofMinutes(15);
    private Duration operationWindow = Duration.ofHours(1);
    private Duration resultRetention = Duration.ofHours(24);
    private Duration applyLease = Duration.ofMinutes(2);
    private int maxActivePreviewsPerOwner = 20;
    private int cleanupBatchSize = 500;
    private Duration cleanupInterval = Duration.ofHours(1);
    private int maxDocuments = 10_000;
    private int maxEmbeddings = 100_000;
    private int maxVersions = 100_000;
    private int maxDerivedRows = 250_000;
    private int maxAffectedChatSessions = 1_000;
    private int maxChatRows = 50_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isAllowAuthDisabled() { return allowAuthDisabled; }
    public void setAllowAuthDisabled(boolean value) { allowAuthDisabled = value; }
    public Duration getConfirmationWindow() { return confirmationWindow; }
    public void setConfirmationWindow(Duration value) { confirmationWindow = value; }
    public Duration getOperationWindow() { return operationWindow; }
    public void setOperationWindow(Duration value) { operationWindow = value; }
    public Duration getResultRetention() { return resultRetention; }
    public void setResultRetention(Duration value) { resultRetention = value; }
    public Duration getApplyLease() { return applyLease; }
    public void setApplyLease(Duration value) { applyLease = value; }
    public int getMaxActivePreviewsPerOwner() { return maxActivePreviewsPerOwner; }
    public void setMaxActivePreviewsPerOwner(int value) { maxActivePreviewsPerOwner = value; }
    public int getCleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int value) { cleanupBatchSize = value; }
    public Duration getCleanupInterval() { return cleanupInterval; }
    public void setCleanupInterval(Duration value) { cleanupInterval = value; }
    public int getMaxDocuments() { return maxDocuments; }
    public void setMaxDocuments(int value) { maxDocuments = value; }
    public int getMaxEmbeddings() { return maxEmbeddings; }
    public void setMaxEmbeddings(int value) { maxEmbeddings = value; }
    public int getMaxVersions() { return maxVersions; }
    public void setMaxVersions(int value) { maxVersions = value; }
    public int getMaxDerivedRows() { return maxDerivedRows; }
    public void setMaxDerivedRows(int value) { maxDerivedRows = value; }
    public int getMaxAffectedChatSessions() { return maxAffectedChatSessions; }
    public void setMaxAffectedChatSessions(int value) { maxAffectedChatSessions = value; }
    public int getMaxChatRows() { return maxChatRows; }
    public void setMaxChatRows(int value) { maxChatRows = value; }

    public void validate() {
        duration("confirmation-window", confirmationWindow,
                Duration.ofMinutes(1), Duration.ofHours(1));
        duration("operation-window", operationWindow,
                confirmationWindow, Duration.ofHours(24));
        duration("result-retention", resultRetention,
                operationWindow, Duration.ofDays(7));
        duration("apply-lease", applyLease,
                Duration.ofSeconds(15), Duration.ofMinutes(15));
        duration("cleanup-interval", cleanupInterval,
                Duration.ofMinutes(1), Duration.ofHours(24));
        range("max-active-previews-per-owner", maxActivePreviewsPerOwner, 1, 100);
        range("cleanup-batch-size", cleanupBatchSize, 10, 5_000);
        range("max-documents", maxDocuments, 1, 100_000);
        range("max-embeddings", maxEmbeddings, 1, 1_000_000);
        range("max-versions", maxVersions, 1, 1_000_000);
        range("max-derived-rows", maxDerivedRows, 1, 2_000_000);
        range("max-affected-chat-sessions", maxAffectedChatSessions, 1, 10_000);
        range("max-chat-rows", maxChatRows, 1, 500_000);
    }

    private static void duration(
            String name, Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "rag.collection-purge." + name + " must be between "
                            + minimum + " and " + maximum);
        }
    }

    private static void range(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "rag.collection-purge." + name + " must be between "
                            + minimum + " and " + maximum);
        }
    }
}
