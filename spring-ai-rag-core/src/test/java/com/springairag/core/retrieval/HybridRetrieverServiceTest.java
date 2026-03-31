package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HybridRetrieverService 逻辑测试
 */
class HybridRetrieverServiceTest {

    @Test
    void retrievalResult_fields() {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId("doc-1");
        r.setChunkText("测试内容");
        r.setScore(0.85);
        r.setVectorScore(0.90);
        r.setFulltextScore(0.80);
        r.setChunkIndex(2);

        assertEquals("doc-1", r.getDocumentId());
        assertEquals("测试内容", r.getChunkText());
        assertEquals(0.85, r.getScore(), 0.001);
        assertEquals(0.90, r.getVectorScore(), 0.001);
        assertEquals(0.80, r.getFulltextScore(), 0.001);
        assertEquals(2, r.getChunkIndex());
    }

    @Test
    void retrievalResult_scoreRange() {
        RetrievalResult r = new RetrievalResult();
        r.setScore(1.5);  // 超出范围的分数
        assertTrue(r.getScore() > 1.0, "分数可以超出 1.0（取决于融合算法）");
    }
}
