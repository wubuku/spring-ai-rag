package com.springairag.core.service;

import com.springairag.core.entity.RagAlert;
import com.springairag.core.repository.AlertRepository;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import com.springairag.core.repository.RagRetrievalLogRepository;
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
 * 告警服务实现
 *
 * <p>提供阈值告警和 SLO 违约告警能力，告警记录持久化到 rag_alerts 表。
 * 支持告警静默、解决和统计查询。
 *
 * <p>内置 SLO 指标：
 * <ul>
 *   <li>可用性 SLO: 99.9%</li>
 *   <li>P50 延迟 SLO: 500ms</li>
 *   <li>P95 延迟 SLO: 2000ms</li>
 *   <li>P99 延迟 SLO: 5000ms</li>
 *   <li>MRR（平均倒数排名）SLO: 0.6</li>
 *   <li>命中率 SLO: 85%</li>
 * </ul>
 */
@Service
public class AlertServiceImpl implements AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertServiceImpl.class);

    // 默认 SLO 定义
    public static final double AVAILABILITY_SLO = 99.9;
    public static final double P50_LATENCY_SLO_MS = 500;
    public static final double P95_LATENCY_SLO_MS = 2000;
    public static final double P99_LATENCY_SLO_MS = 5000;
    public static final double MRR_SLO = 0.6;
    public static final double HIT_RATE_SLO = 0.85;

    // 告警静默缓存
    private final Map<String, ZonedDateTime> silencedAlerts = new ConcurrentHashMap<>();

    private final AlertRepository alertRepository;
    private final RagRetrievalLogRepository retrievalLogRepository;
    private final RagRetrievalEvaluationRepository evaluationRepository;
    private final NotificationService notificationService;

    public AlertServiceImpl(AlertRepository alertRepository,
                           RagRetrievalLogRepository retrievalLogRepository,
                           RagRetrievalEvaluationRepository evaluationRepository,
                           NotificationService notificationService) {
        this.alertRepository = alertRepository;
        this.retrievalLogRepository = retrievalLogRepository;
        this.evaluationRepository = evaluationRepository;
        this.notificationService = notificationService;
    }

    @Override
    public boolean shouldAlert(String alertType, String metricName,
                               double currentValue, double threshold) {
        // 检查是否被静默
        String alertKey = alertType + ":" + metricName;
        ZonedDateTime silencedUntil = silencedAlerts.get(alertKey);
        if (silencedUntil != null && ZonedDateTime.now().isBefore(silencedUntil)) {
            return false;
        }

        // 清理过期的静默记录
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
    public Long fireAlert(String alertType, String alertName, String message,
                          String severity, Map<String, Object> metrics) {
        log.warn("Alert triggered: {} - {} - {}", alertName, severity, message);

        RagAlert alert = new RagAlert();
        alert.setAlertType(alertType);
        alert.setAlertName(alertName);
        alert.setMessage(message);
        alert.setSeverity(severity);
        alert.setMetrics(metrics);
        alert.setStatus("ACTIVE");
        alert.setFiredAt(ZonedDateTime.now());

        alert = alertRepository.save(alert);

        // Send external notification (async, best-effort)
        if (notificationService != null) {
            notificationService.sendAlert(alertType, alertName, severity, message, metrics);
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
        results.put("latency_p50", checkLatencySlo("p50", P50_LATENCY_SLO_MS, windowStart, windowEnd));
        results.put("latency_p95", checkLatencySlo("p95", P95_LATENCY_SLO_MS, windowStart, windowEnd));
        results.put("latency_p99", checkLatencySlo("p99", P99_LATENCY_SLO_MS, windowStart, windowEnd));
        results.put("mrr", checkQualitySlo("mrr", MRR_SLO, windowStart, windowEnd));
        results.put("hit_rate", checkQualitySlo("hit_rate", HIT_RATE_SLO, windowStart, windowEnd));
        return results;
    }

    @Override
    @Transactional(readOnly = true)
    public SloStatus checkSlo(String sloName, ZonedDateTime startDate, ZonedDateTime endDate) {
        return switch (sloName) {
            case "availability" -> checkAvailabilitySlo(startDate, endDate);
            case "latency_p50" -> checkLatencySlo("p50", P50_LATENCY_SLO_MS, startDate, endDate);
            case "latency_p95" -> checkLatencySlo("p95", P95_LATENCY_SLO_MS, startDate, endDate);
            case "latency_p99" -> checkLatencySlo("p99", P99_LATENCY_SLO_MS, startDate, endDate);
            case "mrr" -> checkQualitySlo("mrr", MRR_SLO, startDate, endDate);
            case "hit_rate" -> checkQualitySlo("hit_rate", HIT_RATE_SLO, startDate, endDate);
            default -> {
                SloStatus unknown = new SloStatus();
                unknown.setSloName(sloName);
                unknown.setMet(false);
                yield unknown;
            }
        };
    }

    // ==================== 内部方法 ====================

    private void cleanupExpiredSilenceRecords() {
        ZonedDateTime now = ZonedDateTime.now();
        silencedAlerts.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
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

        // 基于平均延迟判断可用性：平均延迟 > P99 SLO 视为不可用
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

        // 使用平均延迟作为代理（精确的 P95/P99 需要原生 SQL PERCENTILE_CONT）
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
