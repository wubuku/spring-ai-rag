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
    private Duration asyncPreviewDeadline = Duration.ofSeconds(60);
    private Duration asyncPreviewStatementTimeout = Duration.ofSeconds(10);
    private Duration asyncPreviewAdmissionLease = Duration.ofSeconds(180);
    private long asyncPreviewMaxDocuments = 1_000_000;
    private long asyncPreviewMaxDerivedRows = 10_000_000;
    private Duration asyncOperationLease = Duration.ofSeconds(120);
    private Duration asyncOperationHeartbeat = Duration.ofSeconds(20);
    private Duration asyncBatchDeadline = Duration.ofSeconds(60);
    private Duration asyncBatchStatementTimeout = Duration.ofSeconds(30);
    private int asyncBatchMaxDocuments = 500;
    private int asyncBatchMaxRows = 5_000;
    private int asyncWorkerConcurrency = 2;
    private int asyncMaxCoordinatorAttempts = 5;
    private int asyncManifestBatchSize = 5_000;

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
    public Duration getAsyncPreviewDeadline() { return asyncPreviewDeadline; }
    public void setAsyncPreviewDeadline(Duration value) { asyncPreviewDeadline = value; }
    public Duration getAsyncPreviewStatementTimeout() { return asyncPreviewStatementTimeout; }
    public void setAsyncPreviewStatementTimeout(Duration value) { asyncPreviewStatementTimeout = value; }
    public Duration getAsyncPreviewAdmissionLease() { return asyncPreviewAdmissionLease; }
    public void setAsyncPreviewAdmissionLease(Duration value) { asyncPreviewAdmissionLease = value; }
    public long getAsyncPreviewMaxDocuments() { return asyncPreviewMaxDocuments; }
    public void setAsyncPreviewMaxDocuments(long value) { asyncPreviewMaxDocuments = value; }
    public long getAsyncPreviewMaxDerivedRows() { return asyncPreviewMaxDerivedRows; }
    public void setAsyncPreviewMaxDerivedRows(long value) { asyncPreviewMaxDerivedRows = value; }
    public Duration getAsyncOperationLease() { return asyncOperationLease; }
    public void setAsyncOperationLease(Duration value) { asyncOperationLease = value; }
    public Duration getAsyncOperationHeartbeat() { return asyncOperationHeartbeat; }
    public void setAsyncOperationHeartbeat(Duration value) { asyncOperationHeartbeat = value; }
    public Duration getAsyncBatchDeadline() { return asyncBatchDeadline; }
    public void setAsyncBatchDeadline(Duration value) { asyncBatchDeadline = value; }
    public Duration getAsyncBatchStatementTimeout() { return asyncBatchStatementTimeout; }
    public void setAsyncBatchStatementTimeout(Duration value) { asyncBatchStatementTimeout = value; }
    public int getAsyncBatchMaxDocuments() { return asyncBatchMaxDocuments; }
    public void setAsyncBatchMaxDocuments(int value) { asyncBatchMaxDocuments = value; }
    public int getAsyncBatchMaxRows() { return asyncBatchMaxRows; }
    public void setAsyncBatchMaxRows(int value) { asyncBatchMaxRows = value; }
    public int getAsyncWorkerConcurrency() { return asyncWorkerConcurrency; }
    public void setAsyncWorkerConcurrency(int value) { asyncWorkerConcurrency = value; }
    public int getAsyncMaxCoordinatorAttempts() { return asyncMaxCoordinatorAttempts; }
    public void setAsyncMaxCoordinatorAttempts(int value) { asyncMaxCoordinatorAttempts = value; }
    public int getAsyncManifestBatchSize() { return asyncManifestBatchSize; }
    public void setAsyncManifestBatchSize(int value) { asyncManifestBatchSize = value; }

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
        duration("async-preview-deadline", asyncPreviewDeadline,
                Duration.ofSeconds(1), Duration.ofSeconds(120));
        duration("async-preview-statement-timeout", asyncPreviewStatementTimeout,
                Duration.ofSeconds(1), Duration.ofSeconds(30));
        duration("async-preview-admission-lease", asyncPreviewAdmissionLease,
                Duration.ofSeconds(150), Duration.ofSeconds(240));
        Duration requiredPreviewLease = asyncPreviewDeadline
                .plus(asyncPreviewStatementTimeout).plusSeconds(30);
        if (asyncPreviewAdmissionLease.compareTo(requiredPreviewLease) <= 0) {
            throw new IllegalArgumentException(
                    "rag.collection-purge.async-preview-admission-lease must exceed "
                            + "the async preview deadline plus statement timeout and 30 seconds");
        }
        if (asyncPreviewMaxDocuments < 1 || asyncPreviewMaxDocuments > 1_000_000
                || asyncPreviewMaxDerivedRows < 1
                || asyncPreviewMaxDerivedRows > 10_000_000) {
            throw new IllegalArgumentException(
                    "rag.collection-purge async preview scan limits are out of range");
        }
        duration("async-operation-lease", asyncOperationLease,
                Duration.ofSeconds(30), Duration.ofSeconds(180));
        duration("async-operation-heartbeat", asyncOperationHeartbeat,
                Duration.ofSeconds(1), Duration.ofSeconds(60));
        duration("async-batch-deadline", asyncBatchDeadline,
                Duration.ofSeconds(1), Duration.ofSeconds(120));
        duration("async-batch-statement-timeout", asyncBatchStatementTimeout,
                Duration.ofSeconds(1), Duration.ofSeconds(60));
        if (asyncOperationLease.compareTo(asyncBatchDeadline.plusSeconds(15)) <= 0) {
            throw new IllegalArgumentException(
                    "rag.collection-purge.async-operation-lease must exceed "
                            + "the async batch deadline plus 15 seconds");
        }
        if (asyncOperationHeartbeat.multipliedBy(3).compareTo(asyncOperationLease) > 0) {
            throw new IllegalArgumentException(
                    "rag.collection-purge.async-operation-heartbeat must not exceed "
                            + "one third of the async operation lease");
        }
        if (asyncBatchStatementTimeout.compareTo(asyncBatchDeadline) > 0) {
            throw new IllegalArgumentException(
                    "rag.collection-purge.async-batch-statement-timeout must not exceed "
                            + "the async batch deadline");
        }
        range("async-batch-max-documents", asyncBatchMaxDocuments, 1, 500);
        range("async-batch-max-rows", asyncBatchMaxRows, 1, 10_000);
        range("async-worker-concurrency", asyncWorkerConcurrency, 1, 8);
        range("async-max-coordinator-attempts", asyncMaxCoordinatorAttempts, 1, 10);
        range("async-manifest-batch-size", asyncManifestBatchSize, 1, 5_000);
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
