package com.springairag.core.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * 告警服务
 *
 * <p>负责 RAG 系统的告警检测、记录和通知。
 * 支持阈值告警和 SLO（Service Level Objective）违约告警。
 *
 * <p>使用示例：
 * <pre>
 * // 检查是否需要告警
 * if (alertService.shouldAlert("THRESHOLD_HIGH", "latency_p95", 2500, 2000)) {
 *     alertService.fireAlert("THRESHOLD_HIGH", "高延迟告警",
 *         "P95 延迟超过 2000ms", "WARNING", Map.of("latency", 2500));
 * }
 * </pre>
 */
public interface AlertService {

    /**
     * 检查是否触发告警条件
     *
     * @param alertType   告警类型（THRESHOLD_HIGH / THRESHOLD_LOW / SLO_BREACH）
     * @param metricName  指标名称
     * @param currentValue 当前值
     * @param threshold   阈值
     * @return 是否触发告警
     */
    boolean shouldAlert(String alertType, String metricName, double currentValue, double threshold);

    /**
     * 触发告警
     *
     * @param alertType  告警类型
     * @param alertName  告警名称
     * @param message    告警消息
     * @param severity   严重程度（INFO / WARNING / CRITICAL）
     * @param metrics    关联的指标数据
     * @return 告警记录 ID
     */
    Long fireAlert(String alertType, String alertName, String message,
                   String severity, Map<String, Object> metrics);

    /**
     * 获取告警历史
     *
     * @param startDate  开始日期
     * @param endDate    结束日期
     * @param severity   严重程度过滤（可选，null 表示不过滤）
     * @param alertType  告警类型过滤（可选，null 表示不过滤）
     * @return 告警记录列表
     */
    List<AlertRecord> getAlertHistory(ZonedDateTime startDate, ZonedDateTime endDate,
                                      String severity, String alertType);

    /**
     * 获取活跃告警（未解决的）
     *
     * @return 活跃告警列表
     */
    List<AlertRecord> getActiveAlerts();

    /**
     * 解决告警
     *
     * @param alertId   告警 ID
     * @param resolution 解决方案描述
     */
    void resolveAlert(Long alertId, String resolution);

    /**
     * 静默告警（暂时屏蔽）
     *
     * @param alertKey        告警键（格式：alertType:metricName）
     * @param durationMinutes 静默时长（分钟）
     */
    void silenceAlert(String alertKey, int durationMinutes);

    /**
     * 获取告警统计
     *
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @return 告警统计数据
     */
    AlertStats getAlertStats(ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * 检查所有 SLO 状态
     *
     * @param windowStart 检查窗口开始时间
     * @param windowEnd   检查窗口结束时间
     * @return 各 SLO 状态（key = SLO 名称）
     */
    Map<String, SloStatus> checkAllSlos(ZonedDateTime windowStart, ZonedDateTime windowEnd);

    /**
     * 检查单个 SLO 状态
     *
     * @param sloName   SLO 名称
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @return SLO 状态
     */
    SloStatus checkSlo(String sloName, ZonedDateTime startDate, ZonedDateTime endDate);

    // ==================== 数据类 ====================

    /**
     * 告警记录（API 层数据传输对象）
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
     * 告警统计
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
     * SLO 达标状态
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
