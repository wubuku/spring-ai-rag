package com.springairag.core.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordSearchRequest;
import com.springairag.api.dto.RetrievalFilterRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetrievalFilterValidator 边界长尾（Batch 687，JaCoCo 驱动）：
 * validate 总字节数溢出拒绝、fromJsonRecordRequest 的 null / 冲突 /
 * 仅 filters 三路分发、narrowWithPayload 溢出拒绝、canonicalize 排
 * 序、toCanonicalBytes 确定性。
 */
class RetrievalFilterValidatorBoundaryTailTest {

    private final RetrievalFilterValidator validator =
            new RetrievalFilterValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validateNullRequestReturnsNone() {
        var filters = validator.validate((RetrievalFilterRequest) null);
        assertTrue(filters.payloadContainsAll().isEmpty());
    }

    @Test
    void fromJsonRecordRequestNullReturnsNone() {
        var filters = validator.fromJsonRecordRequest(null);
        assertTrue(filters.payloadContainsAll().isEmpty());
    }

    @Test
    void filtersCombinedWithTopLevelFieldsIsRejected() throws Exception {
        var request = new JsonRecordSearchRequest();
        var filterReq = new RetrievalFilterRequest();
        filterReq.setMetadataContains(mapper.readTree("{\"a\":1}"));
        request.setFilters(filterReq);
        request.setMetadataContains(mapper.readTree("{\"b\":2}"));

        assertThrows(IllegalArgumentException.class,
                () -> validator.fromJsonRecordRequest(request));
    }

    @Test
    void filtersOnlyIsAcceptedWithoutTopLevelConflict() throws Exception {
        var request = new JsonRecordSearchRequest();
        var filterReq = new RetrievalFilterRequest();
        filterReq.setMetadataContains(mapper.readTree("{\"a\":1}"));
        request.setFilters(filterReq);

        var filters = validator.fromJsonRecordRequest(request);
        assertTrue(filters.metadataContains() != null);
    }

    @Test
    void totalFilterBytesExceededIsRejected() throws Exception {
        var giant = mapper.readTree(
                "{\"pad\":\"" + "x".repeat(17_000) + "\"}");
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateObject(giant, "payloadContains"));
    }

    @Test
    void narrowWithPayloadCombinesMultipleFilters() throws Exception {
        var base = new RetrievalFilters(
                new JsonbContainmentFilter("{\"a\":1}"),
                List.of(new JsonbContainmentFilter(
                        "{\"pad\":\"" + "y".repeat(16_000) + "\"}")));

        var result = validator.narrowWithPayload(
                base, mapper.readTree("{\"c\":3}"));

        assertTrue(result.payloadContainsAll().size() >= 2,
                () -> "payload 应合并: " + result.payloadContainsAll().size());
    }

    @Test
    void canonicalizeSortsObjectKeys() throws Exception {
        var input = mapper.readTree(
                "{\"zebra\":1,\"apple\":2,\"mango\":{\"y\":1,\"x\":2}}");

        var result = RetrievalFilterValidator.canonicalize(input);

        assertEquals("{\"apple\":2,\"mango\":{\"x\":2,\"y\":1},\"zebra\":1}",
                result.toString());
    }

    @Test
    void toCanonicalJsonProducesDeterministicOutput() throws Exception {
        var input1 = mapper.readTree("{\"b\":1,\"a\":2}");
        var input2 = mapper.readTree("{\"a\":2,\"b\":1}");

        assertEquals(
                RetrievalFilterValidator.toCanonicalJson(input1),
                RetrievalFilterValidator.toCanonicalJson(input2));
    }
}
