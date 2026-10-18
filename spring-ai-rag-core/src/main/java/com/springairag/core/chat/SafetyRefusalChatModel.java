package com.springairag.core.chat;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

final class SafetyRefusalChatModel implements ChatModel {

    private final ChatModel delegate;

    SafetyRefusalChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        com.springairag.core.evaluation.EvaluationDiagnosticCapture.prompt(prompt);
        return ModelSafetyRefusalNormalizer.normalize(delegate.call(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt).map(ModelSafetyRefusalNormalizer::normalize);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
