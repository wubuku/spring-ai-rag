package com.springairag.core.retrieval.rerank;

import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RerankProviderFactoryTest {

    @Test
    void create_heuristicByDefault() {
        RagProperties props = new RagProperties();
        props.getRerank().setProvider("heuristic");
        assertEquals("heuristic", new RerankProviderFactory(props).create().getName());
    }

    @Test
    void create_none() {
        RagProperties props = new RagProperties();
        props.getRerank().setProvider("none");
        assertEquals("none", new RerankProviderFactory(props).create().getName());
    }

    @Test
    void create_http() {
        RagProperties props = new RagProperties();
        props.getRerank().setProvider("http");
        props.getRerank().setApiKey("sk");
        assertEquals("http", new RerankProviderFactory(props).create().getName());
    }
}
