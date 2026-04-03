package com.springairag.core.config;

/**
 * 重排序配置
 */
public class RagRerankProperties {

    private boolean enabled = false;
    private float diversityWeight = 0.2f;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public float getDiversityWeight() {
        return diversityWeight;
    }

    public void setDiversityWeight(float diversityWeight) {
        this.diversityWeight = diversityWeight;
    }
}
