package com.springairag.core.chat;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagChatHistoryRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 跨实例会话 single-flight、绝对截止时间和 token-fenced 持久化协调器。
 */
@Component
public class ChatSessionCoordinator {

    private static final String ACQUIRE_SQL = """
            INSERT INTO rag_chat_session_lease
                (owner_principal_id, session_id, owner_token, acquired_at, expires_at)
            VALUES (?, ?, ?, clock_timestamp(),
                    clock_timestamp() + (? * interval '1 millisecond'))
            ON CONFLICT (owner_principal_id, session_id)
            DO UPDATE SET
                owner_token = EXCLUDED.owner_token,
                acquired_at = EXCLUDED.acquired_at,
                expires_at = EXCLUDED.expires_at
            WHERE rag_chat_session_lease.expires_at < clock_timestamp()
            """;

    private static final String RENEW_SQL = """
            UPDATE rag_chat_session_lease
            SET expires_at = clock_timestamp() + (? * interval '1 millisecond')
            WHERE owner_principal_id = ?
              AND session_id = ?
              AND owner_token = ?
              AND expires_at > clock_timestamp()
            """;

    private static final String RELEASE_SQL = """
            DELETE FROM rag_chat_session_lease
            WHERE owner_principal_id = ?
              AND session_id = ?
              AND owner_token = ?
            """;

