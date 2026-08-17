package com.springairag.core.config;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.MemoryMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 兼容入口配置。
 *
 * <p>公开 model alias 只描述 Chat pipeline 和后端候选，不包含 Collection 范围。
 * Collection 必须由每个请求显式提供，或按调用者当前可见范围动态解析。</p>
 */
public class RagOpenAiCompatibilityProperties {

    private boolean enabled;
    private boolean requireExplicitScope;
    private Map<String, ModelAlias> models = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequireExplicitScope() {
        return requireExplicitScope;
    }

    public void setRequireExplicitScope(boolean requireExplicitScope) {
        this.requireExplicitScope = requireExplicitScope;
    }

    public Map<String, ModelAlias> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelAlias> models) {
        this.models = models != null ? new LinkedHashMap<>(models) : new LinkedHashMap<>();
    }

    public static class ModelAlias {
        private List<String> candidates = new ArrayList<>();
        private ChatMode mode = ChatMode.KNOWLEDGE;
        private MemoryMode memory = MemoryMode.STATELESS;
        private boolean allowRequestModeOverride;
        private boolean allowRequestMemoryOverride;

        public List<String> getCandidates() {
            return candidates;
        }

        public void setCandidates(List<String> candidates) {
            this.candidates = candidates != null
                    ? new ArrayList<>(candidates)
                    : new ArrayList<>();
        }

        public ChatMode getMode() {
            return mode;
        }

        public void setMode(ChatMode mode) {
            this.mode = mode != null ? mode : ChatMode.KNOWLEDGE;
        }

        public MemoryMode getMemory() {
            return memory;
        }

        public void setMemory(MemoryMode memory) {
            this.memory = memory != null ? memory : MemoryMode.STATELESS;
        }

        public boolean isAllowRequestModeOverride() {
            return allowRequestModeOverride;
        }

        public void setAllowRequestModeOverride(boolean allowRequestModeOverride) {
            this.allowRequestModeOverride = allowRequestModeOverride;
        }

        public boolean isAllowRequestMemoryOverride() {
            return allowRequestMemoryOverride;
        }

        public void setAllowRequestMemoryOverride(boolean allowRequestMemoryOverride) {
            this.allowRequestMemoryOverride = allowRequestMemoryOverride;
        }
    }
}
