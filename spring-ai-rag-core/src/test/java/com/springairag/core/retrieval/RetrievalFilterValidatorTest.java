package com.springairag.core.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordSearchRequest;
import com.springairag.api.dto.RetrievalFilterRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalFilterValidatorTest {

    private final RetrievalFilterValidator validator = new RetrievalFilterValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void canonicalJsonSortsObjectKeysAndPreservesArrays() throws Exception {
        String canonical = RetrievalFilterValidator.toCanonicalJson(
                mapper.readTree("{\"z\":1,\"a\":[{\"b\":2,\"a\":1}]}"));
        assertEquals("{\"a\":[{\"a\":1,\"b\":2}],\"z\":1}", canonical);
    }

    @Test
    void rejectsEmptyObjectSqlAndOversizeOrDeepFilters() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateObject(mapper.readTree("{}"), "metadataContains"));
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateObject(mapper.readTree("[]"), "metadataContains"));
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateObject(
                        mapper.readTree("{\"value\":\"" + "x".repeat(17_000) + "\"}"),
                        "metadataContains"));
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateObject(
                        mapper.readTree(
                                "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":{\"h\":{\"i\":1}}}}}}}}}"),
                        "metadataContains"));
    }

    @Test
    void jsonRecordFiltersConflictWithTopLevelFields() throws Exception {
        JsonRecordSearchRequest request = new JsonRecordSearchRequest();
        request.setFilters(new RetrievalFilterRequest(
                mapper.readTree("{\"tenant\":\"acme\"}"), null));
        request.setMetadataContains(mapper.readTree("{\"tenant\":\"other\"}"));
        assertThrows(IllegalArgumentException.class, () ->
                validator.fromJsonRecordRequest(request));
    }

    @Test
    void toolPayloadIsAdditionalConjunctNotMerged() throws Exception {
        RetrievalFilters caller = validator.validate(
                mapper.readTree("{\"status\":\"active\"}"),
                mapper.readTree("{\"tenant\":\"acme\"}"));
        RetrievalFilters combined = validator.narrowWithPayload(
                caller, mapper.readTree("{\"sku\":\"S-1\"}"));
        assertEquals("{\"status\":\"active\"}",
                combined.metadataContains().canonicalJson());
        assertEquals(2, combined.payloadContainsAll().size());
        assertEquals("{\"tenant\":\"acme\"}",
                combined.payloadContainsAll().getFirst().canonicalJson());
        assertEquals("{\"sku\":\"S-1\"}",
                combined.payloadContainsAll().get(1).canonicalJson());
        assertTrue(combined.payloadContainsAll().getFirst()
                != combined.payloadContainsAll().get(1));
    }
}
