package com.springairag.core.extension;

import com.springairag.api.service.PromptCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Prompt 定制器链
 *
 * <p>收集所有 {@link PromptCustomizer} 实现，按 {@link PromptCustomizer#getOrder()} 排序后链式调用。
 */
@Component
public class PromptCustomizerChain {

    private static final Logger log = LoggerFactory.getLogger(PromptCustomizerChain.class);

    private final List<PromptCustomizer> customizers;

    public PromptCustomizerChain(List<PromptCustomizer> customizerList) {
        this.customizers = customizerList != null
                ? customizerList.stream()
                    .sorted(Comparator.comparingInt(PromptCustomizer::getOrder))
                    .toList()
                : List.of();

        if (!customizers.isEmpty()) {
            log.info("PromptCustomizerChain initialized with {} customizers", customizers.size());
        }
    }

    /**
     * 链式定制系统提示词
     */
    public String customizeSystemPrompt(String originalSystemPrompt,
                                         String context,
                                         Map<String, Object> metadata) {
        String result = originalSystemPrompt;
        for (PromptCustomizer customizer : customizers) {
            result = customizer.customizeSystemPrompt(result, context, metadata);
        }
        return result;
    }

    /**
     * 链式定制用户消息
     */
    public String customizeUserMessage(String originalUserMessage,
                                        Map<String, Object> metadata) {
        String result = originalUserMessage;
        for (PromptCustomizer customizer : customizers) {
            result = customizer.customizeUserMessage(result, metadata);
        }
        return result;
    }

    /**
     * 是否有注册的定制器
     */
    public boolean hasCustomizers() {
        return !customizers.isEmpty();
    }
}
