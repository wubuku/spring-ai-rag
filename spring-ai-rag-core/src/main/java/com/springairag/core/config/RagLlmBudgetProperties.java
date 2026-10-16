package com.springairag.core.config;

import java.math.BigDecimal;

/** LLM durable reservation 与 hard limit 配置。 */
public class RagLlmBudgetProperties {

    private boolean enabled = true;
    private long dailyTokenLimit = 5_000_000L;
    private long monthlyTokenLimit = 50_000_000L;
    private BigDecimal dailyCostLimit;
    private BigDecimal monthlyCostLimit;
    private long maxRequestTokens = 200_000L;
    private BigDecimal maxRequestCost;
    private int reservationTtlSeconds = 900;
    private int retentionDays = 30;
    private int cleanupBatchSize = 200;
    private String cleanupCron = "0 */5 * * * *";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getDailyTokenLimit() { return dailyTokenLimit; }
    public void setDailyTokenLimit(long value) { this.dailyTokenLimit = value; }
    public long getMonthlyTokenLimit() { return monthlyTokenLimit; }
    public void setMonthlyTokenLimit(long value) { this.monthlyTokenLimit = value; }
    public BigDecimal getDailyCostLimit() { return dailyCostLimit; }
    public void setDailyCostLimit(BigDecimal value) { this.dailyCostLimit = value; }
    public BigDecimal getMonthlyCostLimit() { return monthlyCostLimit; }
    public void setMonthlyCostLimit(BigDecimal value) { this.monthlyCostLimit = value; }
    public long getMaxRequestTokens() { return maxRequestTokens; }
    public void setMaxRequestTokens(long value) { this.maxRequestTokens = value; }
    public BigDecimal getMaxRequestCost() { return maxRequestCost; }
    public void setMaxRequestCost(BigDecimal value) { this.maxRequestCost = value; }
    public int getReservationTtlSeconds() { return reservationTtlSeconds; }
    public void setReservationTtlSeconds(int value) { this.reservationTtlSeconds = value; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int value) { this.retentionDays = value; }
    public int getCleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int value) { this.cleanupBatchSize = value; }
    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String value) { this.cleanupCron = value; }

    public void validate() {
        if (dailyTokenLimit < 1 || monthlyTokenLimit < dailyTokenLimit
                || maxRequestTokens < 1 || maxRequestTokens > dailyTokenLimit) {
            throw new IllegalArgumentException(
                    "rag.llm-budget token limits are inconsistent");
        }
        validateCost(dailyCostLimit, "daily-cost-limit");
        validateCost(monthlyCostLimit, "monthly-cost-limit");
        validateCost(maxRequestCost, "max-request-cost");
        if (reservationTtlSeconds < 1 || reservationTtlSeconds > 3600) {
            throw new IllegalArgumentException(
                    "rag.llm-budget.reservation-ttl-seconds must be between 1 and 3600");
        }
        if (retentionDays < 1 || retentionDays > 3650) {
            throw new IllegalArgumentException(
                    "rag.llm-budget.retention-days must be between 1 and 3650");
        }
        if (cleanupBatchSize < 1 || cleanupBatchSize > 10_000) {
            throw new IllegalArgumentException(
                    "rag.llm-budget.cleanup-batch-size must be between 1 and 10000");
        }
        if (cleanupCron == null || cleanupCron.isBlank()) {
            throw new IllegalArgumentException(
                    "rag.llm-budget.cleanup-cron must not be blank");
        }
    }

    private void validateCost(BigDecimal value, String name) {
        if (value != null && (value.signum() < 0 || value.compareTo(new BigDecimal("9999999999.99999999")) > 0)) {
            throw new IllegalArgumentException("rag.llm-budget." + name + " is out of range");
        }
    }
}
