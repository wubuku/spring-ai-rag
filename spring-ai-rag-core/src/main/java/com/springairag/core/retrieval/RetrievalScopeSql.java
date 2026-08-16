package com.springairag.core.retrieval;

import org.springframework.jdbc.support.SqlArrayValue;

import java.util.ArrayList;
import java.util.List;

/**
 * 将授权后的检索范围转换为固定形状的 PostgreSQL predicate。
 */
public final class RetrievalScopeSql {

    private RetrievalScopeSql() {
    }

    public static Fragment build(RetrievalScope requestedScope) {
        RetrievalScope scope = requestedScope != null
                ? requestedScope
                : RetrievalScope.unscoped();
        if (scope.matchNone()) {
            return new Fragment("AND 1 = 0 ", List.of());
        }

        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        switch (scope.collectionFilter()) {
            case NONE -> {
            }
            case ANY_ASSIGNED -> sql.append("AND d.collection_id IS NOT NULL ");
            case SELECTED -> {
                sql.append("AND d.collection_id = ANY (?) ");
                args.add(bigintArray(scope.collectionIds()));
            }
        }
        if (!scope.documentIds().isEmpty()) {
            sql.append("AND e.document_id = ANY (?) ");
            args.add(bigintArray(scope.documentIds()));
        }
        if (scope.documentType() != null) {
            sql.append("AND d.document_type = ? ");
            args.add(scope.documentType());
        }
        return new Fragment(sql.toString(), args);
    }

    private static SqlArrayValue bigintArray(List<Long> values) {
        return new SqlArrayValue("bigint", values.toArray());
    }

    public record Fragment(String sql, List<Object> args) {
        public Fragment {
            sql = sql == null ? "" : sql;
            args = args == null ? List.of() : List.copyOf(args);
        }
    }
}
