package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagTimeoutProperties 单元测试
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
}
