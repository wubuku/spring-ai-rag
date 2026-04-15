package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagQueryRewriteProperties.
 */
class RagQueryRewritePropertiesTest {

    @Test
    void defaults_enabledIsTrue() {
        RagQueryRewriteProperties props = new RagQueryRewriteProperties();
        assertTrue(props.isEnabled());
    }

    @Test
    void defaults_paddingCountIs2() {
        RagQueryRewriteProperties props = new RagQueryRewriteProperties();
        assertEquals(2, props.getPaddingCount());
    }

    @Test
    void defaults_synonymDictionaryIsEmpty() {
        RagQueryRewriteProperties props = new RagQueryRewriteProperties();
        assertEquals(Collections.emptyMap(), props.getSynonymDictionary());
    }

    @Test
    void defaults_domainQualifiersIsEmpty() {
        RagQueryRewriteProperties props = new RagQueryRewriteProperties();
        assertEquals(Collections.emptyList(), props.domainQualifiers());
        assertEquals(Collections.emptyList(), props.getDomainQualifiers());
    }

    @Test
    void defaults_llmEnabledIsFalse() {
        RagQueryRewriteProperties props = new RagQueryRewriteProperties();
        assertFalse(props.isLlmEnabled());
    }

    @Test
    void defaults_llmMaxRewritesIs3() {
        RagQueryRewriteProperties props = new RagQueryRewriteProperties();
        assertEquals(3, props.getLlmMaxRewrites());
    }

    @Test
    void setters_updateAllValues() {
        RagQueryRewriteProperties props = new RagQueryRewriteProperties();

        Map<String, String[]> synonyms = new HashMap<>();
        synonyms.put("AI", new String[]{"Artificial Intelligence", "Machine Learning"});
        synonyms.put("RAG", new String[]{"Retrieval Augmented Generation"});

        List<String> qualifiers = List.of("medical", "legal", "technical");

        props.setEnabled(false);
        props.setPaddingCount(5);
        props.setSynonymDictionary(synonyms);
        props.setDomainQualifiers(qualifiers);
        props.setLlmEnabled(true);
        props.setLlmMaxRewrites(10);

        assertFalse(props.isEnabled());
        assertEquals(5, props.getPaddingCount());
        assertEquals(2, props.getSynonymDictionary().size());
        assertArrayEquals(new String[]{"Artificial Intelligence", "Machine Learning"},
                props.getSynonymDictionary().get("AI"));
        assertEquals(qualifiers, props.domainQualifiers());
        assertEquals(qualifiers, props.getDomainQualifiers());
        assertTrue(props.isLlmEnabled());
        assertEquals(10, props.getLlmMaxRewrites());
    }

    @Test
    void setters_acceptBoundaryValues() {
        RagQueryRewriteProperties props = new RagQueryRewriteProperties();

        props.setEnabled(false);
        props.setPaddingCount(0);
        props.setLlmEnabled(false);
        props.setLlmMaxRewrites(0);

        assertFalse(props.isEnabled());
        assertEquals(0, props.getPaddingCount());
        assertFalse(props.isLlmEnabled());
        assertEquals(0, props.getLlmMaxRewrites());
    }

    @Test
    void domainQualifiers_getterAndMethodReturnSameValue() {
        RagQueryRewriteProperties props = new RagQueryRewriteProperties();
        List<String> qualifiers = List.of("finance", "healthcare");
        props.setDomainQualifiers(qualifiers);

        assertEquals(qualifiers, props.domainQualifiers());
        assertEquals(qualifiers, props.getDomainQualifiers());
    }

    @Test
    void synonymDictionary_supportsMultipleSynonyms() {
        RagQueryRewriteProperties props = new RagQueryRewriteProperties();

        Map<String, String[]> synonyms = new HashMap<>();
        synonyms.put("K8s", new String[]{"Kubernetes", "k8s"});
        synonyms.put("ML", new String[]{"Machine Learning", "ML", "ml"});
        synonyms.put("LLM", new String[]{"Large Language Model", "LLM", "llm"});

        props.setSynonymDictionary(synonyms);

        assertEquals(3, props.getSynonymDictionary().size());
        assertArrayEquals(new String[]{"Kubernetes", "k8s"}, props.getSynonymDictionary().get("K8s"));
        assertArrayEquals(new String[]{"Large Language Model", "LLM", "llm"}, props.getSynonymDictionary().get("LLM"));
    }
}
