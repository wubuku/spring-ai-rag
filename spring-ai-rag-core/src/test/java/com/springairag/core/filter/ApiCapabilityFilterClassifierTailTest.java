package com.springairag.core.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * API 能力过滤器分类长尾（Batch 741，JaCoCo 驱动）：null 参守卫
 * （73）、非数据面路径透传（77）、PATCH/DELETE 写能力与未知方法
 * 缺省（88）、查询串剥离与尾斜杠归一（106）。
 */
class ApiCapabilityFilterClassifierTailTest {

    @Test
    void nullMethodOrUriReturnsNull() {
        assertNull(ApiCapabilityFilter.requiredCapability(
                null, "/api/v1/rag/documents"));
        assertNull(ApiCapabilityFilter.requiredCapability(
                "GET", null));
    }

    @Test
    void nonDataPlanePathReturnsNull() {
        assertNull(ApiCapabilityFilter.requiredCapability(
                "GET", "/public/health"));
        assertNull(ApiCapabilityFilter.requiredCapability(
                "GET", "/actuator/health/"));
    }

    @Test
    void unknownMethodReturnsNull() {
        assertNull(ApiCapabilityFilter.requiredCapability(
                "FOO", "/api/v1/rag/documents"));
    }

    @Test
    void patchAndOptionsMapToWriteAndRead() {
        assertEquals("RAG_WRITE",
                ApiCapabilityFilter.requiredCapability(
                        "PATCH", "/api/v1/rag/documents/doc-1"));
        assertEquals("RAG_READ",
                ApiCapabilityFilter.requiredCapability(
                        "OPTIONS", "/api/v1/rag/documents"));
    }

    @Test
    void queryStringAndTrailingSlashAreNormalized() {
        assertEquals("RAG_READ",
                ApiCapabilityFilter.requiredCapability(
                        "GET", "/api/v1/rag/documents?size=10&page=2/"));
        assertEquals("RAG_READ",
                ApiCapabilityFilter.requiredCapability(
                        "GET", "/v1/rag/documents///"));
    }
}
