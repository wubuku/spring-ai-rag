package com.springairag.core.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Alert service interface.
 *
 * <p>Handles alert detection, recording, and notification for the RAG system.
 * Supports threshold alerts and SLO (Service Level Objective) breach alerts.
 *
 * <p>Usage example:
 * <pre>
 * // Check if alert condition is met
 * if (alertService.shouldAlert("THRESHOLD_HIGH", "latency_p95", 2500, 2000)) {
 *     alertService.fireAlert("THRESHOLD_HIGH", "High Latency Alert",
 *         "P95 latency exceeded 2000ms", "WARNING", Map.of("latency", 2500));
 * }
 * </pre>
 */
public interface AlertService {

    /**
     * Check if alert condition is met.
     *
     * @param alertType    alert type (THRESHOLD_HIGH / THRESHOLD_LOW / SLO_BREACH)
     * @param metricName  metric name
     * @param currentValue current value
     * @param threshold   threshold
     * @return true if alert should fire
     */
    boolean shouldAlert(String alertType, String metricName, double currentValue, double threshold);

    /**
     * Fire an alert.
     *
     * @param alertType  alert type
     * @param metricName metric name (used to construct alertKey for silencing: alertType:metricName)
     * @param message    alert message
     * @param severity   severity (INFO / WARNING / CRITICAL)
     * @param metrics    associated metric data
     * @return alert record ID, or null if silenced by schedule
     */
    Long fireAlert(String alertType, String metricName, String message,
                   String severity, Map<String, Object> metrics);

    /**
     * Get alert history.
     *
     * @param startDate start date
     * @param endDate   end date
     * @param severity  severity filter (optional, null means no filter)
     * @param alertType alert type filter (optional, null means no filter)
     * @return list of alert records
     */
    List<AlertRecord> getAlertHistory(ZonedDateTime startDate, ZonedDateTime endDate,
                                      String severity, String alertType);

    /**
     * Get active (unresolved) alerts.
     *
     * @return list of active alerts
     */
    List<AlertRecord> getActiveAlerts();

    /**
     * Resolve an alert.
     *
     * @param alertId     alert ID
     * @param resolution  resolution description
     */
    void resolveAlert(Long alertId, String resolution);

    /**
     * Silence an alert (temporarily suppress).
     *
     * @param alertKey         alert key (format: alertType:metricName)
     * @param durationMinutes  silence duration in minutes
     */
    void silenceAlert(String alertKey, int durationMinutes);

    /**
     * Get alert statistics.
     *
     * @param startDate start date
     * @param endDate   end date
     * @return alert statistics
     */
    AlertStats getAlertStats(ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * Check all SLO statuses.
     *
     * @param windowStart check window start time
     * @param windowEnd   check window end time
     * @return map of SLO name to SLO status
     */
    Map<String, SloStatus> checkAllSlos(ZonedDateTime windowStart, ZonedDateTime windowEnd);

    /**
     * Check a single SLO status.
     *
     * @param sloName   SLO name
     * @param startDate start date
     * @param endDate   end date
     * @return SLO status
     */
    SloStatus checkSlo(String sloName, ZonedDateTime startDate, ZonedDateTime endDate);

    // ==================== Data classes ====================

    /**
     * Alert record (API layer data transfer object).
     */
    class AlertRecord {
        private Long id;
        private String alertType;
        private String alertName;
        private String message;
        private String severity;
        private Map<String, Object> metrics;
        private String status;
        private String resolution;
        private ZonedDateTime firedAt;
        private ZonedDateTime resolvedAt;
        private ZonedDateTime silencedUntil;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getAlertType() { return alertType; }
        public void setAlertType(String alertType) { this.alertType = alertType; }
        public String getAlertName() { return alertName; }
        public void setAlertName(String alertName) { this.alertName = alertName; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public Map<String, Object> getMetrics() { return metrics; }
        public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getResolution() { return resolution; }
        public void setResolution(String resolution) { this.resolution = resolution; }
        public ZonedDateTime getFiredAt() { return firedAt; }
        public void setFiredAt(ZonedDateTime firedAt) { this.firedAt = firedAt; }
        public ZonedDateTime getResolvedAt() { return resolvedAt; }
        public void setResolvedAt(ZonedDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
        public ZonedDateTime getSilencedUntil() { return silencedUntil; }
        public void setSilencedUntil(ZonedDateTime silencedUntil) { this.silencedUntil = silencedUntil; }
    }

    /**
     * Alert statistics.
     */
    class AlertStats {
        private long totalAlerts;
        private long activeAlerts;
        private long criticalAlerts;
        private long warningAlerts;
        private long infoAlerts;
        private double alertRate;

        public long getTotalAlerts() { return totalAlerts; }
        public void setTotalAlerts(long totalAlerts) { this.totalAlerts = totalAlerts; }
        public long getActiveAlerts() { return activeAlerts; }
        public void setActiveAlerts(long activeAlerts) { this.activeAlerts = activeAlerts; }
        public long getCriticalAlerts() { return criticalAlerts; }
        public void setCriticalAlerts(long criticalAlerts) { this.criticalAlerts = criticalAlerts; }
        public long getWarningAlerts() { return warningAlerts; }
        public void setWarningAlerts(long warningAlerts) { this.warningAlerts = warningAlerts; }
        public long getInfoAlerts() { return infoAlerts; }
        public void setInfoAlerts(long infoAlerts) { this.infoAlerts = infoAlerts; }
        public double getAlertRate() { return alertRate; }
        public void setAlertRate(double alertRate) { this.alertRate = alertRate; }
    }

    /**
     * SLO compliance status.
     */
    class SloStatus {
        private String sloName;
        private String sloType;
        private double target;
        private double actual;
        private String unit;
        private boolean met;
        private double errorBudget;
        private double errorBudgetRemaining;
        private ZonedDateTime windowStart;
        private ZonedDateTime windowEnd;

        public String getSloName() { return sloName; }
        public void setSloName(String sloName) { this.sloName = sloName; }
        public String getSloType() { return sloType; }
        public void setSloType(String sloType) { this.sloType = sloType; }
        public double getTarget() { return target; }
        public void setTarget(double target) { this.target = target; }
        public double getActual() { return actual; }
        public void setActual(double actual) { this.actual = actual; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public boolean isMet() { return met; }
        public void setMet(boolean met) { this.met = met; }
        public double getErrorBudget() { return errorBudget; }
        public void setErrorBudget(double errorBudget) { this.errorBudget = errorBudget; }
        public double getErrorBudgetRemaining() { return errorBudgetRemaining; }
        public void setErrorBudgetRemaining(double errorBudgetRemaining) { this.errorBudgetRemaining = errorBudgetRemaining; }
        public ZonedDateTime getWindowStart() { return windowStart; }
        public void setWindowStart(ZonedDateTime windowStart) { this.windowStart = windowStart; }
        public ZonedDateTime getWindowEnd() { return windowEnd; }
        public void setWindowEnd(ZonedDateTime windowEnd) { this.windowEnd = windowEnd; }
    }
}