    private static final String CONSUME_SQL = """
            DELETE FROM rag_chat_session_lease
            WHERE owner_principal_id = ?
              AND session_id = ?
              AND owner_token = ?
              AND expires_at > clock_timestamp()
            RETURNING owner_token
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RagChatHistoryRepository historyRepository;
    private final ChatMemory sharedMemory;
    private final TransactionTemplate transactionTemplate;
    private final RagProperties properties;
    private final ScheduledExecutorService renewExecutor;
    private final ExecutorService invocationExecutor;
    private ConversationSummaryService summaryService;

    public ChatSessionCoordinator(
            JdbcTemplate jdbcTemplate,
            RagChatHistoryRepository historyRepository,
            JdbcChatMemoryRepository memoryRepository,
            PlatformTransactionManager transactionManager,
            RagProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.historyRepository = historyRepository;
        this.properties = properties;
        this.sharedMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(memoryRepository)
                .maxMessages(Math.max(2, properties.getMemory().getMaxMessages()))
                .build();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.renewExecutor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "rag-chat-lease-renew");
            thread.setDaemon(true);
            return thread;
        });
        this.invocationExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "rag-chat-invocation");
            thread.setDaemon(true);
            return thread;
        });
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSummaryService(ConversationSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    public LeaseHandle acquire(ChatCommand command, boolean streaming) {
        if (command.memoryMode() == MemoryMode.STATELESS) {
            return LeaseHandle.stateless(deadline(streaming));
        }
        String token = UUID.randomUUID().toString();
        int ttlMs = leaseTtlMs();
        int affected = jdbcTemplate.update(
                ACQUIRE_SQL,
                command.principal().id(),
                command.sessionId(),
                token,
                ttlMs);
        if (affected != 1) {
            throw new RagException(
                    ErrorCode.SESSION_BUSY,
                    "Chat session already has an active request");
        }
        LeaseHandle handle = new LeaseHandle(
                command.principal().id(),
                command.sessionId(),
                token,
                deadline(streaming),
                false);
        long renewEveryMs = renewIntervalMs();
        ScheduledFuture<?> renewal = renewExecutor.scheduleAtFixedRate(
                () -> renew(handle),
                renewEveryMs,
                renewEveryMs,
                TimeUnit.MILLISECONDS);
        handle.renewal.set(renewal);
        return handle;
    }

    public <T> T invokeWithinDeadline(
            LeaseHandle handle,
            Supplier<T> invocation) {
        assertActive(handle);
        long remaining = Duration.between(
                Instant.now(), handle.deadline).toMillis();
        if (remaining <= 0) {
            throw timeout();
        }
        CompletableFuture<T> future = CompletableFuture.supplyAsync(
                invocation,
                invocationExecutor);
        try {
            T result = future.get(remaining, TimeUnit.MILLISECONDS);
            assertActive(handle);
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw timeout();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RagException(ErrorCode.CHAT_TIMEOUT, "Chat request interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(cause);
        }
    }

    public void commit(
            LeaseHandle handle,
            ChatCommand command,
            ChatExecutionResult result,
            List<Message> committedMessages,
            String relatedDocumentIds) {
        beginCommit(handle);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (!handle.stateless) {
                    consumeLease(handle);
                }
                historyRepository.saveDurable(
                        command.principal(),
                        command.sessionId(),
                        command.message(),
                        result.answer(),
                        relatedDocumentIds,
                        result.sources(),
                        "COMPLETE",
                        result.metadata());
                if (command.memoryMode() == MemoryMode.SERVER) {
                    sharedMemory.clear(command.memoryConversationId());
                    if (committedMessages != null && !committedMessages.isEmpty()) {
                        sharedMemory.add(
                                command.memoryConversationId(),
                                committedMessages);
                    }
                }
            });
            handle.state.set(State.TERMINAL);
        } catch (RagException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RagException(
                    ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                    "Failed to commit chat history and memory",
                    e);
        }
    }

    public void release(LeaseHandle handle) {
        if (handle == null || handle.stateless) {
            return;
        }
        stopRenewal(handle);
        if (!handle.lost.get()) {
            jdbcTemplate.update(
                    RELEASE_SQL,
                    handle.principalId,
                    handle.sessionId,
                    handle.ownerToken);
        }
        handle.state.set(State.TERMINAL);
    }

    public int clearSession(
            ChatPrincipal principal,
            String sessionId) {
        String validSession = SessionIdValidator.resolve(sessionId);
        String memoryId = principal.memoryConversationId(validSession);
        ChatCommand command = new ChatCommand(
                "",
                validSession,
                principal,
                memoryId,
                com.springairag.api.enums.ChatMode.PLAIN,
                MemoryMode.SERVER,
                null,
                null,
                com.springairag.core.retrieval.RetrievalScope.noMatches(),
                new RetrievalOptions(1, 0, false, false, 0, 0),
                java.util.Map.of());
        LeaseHandle handle = acquire(command, false);
        beginCommit(handle);
        try {
            Integer deleted = transactionTemplate.execute(status -> {
                consumeLease(handle);
                int count = historyRepository.deleteByPrincipalAndSession(
                        principal, validSession);
                if (count == 0) {
                    throw new RagException(
                            ErrorCode.SESSION_NOT_FOUND,
                            "Chat session was not found");
                }
                if (summaryService != null) {
                    summaryService.clear(principal, validSession);
                }
                sharedMemory.clear(memoryId);
                return count;
            });
            handle.state.set(State.TERMINAL);
            return deleted != null ? deleted : 0;
        } finally {
            release(handle);
        }
    }

    public ChatMemory sharedMemory() {
        return sharedMemory;
    }

    private void renew(LeaseHandle handle) {
        synchronized (handle.renewMonitor) {
            if (handle.state.get() != State.RUNNING) {
                return;
            }
            try {
                int affected = jdbcTemplate.update(
                        RENEW_SQL,
                        leaseTtlMs(),
                        handle.principalId,
                        handle.sessionId,
                        handle.ownerToken);
                if (affected != 1) {
                    handle.lost.set(true);
                }
            } catch (RuntimeException e) {
                handle.lost.set(true);
            }
        }
    }

    private void beginCommit(LeaseHandle handle) {
        assertActive(handle);
        synchronized (handle.renewMonitor) {
            assertActive(handle);
            handle.state.set(State.COMMITTING);
            ScheduledFuture<?> renewal = handle.renewal.get();
            if (renewal != null) {
                renewal.cancel(false);
            }
        }
    }

    private void stopRenewal(LeaseHandle handle) {
        synchronized (handle.renewMonitor) {
            if (handle.state.get() == State.RUNNING) {
                handle.state.set(State.COMMITTING);
            }
            ScheduledFuture<?> renewal = handle.renewal.get();
            if (renewal != null) {
                renewal.cancel(false);
            }
        }
    }

    /**
     * Atomically consumes the active lease at the beginning of the commit
     * transaction. A rollback restores the lease row; a successful commit
     * releases it without an explicit lock.
     */
    private void consumeLease(LeaseHandle handle) {
        List<String> rows = jdbcTemplate.query(
                CONSUME_SQL,
                (resultSet, rowNum) -> resultSet.getString(1),
                handle.principalId,
                handle.sessionId,
                handle.ownerToken);
        if (rows.size() != 1) {
            handle.lost.set(true);
            throw leaseLost();
        }
    }

    private void assertActive(LeaseHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("lease handle must not be null");
        }
        if (Instant.now().isAfter(handle.deadline)) {
            throw timeout();
        }
        if (handle.lost.get()) {
            throw leaseLost();
        }
    }

    private Instant deadline(boolean streaming) {
        int timeoutMs = streaming
                ? properties.getTimeout().getChatStreamMs()
                : properties.getTimeout().getChatAskMs();
        return Instant.now().plusMillis(Math.max(1_000, timeoutMs));
    }

    private int leaseTtlMs() {
        int ttl = Math.max(
                10,
                properties.getChat().getHistory().getLeaseTtlSeconds());
        int renew = Math.max(
                1,
                properties.getChat().getHistory()
                        .getLeaseRenewIntervalSeconds());
        return Math.max(ttl, renew * 2 + 2) * 1_000;
    }

    private long renewIntervalMs() {
        return Math.max(
                1,
                properties.getChat().getHistory()
                        .getLeaseRenewIntervalSeconds()) * 1_000L;
    }

    private RagException timeout() {
        return new RagException(
                ErrorCode.CHAT_TIMEOUT,
                "Chat request exceeded its deadline");
    }

    private RagException leaseLost() {
        return new RagException(
                ErrorCode.CHAT_SESSION_LEASE_LOST,
                "Chat session lease ownership was lost");
    }

    @PreDestroy
    void shutdown() {
        renewExecutor.shutdownNow();
        invocationExecutor.shutdownNow();
    }

    private enum State {
        RUNNING,
        COMMITTING,
        TERMINAL
    }

    public static final class LeaseHandle {
        private final String principalId;
        private final String sessionId;
        private final String ownerToken;
        private final Instant deadline;
        private final boolean stateless;
        private final AtomicBoolean lost = new AtomicBoolean();
        private final AtomicReference<State> state =
                new AtomicReference<>(State.RUNNING);
        private final AtomicReference<ScheduledFuture<?>> renewal =
                new AtomicReference<>();
        private final Object renewMonitor = new Object();

        private LeaseHandle(
                String principalId,
                String sessionId,
                String ownerToken,
                Instant deadline,
                boolean stateless) {
            this.principalId = principalId;
            this.sessionId = sessionId;
            this.ownerToken = ownerToken;
            this.deadline = deadline;
            this.stateless = stateless;
        }

        static LeaseHandle stateless(Instant deadline) {
            return new LeaseHandle(null, null, null, deadline, true);
        }

        public Instant deadline() {
            return deadline;
        }

        public boolean lost() {
            return lost.get();
        }
    }
}
