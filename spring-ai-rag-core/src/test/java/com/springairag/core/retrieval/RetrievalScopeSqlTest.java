package com.springairag.core.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalScopeSqlTest {

    @Test
    void metadataAndStackedPayloadFiltersBindBeforeLimit() {
        RetrievalFilters filters = new RetrievalFilters(
                new JsonbContainmentFilter("{\"tenant\":\"acme\"}"),
                List.of(
                        new JsonbContainmentFilter("{\"status\":\"active\"}"),
                        new JsonbContainmentFilter("{\"sku\":\"S-1\"}")));
        RetrievalScopeSql.Fragment fragment = RetrievalScopeSql.build(
                RetrievalScope.selectedCollections(List.of(7L), List.of(11L), null),
                filters);
        assertTrue(fragment.sql().contains("d.metadata @> CAST(? AS jsonb)"));
        assertEquals(2, fragment.sql().split("jsonb_payload @>", -1).length - 1);
        assertEquals("{\"tenant\":\"acme\"}", fragment.args().get(2));
        assertEquals("{\"status\":\"active\"}", fragment.args().get(3));
        assertEquals("{\"sku\":\"S-1\"}", fragment.args().get(4));
    }
}
