package com.springairag.core.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HistoryAwareQueryTransformerTest {

    private QueryTransformer firstTurn;
    private QueryTransformer followUp;
    private HistoryAwareQueryTransformer transformer;

    @BeforeEach
    void setUp() {
        firstTurn = mock(QueryTransformer.class);
        followUp = mock(QueryTransformer.class);
        transformer = new HistoryAwareQueryTransformer(
                firstTurn, followUp, Duration.ofSeconds(5));
    }

    private Query query(String text, Message... history) {
        return Query.builder()
                .text(text)
                .history(List.of(history))
                .build();
    }

    @Test
    void nullQueryPassesThrough() {
        assertNull(transformer.transform(null));
    }

    @Test
    void firstTurnDelegatesToFirstTurnTransformer() {
        Query q = query("what is RAG?");
        Query expected = Query.builder().text("transformed").build();
        when(firstTurn.transform(any(Query.class))).thenReturn(expected);

        Query result = transformer.transform(q);
        assertEquals("transformed", result.text());
        verify(firstTurn).transform(any(Query.class));
        verify(followUp, never()).transform(any());
    }

    @Test
    void followUpWithHistoryDelegatesToFollowUpTransformer() {
        Query q = query("and what about evaluation?",
                new UserMessage("what is RAG?"),
                new AssistantMessage("RAG is retrieval-augmented generation."));
        Query expected = Query.builder().text("compressed query").build();
        when(followUp.transform(any(Query.class))).thenReturn(expected);

        Query result = transformer.transform(q);
        assertEquals("compressed query", result.text());
        verify(followUp).transform(any(Query.class));
    }

    @Test
    void systemMessagesAreExcludedFromPriorHistory() {
        Query q = Query.builder()
                .text("follow-up question")
                .history(List.of(
                        new SystemMessage("system instructions"),
                        new UserMessage("prior question"),
                        new AssistantMessage("prior answer")))
                .build();
        Query expected = Query.builder().text("done").build();
        when(followUp.transform(any())).thenReturn(expected);

        transformer.transform(q);
        verify(followUp).transform(any());
    }

    @Test
    void timeoutFallsBackToOriginalQuery() throws InterruptedException {
        // Batch 950：原来是 Thread.sleep(10_000)。这里要表达的只是"模型调用比
        // 50ms 的预算更久"，10 秒里除了"够久"之外没有任何信息量，却留下一条睡
        // 满 10 秒的线程。改成由测试自己控制的 latch：阻塞是确定性的（不依赖
        // 调度快慢），收尾时 countDown 把那条线程放掉，不会泄漏。
        CountDownLatch release = new CountDownLatch(1);
        QueryTransformer slowTransformer = mock(QueryTransformer.class);
        when(slowTransformer.transform(any(Query.class)))
                .thenAnswer(inv -> {
                    release.await();
                    return Query.builder().text("late").build();
                });
        var slowService = new HistoryAwareQueryTransformer(
                slowTransformer, slowTransformer, Duration.ofMillis(50));

        Query q = query("stale query");
        try {
            Query result = slowService.transform(q);
            assertEquals(q, result);
        } finally {
            release.countDown();
        }
    }

    @Test
    void transformExceptionFallsBackToOriginalQuery() {
        QueryTransformer failing = mock(QueryTransformer.class);
        when(failing.transform(any(Query.class)))
                .thenThrow(new RuntimeException("transform exploded"));
        var service = new HistoryAwareQueryTransformer(
                failing, failing, Duration.ofSeconds(5));

        Query q = query("test");
        Query result = service.transform(q);
        assertEquals(q, result);
    }
}
