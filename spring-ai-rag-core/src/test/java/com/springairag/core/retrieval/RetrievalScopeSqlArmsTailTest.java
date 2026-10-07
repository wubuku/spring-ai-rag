package com.springairag.core.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索范围 SQL 长尾（Batch 964，JaCoCo 驱动）：三个便捷重载、null
 * scope/filters 回退、matchNone 短路、ANY_ASSIGNED 臂、Fragment 的
 * null 归一。
 */
class RetrievalScopeSqlArmsTailTest {

    @Test
    void oneArgOverloadDefaultsToNoFilters() {
        var scope = RetrievalScope.selectedCollections(
                List.of(1L, 2L), List.of(7L), "pdf");

        var oneArg = RetrievalScopeSql.build(scope);
        var twoArg = RetrievalScopeSql.build(scope, RetrievalFilters.none());

        // 一参重载等价于"无过滤器"重载：SQL 一致；SqlArrayValue 未实现
        // equals，参数列表只比长度与非数组元素。
        assertEquals(twoArg.sql(), oneArg.sql());
        assertEquals(twoArg.args().size(), oneArg.args().size());
        assertEquals(twoArg.args().get(2), oneArg.args().get(2));
        assertTrue(oneArg.sql().contains("e.document_id = ANY (?)"));
    }

    @Test
    void payloadFilterOverloadWrapsSingleContainmentFilter() {
        var scope = RetrievalScope.unscoped(List.of(5L), null);

        var fragment = RetrievalScopeSql.build(
                scope, new JsonbContainmentFilter("{\"k\":1}"));

        assertTrue(fragment.sql().contains("e.document_id = ANY (?)"));
        assertTrue(fragment.sql().contains("d.jsonb_payload @> CAST(? AS jsonb)"));
        assertEquals(2, fragment.args().size());
        assertEquals("{\"k\":1}", fragment.args().get(1));
    }

    @Test
    void nullScopeAndNullFiltersFallBackToUnscopedNone() {
        assertEquals("", RetrievalScopeSql
                .build(null, (RetrievalFilters) null).sql());

        // 文档只读探针同样回退：null scope → unscoped，空 SQL。
        assertEquals("", RetrievalScopeSql
                .buildDocumentOnly(null, RetrievalFilters.none()).sql());
    }

    @Test
    void matchNoneScopeShortCircuitsToOneEqualsZero() {
        // SELECTED + 空 collectionIds 在构造器里即置 matchNone。
        var scope = RetrievalScope.selectedCollections(
                List.of(), List.of(), null);

        var fragment = RetrievalScopeSql.build(scope, RetrievalFilters.none());

        assertEquals("AND 1 = 0 ", fragment.sql());
        assertTrue(fragment.args().isEmpty());
    }

    @Test
    void anyAssignedScopeAppendsCollectionIdNotNull() {
        var scope = RetrievalScope.anyAssigned(List.of(), null);

        var fragment = RetrievalScopeSql.build(scope, RetrievalFilters.none());

        assertEquals("AND d.collection_id IS NOT NULL ", fragment.sql());
    }

    @Test
    void fragmentCanonicalizesNullSqlAndArgs() {
        var fragment = new RetrievalScopeSql.Fragment(null, null);

        assertEquals("", fragment.sql());
        assertTrue(fragment.args().isEmpty());
    }
}
