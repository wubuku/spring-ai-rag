package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
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
    void getAsyncExecutor_usesPropertyDefaults() {
        RagProperties props = new RagProperties();
        AsyncConfig config = new AsyncConfig(props);

        ThreadPoolTaskExecutor executor =
                (ThreadPoolTaskExecutor) config.getAsyncExecutor();

        // 原来这条只有 assertNotNull + assertInstanceOf，而隔壁那条
        // getAsyncExecutor_usesCustomSettings 已经把类型和自定义值都验了。
        // 这里补上"没配的时候用的是属性默认值"这半边——默认值变了要红。
        assertEquals(props.getAsync().getCorePoolSize(),
                executor.getCorePoolSize());
        assertEquals(props.getAsync().getMaxPoolSize(),
                executor.getMaxPoolSize());
        assertEquals(props.getAsync().getQueueCapacity(),
                executor.getQueueCapacity());
    }

    @Test
    void getAsyncUncaughtExceptionHandler_logsTheFailure() throws Exception {
        RagProperties props = new RagProperties();
        AsyncConfig config = new AsyncConfig(props);

        AsyncUncaughtExceptionHandler handler =
                config.getAsyncUncaughtExceptionHandler();

        assertNotNull(handler);
        // Batch 954：原来只有 assertNotNull(handler)，这个处理器的方法体
        // 一次都没被执行过——异步方法抛的异常到底有没有被记下来，无人知道。
        // 现在真的喂一个进去，并从日志侧确认它**记下来了**而不是静默吞掉。
        ch.qos.logback.classic.Logger handlerLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        "com.springairag.core.config.AsyncConfig$RagAsyncExceptionHandler");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
                appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
        try {
            Thread victim = new Thread(() -> { }, "async-victim");
            java.lang.reflect.Method method = Object.class.getMethod("toString");
            IllegalStateException boom = new IllegalStateException("boom");
            assertDoesNotThrow(() ->
                    handler.handleUncaughtException(boom, method));
            assertFalse(victim.isInterrupted(),
                    "处理器不得中断出错的线程");

            var errors = appender.list.stream()
                    .filter(event -> event.getLevel()
                            == ch.qos.logback.classic.Level.ERROR)
                    .toList();
            assertEquals(1, errors.size(),
                    "异步异常必须以 ERROR 记一条：" + appender.list);
            assertTrue(errors.get(0).getFormattedMessage().contains("boom"),
                    "日志应带上异常信息：" + errors.get(0).getFormattedMessage());
            assertNotNull(errors.get(0).getThrowableProxy(),
                    "异常对象本身要挂上去，不能只剩一句话");
            assertEquals(IllegalStateException.class.getName(),
                    errors.get(0).getThrowableProxy().getClassName());
        } finally {
            handlerLogger.detachAppender(appender);
            appender.stop();
        }
    }
}
