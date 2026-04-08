package com.springairag.core.extension;

import com.springairag.api.service.PromptCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Prompt Customizer Chain
 *
 * <p>Collects all {@link PromptCustomizer} implementations and chains them in order
 * determined by {@link PromptCustomizer#getOrder()}.
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
     * Chain-customize the system prompt.
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
     * Chain-customize the user message.
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
     * Check if any customizers are registered.
     */
    public boolean hasCustomizers() {
        return !customizers.isEmpty();
    }
}
