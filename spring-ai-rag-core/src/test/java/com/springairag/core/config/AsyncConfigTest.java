package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AsyncConfig Unit Tests
 */
class AsyncConfigTest {

    @Test
    void getAsyncExecutor_createsThreadPool() {
        RagProperties props = new RagProperties();
        AsyncConfig config = new AsyncConfig(props);

        Executor executor = config.getAsyncExecutor();

        assertNotNull(executor);
        assertInstanceOf(ThreadPoolTaskExecutor.class, executor);
    }

    @Test
    void getAsyncExecutor_usesCustomSettings() {
        RagProperties props = new RagProperties();
        props.getAsync().setCorePoolSize(2);
        props.getAsync().setMaxPoolSize(8);
        props.getAsync().setQueueCapacity(50);

        AsyncConfig config = new AsyncConfig(props);
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.getAsyncExecutor();

        assertEquals(2, executor.getCorePoolSize());
        assertEquals(8, executor.getMaxPoolSize());
    }

    @Test
    void getAsyncUncaughtExceptionHandler_returnsHandler() {
        RagProperties props = new RagProperties();
        AsyncConfig config = new AsyncConfig(props);

        assertNotNull(config.getAsyncUncaughtExceptionHandler());
    }
}
