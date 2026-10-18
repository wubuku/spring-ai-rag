package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.EvidenceSnapshot;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.openai.OpenAiRagSource;
import com.springairag.core.exception.RagException;
import com.springairag.core.rag.RetrievalDocumentMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceSnapshotTest {
    private EvidenceSnapshot snapshot(long revision) {
        return new EvidenceSnapshot(revision, "content-hash", 2L, "VECTOR", 1L, 3, "chunk-v1", null);
    }

    @Test
    void roundTripsTrustedSnapshotAndHashesCompleteFinalEvidenceNotPreview() throws Exception {
        var mapper = new RetrievalDocumentMapper();
        var result = new RetrievalResult();
        result.setDocumentId("42");
        result.setChunkIndex(0);
        result.setChunkText("证据".repeat(2000));
        result.setEvidenceSnapshot(snapshot(5).withText(result.getChunkText()));
        result.setMetadata(Map.of("evidenceSnapshot", snapshot(999), "secret", "must-not-leak"));
        var document = mapper.toDocument(result);
        assertEquals(result.getEvidenceSnapshot(), mapper.toRetrievalResult(document).getEvidenceSnapshot());
        var source = mapper.toChatSource(document, "S1");
        assertEquals(2000, source.getChunkText().length());
        assertEquals(result.getEvidenceSnapshot(), source.getEvidenceSnapshot());
        assertNotEquals(snapshot(5).withText(source.getChunkText()).evidenceDigest(), source.getEvidenceSnapshot().evidenceDigest());
        var json = new ObjectMapper();
        assertEquals(source, json.readValue(json.writeValueAsString(source), ChatSource.class));
        var generic = json.convertValue(source.getEvidenceSnapshot(), Map.class);
        assertEquals(source.getEvidenceSnapshot(), EvidenceSnapshot.from(generic));
        String projection = json.writeValueAsString(OpenAiRagSource.from(source));
        assertTrue(projection.contains("evidenceDigest"));
        assertFalse(projection.contains("chunkText"));
        assertFalse(projection.contains("secret"));
        result.setEvidenceSnapshot(null);
        assertFalse(mapper.toDocument(result).getMetadata().containsKey("evidenceSnapshot"));
        assertNull(json.readValue("{\"documentId\":\"42\"}", ChatSource.class).getEvidenceSnapshot());
    }

    @Test
    void registryRejectsDifferentDocumentIdentityEvenAtLowerScoreButAllowsSameDocumentAcrossIndexes() {
        var registry = new CitationRegistry(10);
        var first = new RetrievalResult();
        first.setDocumentId("42");
        first.setChunkIndex(0);
        first.setEvidenceSnapshot(snapshot(5));
        registry.register(first);
        var other = new RetrievalResult();
        other.setDocumentId("42");
        other.setChunkIndex(0);
        other.setEvidenceSnapshot(new EvidenceSnapshot(5, "content-hash", 2L, "KEYWORD", null, 8, "chunk-v1", null));
        assertNotNull(registry.register(other));
        other.setEvidenceSnapshot(new EvidenceSnapshot(5, "content-hash", 2L, "VECTOR", 1L, 4, "chunk-v1", null));
        assertThrows(RagException.class, () -> registry.register(other));
        other.setEvidenceSnapshot(snapshot(6));
        assertEquals("EVIDENCE_SNAPSHOT_CONFLICT", assertThrows(RagException.class,
                () -> registry.register(other)).getErrorCode());
    }
}
