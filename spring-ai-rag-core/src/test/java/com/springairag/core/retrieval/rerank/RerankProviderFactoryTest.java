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
    void create_noneAliases() {
        for (String alias : new String[]{"none", "noop", "off"}) {
            RagProperties props = new RagProperties();
            props.getRerank().setProvider(alias);
            assertEquals(
                    "none",
                    new RerankProviderFactory(props).create().getName(),
                    alias);
        }
    }

    @Test
    void create_httpAliases() {
        for (String alias : new String[]{
                "http", "api", "siliconflow", "remote"}) {
            RagProperties props = new RagProperties();
            props.getRerank().setProvider(alias);
            props.getRerank().setApiKey("sk");
            assertEquals(
                    "http",
                    new RerankProviderFactory(props).create().getName(),
                    alias);
        }
    }

    @Test
    void create_unknownProviderFallsBackToHeuristic() {
        RagProperties props = new RagProperties();
        props.getRerank().setProvider("future-provider");

        assertEquals(
                "heuristic",
                new RerankProviderFactory(props).create().getName());
    }
}
