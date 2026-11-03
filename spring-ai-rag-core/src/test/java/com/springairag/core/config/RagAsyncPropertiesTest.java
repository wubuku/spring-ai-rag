package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 线程池参数默认值与启动期校验。
 */
class RagAsyncPropertiesTest {

    @Test
    void defaultsShouldHaveReasonableThreadPoolSizes() {
        RagAsyncProperties props = new RagAsyncProperties();
        assertEquals(4, props.getCorePoolSize(), "Default core pool size should be 4");
        assertEquals(16, props.getMaxPoolSize(), "Default max pool size should be 16");
        assertEquals(100, props.getQueueCapacity(), "Default queue capacity should be 100");
        assertEquals(5, props.getRetrievalTimeoutSeconds(), "Default retrieval timeout should be 5 seconds");
        assertDoesNotThrow(props::validate);
    }

    @Test
    void settersAndGettersShouldWork() {
        RagAsyncProperties props = new RagAsyncProperties();
        props.setCorePoolSize(8);
        props.setMaxPoolSize(32);
        props.setQueueCapacity(200);
        props.setRetrievalTimeoutSeconds(10);

        assertEquals(8, props.getCorePoolSize());
        assertEquals(32, props.getMaxPoolSize());
        assertEquals(200, props.getQueueCapacity());
        assertEquals(10, props.getRetrievalTimeoutSeconds());
    }

    @Test
    void corePoolSizeMustBePositive() {
        RagAsyncProperties properties = new RagAsyncProperties();
        properties.setCorePoolSize(0);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                properties::validate);
        assertTrue(error.getMessage().contains("rag.async.core-pool-size"));
    }

    @Test
    void maxPoolSizeMustNotBeBelowCorePoolSize() {
        RagAsyncProperties properties = new RagAsyncProperties();
        properties.setCorePoolSize(32);
        properties.setMaxPoolSize(8);
        // setter 仍接受该值，但启动期校验会显式拒绝。
        assertEquals(32, properties.getCorePoolSize());
        assertEquals(8, properties.getMaxPoolSize());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                properties::validate);
        assertTrue(error.getMessage().contains("rag.async.max-pool-size"));
    }

    @Test
    void queueCapacityMustBePositive() {
        RagAsyncProperties properties = new RagAsyncProperties();
        properties.setQueueCapacity(0);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                properties::validate);
        assertTrue(error.getMessage().contains("rag.async.queue-capacity"));
    }

    @Test
    void retrievalTimeoutMustStayInRange() {
        RagAsyncProperties properties = new RagAsyncProperties();
        properties.setRetrievalTimeoutSeconds(0);
        assertThrows(IllegalStateException.class, properties::validate);

        properties.setRetrievalTimeoutSeconds(301);
        assertThrows(IllegalStateException.class, properties::validate);
    }
}
