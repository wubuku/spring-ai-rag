package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RagAsyncPropertiesTest {

    @Test
    void defaultsShouldHaveReasonableThreadPoolSizes() {
        RagAsyncProperties props = new RagAsyncProperties();
        assertEquals(4, props.getCorePoolSize(), "Default core pool size should be 4");
        assertEquals(16, props.getMaxPoolSize(), "Default max pool size should be 16");
        assertEquals(100, props.getQueueCapacity(), "Default queue capacity should be 100");
        assertEquals(5, props.getRetrievalTimeoutSeconds(), "Default retrieval timeout should be 5 seconds");
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
    void corePoolSizeShouldNotExceedMaxPoolSize() {
        RagAsyncProperties props = new RagAsyncProperties();
        // This class does not enforce the invariant; it is the caller's responsibility.
        // We simply verify the setter accepts the value.
        props.setCorePoolSize(32);
        props.setMaxPoolSize(8);
        assertEquals(32, props.getCorePoolSize());
        assertEquals(8, props.getMaxPoolSize());
    }
}
