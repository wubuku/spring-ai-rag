package com.springairag.core.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 查询改写配置
 */
public class RagQueryRewriteProperties {

    private boolean enabled = true;
    private int paddingCount = 2;
    private Map<String, String[]> synonymDictionary = Collections.emptyMap();
    private List<String> domainQualifiers = Collections.emptyList();
    private boolean llmEnabled = false;
    private int llmMaxRewrites = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPaddingCount() {
        return paddingCount;
    }

    public void setPaddingCount(int paddingCount) {
        this.paddingCount = paddingCount;
    }

    public Map<String, String[]> getSynonymDictionary() {
        return synonymDictionary;
    }

    public void setSynonymDictionary(Map<String, String[]> synonymDictionary) {
        this.synonymDictionary = synonymDictionary;
    }

    public List<String> domainQualifiers() {
        return domainQualifiers;
    }

    public void setDomainQualifiers(List<String> domainQualifiers) {
        this.domainQualifiers = domainQualifiers;
    }

    public boolean isLlmEnabled() {
        return llmEnabled;
    }

    public void setLlmEnabled(boolean llmEnabled) {
        this.llmEnabled = llmEnabled;
    }

    public int getLlmMaxRewrites() {
        return llmMaxRewrites;
    }

    public void setLlmMaxRewrites(int llmMaxRewrites) {
        this.llmMaxRewrites = llmMaxRewrites;
    }

    // 保持向后兼容的 getter 名
    public List<String> getDomainQualifiers() {
        return domainQualifiers;
    }
}
