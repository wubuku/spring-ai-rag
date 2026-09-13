package com.springairag.core.retrieval;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetrievalBranchStage toMap 与状态谓词（Batch 347）：字段映射、
 * 空白 errorCode 省略、非空白 errorCode 保留、三态谓词。
 */
class RetrievalBranchStageTest {

    @Test
    void toMapIncludesAllFieldsAndOmitsBlankErrorCode() {
        RetrievalBranchStage stage = new RetrievalBranchStage(
                "VECTOR", "bge-m3", "SUCCESS", 12L, 5, 4, " ");

        Map<String, Object> map = stage.toMap();

        assertEquals("VECTOR", map.get("branch"));
        assertEquals("bge-m3", map.get("provider"));
        assertEquals("SUCCESS", map.get("status"));
        assertEquals(12L, map.get("elapsedMs"));
        assertEquals(5, map.get("candidateCount"));
        assertEquals(4, map.get("resultCount"));
        // 空白 errorCode 不进入映射。
        assertFalse(map.containsKey("errorCode"));
        // 不可变快照。
        assertEquals(Map.copyOf(map), map);
    }

    @Test
    void nonBlankErrorCodeIsIncluded() {
        RetrievalBranchStage stage = new RetrievalBranchStage(
                "FULLTEXT", "pg", "ERROR", 30L, 0, 0, "TIMEOUT");

        Map<String, Object> map = stage.toMap();

        assertEquals("TIMEOUT", map.get("errorCode"));
        assertTrue(stage.failed());
        assertFalse(stage.succeeded());
        assertFalse(stage.timedOut());
    }

    @Test
    void statusPredicatesCoverThreeStates() {
        assertTrue(new RetrievalBranchStage(
                "VECTOR", "p", "SUCCESS", 1, 1, 1, null).succeeded());
        assertTrue(new RetrievalBranchStage(
                "VECTOR", "p", "TIMEOUT", 1, 1, 0, null).timedOut());
        assertTrue(new RetrievalBranchStage(
                "VECTOR", "p", "ERROR", 1, 1, 0, "boom").failed());
        // null errorCode 记录为 null（不写入 map）。
        assertNull(new RetrievalBranchStage(
                "VECTOR", "p", "SUCCESS", 1, 1, 1, null).errorCode());
    }

    @Test
    void constantsExposeBranchNames() {
        assertEquals("VECTOR", RetrievalBranchStage.VECTOR);
        assertEquals("FULLTEXT", RetrievalBranchStage.FULLTEXT);
        assertEquals("SUCCESS", RetrievalBranchStage.SUCCESS);
        assertEquals("TIMEOUT", RetrievalBranchStage.TIMEOUT);
        assertEquals("ERROR", RetrievalBranchStage.ERROR);
    }
}
