package com.springairag.core.service;

import com.springairag.core.config.RagAlertProperties;
import com.springairag.core.entity.RagAlert;
import com.springairag.core.entity.RagSilenceSchedule;
import com.springairag.core.repository.AlertRepository;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import com.springairag.core.repository.RagRetrievalLogRepository;
import com.springairag.core.repository.RagSilenceScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alert service implementation.
 *
 * <p>Provides threshold alerting and SLO breach alerting, with alert records persisted to rag_alerts table.
 * Supports alert silencing, resolution, and statistical queries.
 *
 * <p>Built-in SLO metrics:
 * <ul>
 *   <li>Availability SLO: 99.9%</li>
 *   <li>P50 latency SLO: 500ms</li>
 *   <li>P95 latency SLO: 2000ms</li>
 *   <li>P99 latency SLO: 5000ms</li>
 *   <li>MRR (Mean Reciprocal Rank) SLO: 0.6</li>
 *   <li>Hit rate SLO: 85%</li>
 * </ul>
 */
@Service
public class AlertServiceImpl implements AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertServiceImpl.class);

    // Default SLO definitions
    public static final double AVAILABILITY_SLO = 99.9;
    public static final double P50_LATENCY_SLO_MS = 500;
    public static final double P95_LATENCY_SLO_MS = 2000;
    public static final double P99_LATENCY_SLO_MS = 5000;
    public static final double MRR_SLO = 0.6;
    public static final double HIT_RATE_SLO = 0.85;

    // Alert silencing cache
    private final Map<String, ZonedDateTime> silencedAlerts = new ConcurrentHashMap<>();

    private final AlertRepository alertRepository;
    private final RagRetrievalLogRepository retrievalLogRepository;
    private final RagRetrievalEvaluationRepository evaluationRepository;
    private final RagSilenceScheduleRepository silenceScheduleRepository;
    private final List<NotificationService> notificationServices;
    private final RagAlertProperties alertProperties;

    public AlertServiceImpl(AlertRepository alertRepository,
                           RagRetrievalLogRepository retrievalLogRepository,
                           RagRetrievalEvaluationRepository evaluationRepository,
                           RagSilenceScheduleRepository silenceScheduleRepository,
                           List<NotificationService> notificationServices,
                           RagAlertProperties alertProperties) {
        this.alertRepository = alertRepository;
        this.retrievalLogRepository = retrievalLogRepository;
        this.evaluationRepository = evaluationRepository;
        this.silenceScheduleRepository = silenceScheduleRepository;
        this.notificationServices = notificationServices != null ? notificationServices : List.of();
        this.alertProperties = alertProperties;
    }

    @Override
    public boolean shouldAlert(String alertType, String metricName,
                               double currentValue, double threshold) {
        // Check if silenced by schedule (database-backed)
        String alertKey = alertType + ":" + metricName;
        if (isSilencedBySchedule(alertKey)) {
            return false;
        }

        // Check if silenced (in-memory)
        ZonedDateTime silencedUntil = silencedAlerts.get(alertKey);
        if (silencedUntil != null && ZonedDateTime.now().isBefore(silencedUntil)) {
            return false;
        }

        // Clean up expired silence records
        cleanupExpiredSilenceRecords();

        return switch (alertType) {
            case "THRESHOLD_HIGH" -> currentValue > threshold;
            case "THRESHOLD_LOW" -> currentValue < threshold;
            case "SLO_BREACH" -> {
                if (metricName.contains("latency") || metricName.contains("time")) {
                    yield currentValue > threshold;
                }
                yield currentValue < threshold;
            }
            default -> false;
        };
    }

    @Override
    @Transactional
    public Long fireAlert(String alertType, String metricName, String message,
                          String severity, Map<String, Object> metrics) {
        // Final silence check as safeguard (schedule may have been activated since shouldAlert)
        String alertKey = alertType + ":" + metricName;
        if (isSilencedBySchedule(alertKey)) {
            log.info("Alert {} silenced by schedule, skipping fire", alertKey);
            return null;
        }

        log.warn("Alert triggered: {} - {} - {}", alertType + ":" + metricName, severity, message);

        RagAlert alert = new RagAlert();
        alert.setAlertType(alertType);
        alert.setAlertName(metricName);
        alert.setMessage(message);
        alert.setSeverity(severity);
        alert.setMetrics(metrics);
        alert.setStatus("ACTIVE");
        alert.setFiredAt(ZonedDateTime.now());

        alert = alertRepository.save(alert);

        // Send external notification (async, best-effort)
        if (!notificationServices.isEmpty()) {
            for (NotificationService ns : notificationServices) {
                try {
                    ns.sendAlert(alertType, metricName, severity, message, metrics);
                } catch (Exception e) {
                    // Resilience: one channel failure must not block others
                    log.warn("Notification channel {} failed for alert {}: {}",
                            ns.getClass().getSimpleName(), metricName, e.getMessage());
                }
            }
        }

        return alert.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertRecord> getAlertHistory(ZonedDateTime startDate, ZonedDateTime endDate,
                                             String severity, String alertType) {
        List<RagAlert> alerts = alertRepository.findAlertHistory(startDate, endDate, severity, alertType);
        return alerts.stream().map(this::toAlertRecord).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertRecord> getActiveAlerts() {
        List<RagAlert> alerts = alertRepository.findByStatusOrderByFiredAtDesc("ACTIVE");
        return alerts.stream().map(this::toAlertRecord).toList();
    }

    @Override
    @Transactional
    public void resolveAlert(Long alertId, String resolution) {
        alertRepository.findById(alertId).ifPresent(alert -> {
            alert.setStatus("RESOLVED");
            alert.setResolution(resolution);
            alert.setResolvedAt(ZonedDateTime.now());
            alertRepository.save(alert);
            log.info("Alert resolved: {} - {}", alertId, resolution);
        });
    }

    @Override
    public void silenceAlert(String alertKey, int durationMinutes) {
        silencedAlerts.put(alertKey, ZonedDateTime.now().plusMinutes(durationMinutes));
        log.info("Alert silenced: {} - {} minutes", alertKey, durationMinutes);
    }

    @Override
    @Transactional(readOnly = true)
    public AlertStats getAlertStats(ZonedDateTime startDate, ZonedDateTime endDate) {
        AlertStats stats = new AlertStats();
        long totalAlerts = alertRepository.countByFiredAtBetween(startDate, endDate);
        stats.setTotalAlerts(totalAlerts);
        stats.setActiveAlerts(alertRepository.countActiveAlerts(startDate, endDate));
        stats.setCriticalAlerts(alertRepository.countBySeverity(startDate, endDate, "CRITICAL"));
        stats.setWarningAlerts(alertRepository.countBySeverity(startDate, endDate, "WARNING"));
        stats.setInfoAlerts(alertRepository.countBySeverity(startDate, endDate, "INFO"));

        long hours = ChronoUnit.HOURS.between(startDate, endDate);
        stats.setAlertRate(hours > 0 ? (double) totalAlerts / hours : 0);
        return stats;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, SloStatus> checkAllSlos(ZonedDateTime windowStart, ZonedDateTime windowEnd) {
        Map<String, SloStatus> results = new LinkedHashMap<>();
        results.put("availability", checkAvailabilitySlo(windowStart, windowEnd));
        results.put("latency_p50", checkLatencySlo("p50", alertProperties.getLatencyP50SloMs(), windowStart, windowEnd));
        results.put("latency_p95", checkLatencySlo("p95", alertProperties.getLatencyP95SloMs(), windowStart, windowEnd));
        results.put("latency_p99", checkLatencySlo("p99", alertProperties.getLatencyP99SloMs(), windowStart, windowEnd));
        results.put("mrr", checkQualitySlo("mrr", alertProperties.getMrrSlo(), windowStart, windowEnd));
        results.put("hit_rate", checkQualitySlo("hit_rate", alertProperties.getHitRateSlo(), windowStart, windowEnd));
        return results;
    }

    @Override
    @Transactional(readOnly = true)
    public SloStatus checkSlo(String sloName, ZonedDateTime startDate, ZonedDateTime endDate) {
        return switch (sloName) {
            case "availability" -> checkAvailabilitySlo(startDate, endDate);
            case "latency_p50" -> checkLatencySlo("p50", alertProperties.getLatencyP50SloMs(), startDate, endDate);
            case "latency_p95" -> checkLatencySlo("p95", alertProperties.getLatencyP95SloMs(), startDate, endDate);
            case "latency_p99" -> checkLatencySlo("p99", alertProperties.getLatencyP99SloMs(), startDate, endDate);
            case "mrr" -> checkQualitySlo("mrr", alertProperties.getMrrSlo(), startDate, endDate);
            case "hit_rate" -> checkQualitySlo("hit_rate", alertProperties.getHitRateSlo(), startDate, endDate);
            default -> {
                SloStatus unknown = new SloStatus();
                unknown.setSloName(sloName);
                unknown.setMet(false);
                yield unknown;
            }
        };
    }

    // ==================== Internal methods ====================

    private void cleanupExpiredSilenceRecords() {
        ZonedDateTime now = ZonedDateTime.now();
        silencedAlerts.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }

    /**
     * Check if the given alert key is currently silenced by an active schedule.
     * Supports ONE_TIME silence: alert is silenced if current time is within [startTime, endTime].
     * Supports wildcard silence: schedule with alertKey=null silences all alerts.
     *
     * @param alertKey alert key (format: alertType:metricName)
     * @return true if silenced by an active schedule
     */
    private boolean isSilencedBySchedule(String alertKey) {
        if (silenceScheduleRepository == null) {
            return false;
        }
        try {
            ZonedDateTime now = ZonedDateTime.now();
            // Check schedules matching the specific alert key
            List<RagSilenceSchedule> schedules = silenceScheduleRepository.findByAlertKeyAndEnabledTrue(alertKey);
            for (RagSilenceSchedule schedule : schedules) {
                if (isCurrentlySilent(schedule, now)) {
                    return true;
                }
            }
            // Check wildcard schedules (null alertKey silences all)
            List<RagSilenceSchedule> wildcardSchedules = silenceScheduleRepository.findByAlertKeyAndEnabledTrue(null);
            for (RagSilenceSchedule schedule : wildcardSchedules) {
                if (isCurrentlySilent(schedule, now)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Best-effort: schedule check failure must not block alerting
            log.debug("Failed to check silence schedule for alert {}: {}", alertKey, e.getMessage());
        }
        return false;
    }

    /**
     * Check if a schedule is currently active based on its type.
     */
    private boolean isCurrentlySilent(RagSilenceSchedule schedule, ZonedDateTime now) {
        if (schedule == null || !"ONE_TIME".equals(schedule.getSilenceType())) {
            return false;
        }
        try {
            ZonedDateTime start = ZonedDateTime.parse(schedule.getStartTime());
            ZonedDateTime end = ZonedDateTime.parse(schedule.getEndTime());
            return !now.isBefore(start) && !now.isAfter(end);
        } catch (Exception e) {
            // Resilience: parse failure means the schedule is invalid; treat as not silent
            log.debug("Failed to parse silence schedule times: {}", e.getMessage());
            return false;
        }
    }

    private AlertRecord toAlertRecord(RagAlert alert) {
        AlertRecord record = new AlertRecord();
        record.setId(alert.getId());
        record.setAlertType(alert.getAlertType());
        record.setAlertName(alert.getAlertName());
        record.setMessage(alert.getMessage());
        record.setSeverity(alert.getSeverity());
        record.setMetrics(alert.getMetrics());
        record.setStatus(alert.getStatus());
        record.setResolution(alert.getResolution());
        record.setFiredAt(alert.getFiredAt());
        record.setResolvedAt(alert.getResolvedAt());
        record.setSilencedUntil(alert.getSilencedUntil());
        return record;
    }

    private SloStatus checkAvailabilitySlo(ZonedDateTime startDate, ZonedDateTime endDate) {
        SloStatus status = new SloStatus();
        status.setSloName("availability");
        status.setSloType("AVAILABILITY");
        status.setTarget(AVAILABILITY_SLO);
        status.setUnit("%");
        status.setWindowStart(startDate);
        status.setWindowEnd(endDate);

        long totalRequests = retrievalLogRepository.countByCreatedAtBetween(startDate, endDate);
        if (totalRequests == 0) {
            status.setActual(100.0);
            status.setMet(true);
            return status;
        }

        // Determine availability based on average latency: considered unavailable if avg latency > P99 SLO
        Double avgTotalTime = retrievalLogRepository.findAvgTotalTime(startDate, endDate);
        double availability = (avgTotalTime != null && avgTotalTime <= P99_LATENCY_SLO_MS) ? 100.0 : 99.0;
        status.setActual(availability);
        status.setMet(availability >= AVAILABILITY_SLO);

        long windowMinutes = ChronoUnit.MINUTES.between(startDate, endDate);
        double errorBudget = windowMinutes * (1.0 - AVAILABILITY_SLO / 100.0);
        status.setErrorBudget(errorBudget);
        status.setErrorBudgetRemaining(status.isMet() ? 100.0 : 0.0);
        return status;
    }

    private SloStatus checkLatencySlo(String percentile, double thresholdMs,
                                      ZonedDateTime startDate, ZonedDateTime endDate) {
        SloStatus status = new SloStatus();
        status.setSloName("latency_" + percentile);
        status.setSloType("LATENCY");
        status.setTarget(thresholdMs);
        status.setUnit("ms");
        status.setWindowStart(startDate);
        status.setWindowEnd(endDate);

        // Use average latency as proxy (accurate P95/P99 requires native SQL PERCENTILE_CONT)
        Double actualLatency = retrievalLogRepository.findAvgTotalTime(startDate, endDate);
        status.setActual(actualLatency != null ? actualLatency : 0);
        status.setMet(status.getActual() <= thresholdMs);
        return status;
    }

    private SloStatus checkQualitySlo(String metricName, double threshold,
                                      ZonedDateTime startDate, ZonedDateTime endDate) {
        SloStatus status = new SloStatus();
        status.setSloName(metricName);
        status.setSloType("QUALITY");
        status.setTarget(threshold);
        status.setUnit(metricName.contains("rate") ? "%" : "score");
        status.setWindowStart(startDate);
        status.setWindowEnd(endDate);

        Double actualValue = null;
        if ("mrr".equals(metricName)) {
            actualValue = evaluationRepository.findAvgMrr(startDate, endDate);
        } else if ("hit_rate".equals(metricName)) {
            actualValue = evaluationRepository.findAvgHitRate(startDate, endDate);
            if (actualValue != null) {
                actualValue = actualValue * 100;
            }
        }

        status.setActual(actualValue != null ? Math.round(actualValue * 100.0) / 100.0 : 0);
        status.setMet(status.getActual() >= threshold);
        return status;
    }
}
