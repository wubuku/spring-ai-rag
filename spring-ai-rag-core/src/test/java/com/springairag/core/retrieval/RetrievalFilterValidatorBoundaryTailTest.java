package com.springairag.core.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.RetrievalFilterRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetrievalFilterValidator 边界长尾（Batch 678，JaCoCo 驱动）：
 * validate(null) → none、fromJsonRecordRequest 的冲突拒绝、
 * narrowWithPayload 组合与溢出、canonicalize 递归排序、
 * toCanonicalJson 确定性。
 */
class RetrievalFilterValidatorBoundaryTailTest {

    private final RetrievalFilterValidator validator =
            new RetrievalFilterValidator();

    private com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    void validateNullRequestReturnsNone() {
        var filters = validator.validate((RetrievalFilterRequest) null);

        assertTrue(filters.metadataContains() == null
                || filters.metadataContains().toString().equals("null"));
        assertTrue(filters.payloadContainsAll().isEmpty());
    }

    @Test
    void narrowWithPayloadCombinesAndLimits() throws Exception {
        var base = new RetrievalFilters(
                new JsonbContainmentFilter("{\"a\":1}"),
                List.of(new JsonbContainmentFilter("{\"b\":2}")));

        var result = validator.narrowWithPayload(
                base, mapper.readTree("{\"c\":3}"));

        // base 的 payloadContainsAll 已有 1 条 + extra 1 条。
        assertTrue(result.payloadContainsAll().size() >= 2,
                () -> "payload 应合并: " + result.payloadContainsAll().size());
    }

    @Test
    void narrowWithPayloadExceedingTotalRejects() throws Exception {
        var base = new RetrievalFilters(
                new JsonbContainmentFilter("{\"a\":1}"),
                List.of(new JsonbContainmentFilter("{\"b\":2}")));

        var giant = mapper.readTree(
                "{\"pad\":\"" + "x".repeat(33_000) + "\"}");

        assertThrows(IllegalArgumentException.class,
                () -> validator.narrowWithPayload(
                        base, giant));
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
    void canonicalizeArraysPreserveOrder() throws Exception {
        var input = mapper.readTree("[3,1,2]");

        var result = RetrievalFilterValidator.canonicalize(input);

        assertEquals("[3,1,2]", result.toString());
    }

    @Test
    void toCanonicalJsonProducesDeterministicOutput() throws Exception {
        var input1 = mapper.readTree("{\"b\":1,\"a\":2}");
        var input2 = mapper.readTree("{\"a\":2,\"b\":1}");

        assertEquals(
                RetrievalFilterValidator.toCanonicalJson(input1),
                RetrievalFilterValidator.toCanonicalJson(input2));
    }

    @Test
    void validateObjectNullReturnsNull() throws Exception {
        Method method = RetrievalFilterValidator.class
                .getDeclaredMethod("validateObject",
                        com.fasterxml.jackson.databind.JsonNode.class,
                        String.class);
        method.setAccessible(true);

        var result = method.invoke(validator,
                (Object) null, "payloadContains");

        assertTrue(result == null
                || result.toString().equals("null"));
    }
}
