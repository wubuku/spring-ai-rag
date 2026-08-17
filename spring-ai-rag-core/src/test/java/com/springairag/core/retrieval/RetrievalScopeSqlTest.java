package com.springairag.core.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.SqlArrayValue;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalScopeSqlTest {

    @Test
    void unscopedHasNoPredicate() {
        RetrievalScopeSql.Fragment fragment =
                RetrievalScopeSql.build(RetrievalScope.unscoped());

        assertEquals("", fragment.sql());
        assertTrue(fragment.args().isEmpty());
    }

    @Test
    void anyAssignedUsesIsNotNull() {
        RetrievalScopeSql.Fragment fragment = RetrievalScopeSql.build(
                RetrievalScope.anyAssigned(null, null));

        assertTrue(fragment.sql().contains(
                "d.collection_id IS NOT NULL"));
        assertTrue(fragment.args().isEmpty());
    }

    @Test
    void selectedDocumentsAndTypeUseFixedArrayPredicates() {
        RetrievalScopeSql.Fragment fragment = RetrievalScopeSql.build(
                RetrievalScope.selectedCollections(
                        List.of(1L, 2L),
                        List.of(10L, 11L),
                        "json-record"));

        assertEquals(
                "AND d.collection_id = ANY (?) "
                        + "AND e.document_id = ANY (?) "
                        + "AND d.document_type = ? ",
                fragment.sql());
        assertEquals(3, fragment.args().size());
        assertInstanceOf(SqlArrayValue.class, fragment.args().get(0));
        assertInstanceOf(SqlArrayValue.class, fragment.args().get(1));
        assertEquals("json-record", fragment.args().get(2));
    }

    @Test
    void matchNoneUsesConstantFalsePredicate() {
        RetrievalScopeSql.Fragment fragment =
                RetrievalScopeSql.build(RetrievalScope.noMatches());

        assertEquals("AND 1 = 0 ", fragment.sql());
        assertTrue(fragment.args().isEmpty());
    }

    @Test
    void jsonbContainmentUsesBoundParameterAfterScopePredicates() {
        RetrievalScopeSql.Fragment fragment = RetrievalScopeSql.build(
                RetrievalScope.selectedCollections(
                        List.of(7L), null, "json-record"),
                new JsonbContainmentFilter(
                        "{\"status\":\"active\"}"));

        assertEquals(
                "AND d.collection_id = ANY (?) "
                        + "AND d.document_type = ? "
                        + "AND d.jsonb_payload @> CAST(? AS jsonb) ",
                fragment.sql());
        assertEquals(3, fragment.args().size());
        assertInstanceOf(SqlArrayValue.class, fragment.args().get(0));
        assertEquals("json-record", fragment.args().get(1));
        assertEquals(
                "{\"status\":\"active\"}",
                fragment.args().get(2));
    }
}
