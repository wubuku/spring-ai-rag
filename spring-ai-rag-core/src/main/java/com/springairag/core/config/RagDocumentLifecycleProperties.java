package com.springairag.core.config;

/**
 * 文档业务生命周期和外部来源同步兼容配置。
 */
public class RagDocumentLifecycleProperties {

    private boolean strictExternalCas = true;
    private boolean allowNonDefaultNamespace = true;
    private int idempotencyTtlHours = 24;

    public boolean isStrictExternalCas() { return strictExternalCas; }
    public void setStrictExternalCas(boolean value) { strictExternalCas = value; }

    public boolean isAllowNonDefaultNamespace() {
        return allowNonDefaultNamespace;
    }
    public void setAllowNonDefaultNamespace(boolean value) {
        allowNonDefaultNamespace = value;
    }

    public int getIdempotencyTtlHours() { return idempotencyTtlHours; }
    public void setIdempotencyTtlHours(int value) {
        idempotencyTtlHours = Math.max(1, Math.min(168, value));
    }
}

