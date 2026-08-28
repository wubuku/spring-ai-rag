package com.springairag.core.alertdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.AlertNotificationDeliveryPageResponse;
import com.springairag.api.dto.AlertNotificationDeliveryResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.NotificationConfig;
import com.springairag.core.exception.RagException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Operator 查询与人工重试 durable notification delivery。 */
@Service
public class AlertNotificationDeliveryService {

    private static final List<String> STATUSES = List.of(
            "PENDING", "IN_PROGRESS", "RETRY_WAIT",
            "DELIVERED", "FAILED", "SUPERSEDED");
    private static final List<String> PROVIDERS = List.of("EMAIL", "DINGTALK");

    private final AlertNotificationDeliveryRepository repository;
    private final AlertNotificationOutboxService outboxService;
    private final AlertNotificationWakeupPublisher wakeupPublisher;
    private final NotificationConfig notificationConfig;
    private final TransactionTemplate transactionTemplate;
    private final AlertNotificationCursorCodec cursorCodec;

    public AlertNotificationDeliveryService(
            AlertNotificationDeliveryRepository repository,
            AlertNotificationOutboxService outboxService,
            AlertNotificationWakeupPublisher wakeupPublisher,
            NotificationConfig notificationConfig,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.outboxService = outboxService;
        this.wakeupPublisher = wakeupPublisher;
        this.notificationConfig = notificationConfig;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.cursorCodec = new AlertNotificationCursorCodec(objectMapper);
    }

    public AlertNotificationDeliveryPageResponse query(
            String requestedStatus,
            String requestedProvider,
            Long alertId,
            int limit,
            String cursor) {
        String status = enumValue(requestedStatus, STATUSES, "status");
        String provider = enumValue(requestedProvider, PROVIDERS, "provider");
        AlertNotificationCursorCodec.CursorPosition position =
                cursor == null || cursor.isBlank()
                        ? null
                        : cursorCodec.decode(
                                cursor, status, provider, alertId);
        List<AlertNotificationDeliveryRecord> rows = repository.query(
                status,
                provider,
                alertId,
                position == null ? null : position.createdAt(),
                position == null ? null : position.id(),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<AlertNotificationDeliveryRecord> page =
                hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            AlertNotificationDeliveryRecord last = page.getLast();
            nextCursor = cursorCodec.encode(
                    status, provider, alertId, last.createdAt(), last.id());
        }
        return new AlertNotificationDeliveryPageResponse(
                notificationConfig.isEnabled(),
                notificationConfig.getDelivery().isEnabled(),
                outboxService.configuredProviders(),
                page.stream().map(this::response).toList(),
                limit,
                hasMore,
                nextCursor);
    }

    public AlertNotificationDeliveryResponse retry(UUID id) {
        RetryResult result =
                transactionTemplate.execute(status -> retryInTransaction(id));
        if (result == null) {
            throw new IllegalStateException(
                    "Alert notification retry returned no result");
        }
        if (result.conflictMessage() != null) {
            throw conflict(result.conflictMessage());
        }
        return response(result.record());
    }

    private RetryResult retryInTransaction(UUID id) {
        AlertNotificationDeliveryRecord current = repository.find(id)
                .orElseThrow(() -> new RagException(
                        ErrorCode.NOT_FOUND,
                        "Alert notification delivery was not found"));
        if (List.of("PENDING", "RETRY_WAIT", "IN_PROGRESS")
                .contains(current.status())) {
            return RetryResult.success(current);
        }
        if (!"FAILED".equals(current.status())) {
            throw conflict("Delivery status " + current.status()
                    + " cannot be retried");
        }
        AlertNotificationProvider provider =
                outboxService.provider(current.provider());
        if (provider == null
                || !provider.isRoutedFor(current.payload().alertType())
                || !provider.isCurrentlyAvailable()) {
            throw conflict("Delivery provider is not currently available");
        }
        if (current.managedCondition()
                && !repository.isManagedStateCurrent(
                        current.alertId(),
                        current.notificationVersion())) {
            repository.markFailedAsSuperseded(id);
            return RetryResult.conflict(
                    repository.find(id).orElse(current),
                    "Managed alert state is no longer current");
        }
        AlertNotificationDeliveryRecord retried = repository.retryFailed(
                        id,
                        notificationConfig.getDelivery().getMaxAttempts())
                .orElseGet(() -> repository.find(id).orElseThrow(
                        () -> new RagException(
                                ErrorCode.NOT_FOUND,
                                "Alert notification delivery was not found")));
        wakeupPublisher.publishAfterCommit();
        return RetryResult.success(retried);
    }

    private AlertNotificationDeliveryResponse response(
            AlertNotificationDeliveryRecord record) {
        return new AlertNotificationDeliveryResponse(
                record.id(),
                record.alertId(),
                record.notificationVersion(),
                record.provider(),
                record.status(),
                record.attemptCount(),
                record.attemptBudget(),
                record.manualRetryCount(),
                record.nextAttemptAt(),
                record.lastErrorCode(),
                record.lastHttpStatus(),
                record.lastAttemptAt(),
                record.deliveredAt(),
                record.createdAt(),
                record.updatedAt());
    }

    private static String enumValue(
            String value, List<String> allowed, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }

    private static RagException conflict(String message) {
        return new RagException(
                ErrorCode.ALERT_NOTIFICATION_DELIVERY_CONFLICT, message);
    }

    private record RetryResult(
            AlertNotificationDeliveryRecord record,
            String conflictMessage) {

        private static RetryResult success(
                AlertNotificationDeliveryRecord record) {
            return new RetryResult(record, null);
        }

        private static RetryResult conflict(
                AlertNotificationDeliveryRecord record,
                String message) {
            return new RetryResult(record, message);
        }
    }
}
