package com.springairag.core.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 根据结构化历史决定是否调用 Spring AI 内置的对话压缩转换器。
 *
 * <p>该类不解析自然语言，也不自行改写查询；首轮直接保留原查询，有前序对话时才委派
 * compression transformer，并在超时或失败时回退原查询。</p>
 */
public final class HistoryAwareQueryTransformer implements QueryTransformer {

    private static final Logger log =
            LoggerFactory.getLogger(HistoryAwareQueryTransformer.class);

    private final QueryTransformer firstTurnTransformer;
    private final QueryTransformer followUpTransformer;
    private final Duration timeout;

    public HistoryAwareQueryTransformer(
            QueryTransformer firstTurnTransformer,
            QueryTransformer followUpTransformer,
            Duration timeout) {
        this.firstTurnTransformer = firstTurnTransformer;
        this.followUpTransformer = followUpTransformer;
        this.timeout = timeout != null && !timeout.isNegative() && !timeout.isZero()
                ? timeout
                : Duration.ofSeconds(10);
    }

    @Override
    public Query transform(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return query;
        }
        List<Message> priorHistory = priorHistory(query);
        Query input = query.mutate().history(priorHistory).build();
        QueryTransformer delegate = priorHistory.isEmpty()
                ? firstTurnTransformer
                : followUpTransformer;
        if (delegate == null) {
            return query;
        }
        try {
            return CompletableFuture.supplyAsync(() -> delegate.transform(input))
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(error -> {
                        log.warn("Spring AI 查询转换失败，回退原查询: {}", error.getMessage());
                        return query;
                    })
                    .join();
        } catch (RuntimeException e) {
            log.warn("Spring AI 查询转换失败，回退原查询: {}", e.getMessage());
            return query;
        }
    }

    private List<Message> priorHistory(Query query) {
        List<Message> history = query.history();
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
                .filter(message -> !(message instanceof SystemMessage))
                .filter(message -> message instanceof UserMessage
                        || message instanceof AssistantMessage)
                .filter(message -> !isCurrentUserMessage(message, query.text()))
                .toList();
    }

    private boolean isCurrentUserMessage(Message message, String currentText) {
        return message instanceof UserMessage userMessage
                && currentText.equals(userMessage.getText());
    }
}
