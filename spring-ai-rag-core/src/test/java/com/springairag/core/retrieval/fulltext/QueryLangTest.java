package com.springairag.core.retrieval.fulltext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link QueryLang} enum.
 *
 * <p>Ensures the language classification enum has the expected constants and semantics
 * used by {@link FulltextSearchProviderFactory} to select the correct FTS provider.
 */
@DisplayName("QueryLang Enum Tests")
class QueryLangTest {

    @Test
    @DisplayName("ZH constant exists")
    void zh_constantExists() {
        assertNotNull(QueryLang.ZH);
        assertEquals("ZH", QueryLang.ZH.name());
    }

    @Test
    @DisplayName("EN_OR_OTHER constant exists")
    void enOrOther_constantExists() {
        assertNotNull(QueryLang.EN_OR_OTHER);
        assertEquals("EN_OR_OTHER", QueryLang.EN_OR_OTHER.name());
    }

    @Test
    @DisplayName("has exactly two values")
    void values_hasTwoElements() {
        QueryLang[] values = QueryLang.values();
        assertEquals(2, values.length);
    }

    @Test
    @DisplayName("valueOf ZH works")
    void valueOf_zh_returnsZh() {
        assertEquals(QueryLang.ZH, QueryLang.valueOf("ZH"));
    }

    @Test
    @DisplayName("valueOf EN_OR_OTHER works")
    void valueOf_enOrOther_returnsEnOrOther() {
        assertEquals(QueryLang.EN_OR_OTHER, QueryLang.valueOf("EN_OR_OTHER"));
    }

    @Test
    @DisplayName("ZH and EN_OR_OTHER are different")
    void zh_and_enOrOther_areDifferent() {
        assertNotEquals(QueryLang.ZH, QueryLang.EN_OR_OTHER);
    }

    @Test
    @DisplayName("ZH ordinal is 0")
    void zh_ordinalIsZero() {
        assertEquals(0, QueryLang.ZH.ordinal());
    }

    @Test
    @DisplayName("EN_OR_OTHER ordinal is 1")
    void enOrOther_ordinalIsOne() {
        assertEquals(1, QueryLang.EN_OR_OTHER.ordinal());
    }
}
