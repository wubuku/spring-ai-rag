package com.springairag.documents.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkValidatorTest {
    @Test
    void rejectsLostSourceMisalignedPositionsAndPayloadOverflow() {
        assertTrue(report("abc", List.of()).violations().contains("EMPTY_OUTPUT"));
        assertTrue(report("abc", List.of(new TextChunk("ab", 0, 2))).violations().contains("UNCOVERED_CONTENT"));
        assertTrue(report("abc", List.of(new TextChunk("abc", 0, 4))).violations().contains("INVALID_RANGE"));
        assertTrue(report("abc", List.of(new TextChunk("wrong", 0, 3))).violations().contains("SOURCE_MISMATCH"));
        assertTrue(report("abc", List.of(new TextChunk("x".repeat(101) + "abc", 0, 3)))
                .violations().contains("PAYLOAD_LIMIT"));
    }

    @Test
    void permitsShortDocumentsAndCountsGeneratedPrefixSeparately() {
        ChunkDiagnostics report = report("abc", List.of(new TextChunk("title\nabc", 0, 3)));
        assertTrue(report.valid());
        assertEquals(3, report.coveredNonWhitespaceChars());
        assertEquals(6, report.generatedChars());
    }

    @Test
    void checksSortingEmptyChunksAndUnicodeBoundaries() {
        assertTrue(report("abc", List.of(new TextChunk("bc", 1, 3), new TextChunk("ab", 0, 2)))
                .violations().contains("UNSORTED_RANGES"));
        assertTrue(report("abc", List.of(new TextChunk("", 0, 3))).violations().contains("EMPTY_CHUNK"));
        assertTrue(report("😀", List.of(new TextChunk("\ud83d", 0, 1), new TextChunk("\ude00", 1, 2)))
                .violations().contains("SPLIT_SURROGATE"));
    }

    private ChunkDiagnostics report(String original, List<TextChunk> chunks) {
        return ChunkValidator.analyze(original, chunks, 100, 100, List.of());
    }
}
