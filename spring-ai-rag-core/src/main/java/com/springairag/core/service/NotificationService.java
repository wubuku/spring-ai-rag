package com.springairag.core.service;

import java.util.concurrent.CompletableFuture;

/**
 * Notification service for sending alert notifications to external channels.
 * Implementations handle DingTalk webhooks, email, Slack, etc.
 */
public interface NotificationService {

    /**
     * Send an alert notification to the configured channel.
     *
     * @param alertType  alert type (e.g. THRESHOLD_HIGH, SLO_BREACH)
     * @param alertName  human-readable alert name
     * @param severity   CRITICAL / WARNING / INFO
     * @param message    alert message
     * @param metadata   additional alert metadata
     * @return asynchronously completed send result
     */
    CompletableFuture<Boolean> sendAlert(
            String alertType,
            String alertName,
            String severity,
            String message,
            java.util.Map<String, Object> metadata);
}
