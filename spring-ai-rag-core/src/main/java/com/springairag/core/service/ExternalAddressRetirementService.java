package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.exception.RagException;
import com.springairag.core.security.ApiKeyCollectionAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 集中执行外部文档旧 placement address 的永久阻断规则。 */
@Service
public class ExternalAddressRetirementService {

    private final JdbcTemplate jdbcTemplate;

    public ExternalAddressRetirementService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void requireNotRetired(
            long collectionId,
            String sourceNamespace,
            String externalId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT marker.target_collection_id, collection.collection_key
                FROM rag_document_relocated_addresses marker
                JOIN rag_collection collection ON collection.id = marker.target_collection_id
                WHERE marker.source_collection_id = ?
                  AND marker.source_namespace = ?
                  AND marker.external_id = ?
                  AND marker.active = TRUE
                """,
                collectionId, sourceNamespace, externalId);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> marker = rows.getFirst();
        Long targetId = ((Number) marker.get("target_collection_id")).longValue();
        ApiAccessPolicy caller = ApiKeyCollectionAccess.currentPolicy();
        String suffix = "";
        try {
            ApiKeyCollectionAccess.requireCollectionId(targetId, caller);
            suffix = "; current targetCollectionKey=" + marker.get("collection_key");
        } catch (SecurityException ignored) {
            // Restricted callers must not learn the current target placement.
        }
        throw new RagException(ErrorCode.EXTERNAL_IDENTITY_RELOCATED,
                "The external identity was relocated and this address is retired" + suffix);
    }
}
