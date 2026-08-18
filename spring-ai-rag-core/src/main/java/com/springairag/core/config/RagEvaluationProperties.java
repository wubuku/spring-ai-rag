package com.springairag.core.config;

/**
 * 受管质量套件与 citation 校验配置。
 */
public class RagEvaluationProperties {

    private boolean managedSuitesEnabled;
    private boolean citationValidationEnabled = true;
    private int maxConcurrentRuns = 1;
    private int runConcurrency = 4;
    private int maxCasesPerVersion = 200;
    private int maxVariantsPerRun = 4;
    private int semanticBatchLimit = 50;

    public boolean isManagedSuitesEnabled() {
        return managedSuitesEnabled;
    }

    public void setManagedSuitesEnabled(boolean managedSuitesEnabled) {
        this.managedSuitesEnabled = managedSuitesEnabled;
    }

    public boolean isCitationValidationEnabled() {
        return citationValidationEnabled;
    }

    public void setCitationValidationEnabled(boolean citationValidationEnabled) {
        this.citationValidationEnabled = citationValidationEnabled;
    }

    public int getMaxConcurrentRuns() {
        return maxConcurrentRuns;
    }

    public void setMaxConcurrentRuns(int maxConcurrentRuns) {
        this.maxConcurrentRuns = Math.max(1, Math.min(4, maxConcurrentRuns));
    }

    public int getRunConcurrency() {
        return runConcurrency;
    }

    public void setRunConcurrency(int runConcurrency) {
        this.runConcurrency = Math.max(1, Math.min(8, runConcurrency));
    }

    public int getMaxCasesPerVersion() {
        return maxCasesPerVersion;
    }

    public void setMaxCasesPerVersion(int maxCasesPerVersion) {
        this.maxCasesPerVersion = Math.max(1, Math.min(200, maxCasesPerVersion));
    }

    public int getMaxVariantsPerRun() {
        return maxVariantsPerRun;
    }

    public void setMaxVariantsPerRun(int maxVariantsPerRun) {
        this.maxVariantsPerRun = Math.max(1, Math.min(4, maxVariantsPerRun));
    }

    public int getSemanticBatchLimit() {
        return semanticBatchLimit;
    }

    public void setSemanticBatchLimit(int semanticBatchLimit) {
        this.semanticBatchLimit = Math.max(1, Math.min(50, semanticBatchLimit));
    }
}
