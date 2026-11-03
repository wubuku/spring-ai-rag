package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 超时配置默认值与启动期范围校验。
 */
class RagTimeoutPropertiesTest {

    @Test
    void defaults_haveReasonableValues() {
        RagTimeoutProperties props = new RagTimeoutProperties();

        assertEquals(10_000, props.getConnectTimeoutMs());
        assertEquals(60_000, props.getReadTimeoutMs());
        assertEquals(120_000, props.getChatAskMs());
        assertEquals(180_000, props.getChatStreamMs());
        assertEquals(30_000, props.getSearchMs());
        assertEquals(60_000, props.getEmbedMs());
        assertEquals(90_000, props.getModelCompareMs());
        assertDoesNotThrow(props::validate);
    }

    @Test
    void setters_updateValues() {
        RagTimeoutProperties props = new RagTimeoutProperties();

        props.setConnectTimeoutMs(5_000);
        props.setReadTimeoutMs(30_000);
        props.setChatAskMs(60_000);
        props.setChatStreamMs(120_000);
        props.setSearchMs(15_000);
        props.setEmbedMs(45_000);
        props.setModelCompareMs(60_000);

        assertEquals(5_000, props.getConnectTimeoutMs());
        assertEquals(30_000, props.getReadTimeoutMs());
        assertEquals(60_000, props.getChatAskMs());
        assertEquals(120_000, props.getChatStreamMs());
        assertEquals(15_000, props.getSearchMs());
        assertEquals(45_000, props.getEmbedMs());
        assertEquals(60_000, props.getModelCompareMs());
    }

    @Test
    void chatStreamTimeout_largerThanChatAsk() {
        RagTimeoutProperties props = new RagTimeoutProperties();
        // Streaming typically needs longer timeout than non-streaming
        assertTrue(props.getChatStreamMs() > props.getChatAskMs());
    }

    @Test
    void searchTimeout_lessThanEmbed() {
        RagTimeoutProperties props = new RagTimeoutProperties();
        // Search is typically faster than embedding large documents
        assertTrue(props.getSearchMs() < props.getEmbedMs());
    }

    @Test
    void connectTimeoutBelowMinimumFailsWithPropertyName() {
        RagTimeoutProperties properties = new RagTimeoutProperties();
        properties.setConnectTimeoutMs(0);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                properties::validate);
        assertTrue(error.getMessage().contains("rag.timeout.connect-timeout-ms"));
    }

    @Test
    void readTimeoutAboveMaximumFailsWithPropertyName() {
        RagTimeoutProperties properties = new RagTimeoutProperties();
        properties.setReadTimeoutMs(600_001);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                properties::validate);
        assertTrue(error.getMessage().contains("rag.timeout.read-timeout-ms"));
    }

    @Test
    void chatTimeoutsAreBounded() {
        RagTimeoutProperties properties = new RagTimeoutProperties();
        properties.setChatAskMs(0);
        assertThrows(IllegalStateException.class, properties::validate);

        properties = new RagTimeoutProperties();
        properties.setChatStreamMs(1_800_001);
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void searchEmbedAndCompareTimeoutsAreBounded() {
        assertRangeFailure(properties -> properties.setSearchMs(0),
                "rag.timeout.search-ms");
        assertRangeFailure(properties -> properties.setEmbedMs(600_001),
                "rag.timeout.embed-ms");
        assertRangeFailure(properties -> properties.setModelCompareMs(0),
                "rag.timeout.model-compare-ms");
    }

    private void assertRangeFailure(java.util.function.Consumer<RagTimeoutProperties> mutation,
                                    String propertyKey) {
        RagTimeoutProperties properties = new RagTimeoutProperties();
        mutation.accept(properties);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                properties::validate);
        assertTrue(error.getMessage().contains(propertyKey));
    }
}
