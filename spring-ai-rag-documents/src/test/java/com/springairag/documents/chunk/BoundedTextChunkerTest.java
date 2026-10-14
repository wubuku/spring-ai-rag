package com.springairag.documents.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BoundedTextChunkerTest {
    private final BoundedTextChunker chunker = new BoundedTextChunker(100, 10, 10, 100000, 20000);

    @Test
    void longUnpunctuatedInputIsBoundedAndCovered() {
        ChunkingResult result = chunker.split("x".repeat(1000));
        assertTrue(result.chunks().size() > 1);
        assertEquals(1000, result.diagnostics().coveredNonWhitespaceChars());
        assertTrue(result.diagnostics().valid());
        assertTrue(result.diagnostics().maxPayloadChars() <= 100);
        assertTrue(result.diagnostics().overlapChars() > 0);
    }

    @Test
    void invalidTableRemainsPlainText() {
        String original = "| Single | Row | Table |";
        assertEquals(original, chunker.split(original).chunks().getFirst().text());
    }

    @Test
    void siblingTitlesReplaceRatherThanAccumulate() {
        ChunkingResult result = chunker.split("# Alpha\nalpha body\n# Beta\nbeta body");
        assertEquals(List.of("Beta"), result.metadata().getLast().titlePath());
        assertFalse(result.chunks().getLast().text().contains("Alpha"));
    }

    @Test
    void tableDoesNotShiftFollowingOffsets() {
        String original = "intro\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\nTAIL_MARKER_0123";
        TextChunk tail = chunker.split(original).chunks().getLast();
        assertEquals("TAIL_MARKER_0123", original.substring(tail.startPos(), tail.endPos()));
    }

    @Test
    void nestedTitlesAndHeaderOnlyDocumentsRetainContent() {
        ChunkingResult result = chunker.split("# Root\n## Child\nhello\n## Sibling\nworld");
        assertEquals(List.of("Root", "Sibling"), result.metadata().getLast().titlePath());
        assertTrue(chunker.split("# title only").diagnostics().valid());
        assertTrue(chunker.split("hi").diagnostics().valid());
        assertTrue(chunker.split("\n\t").chunks().isEmpty());
        assertTrue(chunker.split(null).chunks().isEmpty());
    }

    @Test
    void longTablesRepeatHeaderWithinFinalBudget() {
        String header = "| A | B |\n| --- | --- |\n";
        ChunkingResult result = chunker.split(header + "| 1 | 2 |\n".repeat(30));
        assertTrue(result.chunks().size() > 1);
        assertTrue(result.chunks().stream().allMatch(chunk -> chunk.text().startsWith(header)));
        assertTrue(result.diagnostics().valid());
        assertTrue(result.diagnostics().maxPayloadChars() <= 100);
        assertTrue(result.diagnostics().generatedChars() > 0);
    }

    @Test
    void oneColumnTablesAndExtremeValidSizeRemainSafe() {
        ChunkingResult table = chunker.split("| Value |\n| --- |\n| abc |\n");
        assertEquals("TABLE", table.metadata().getFirst().kind());
        assertTrue(table.diagnostics().valid());
        assertTrue(new BoundedTextChunker(Integer.MAX_VALUE, 0, 0, 1000, 10)
                .split("# Title\nbody\n# Next\nnext body").diagnostics().valid());
    }

    @Test
    void fencesProtectFakeHeadingsAndTables() {
        ChunkingResult result = chunker.split("# Root\n```java\n# not a heading\n| invalid |\n```\nafter");
        assertEquals("CODE", result.metadata().get(1).kind());
        assertEquals(List.of("Root"), result.metadata().getLast().titlePath());
        assertTrue(result.diagnostics().valid());
    }

    @Test
    void protectedBlocksSplitOnLinesOrFailExplicitly() {
        assertTrue(chunker.split("```\n" + "short line\n".repeat(30) + "```\n").diagnostics().valid());
        assertEquals("CHUNK_ATOMIC_BLOCK_TOO_LARGE", assertThrows(ChunkingException.class,
                () -> chunker.split("```\n" + "x".repeat(101) + "\n```" )).getCode());
        assertEquals("FORMULA", chunker.split("$$\nx+y=2\n$$").metadata().getFirst().kind());
        assertTrue(chunker.split("~~~\n# fake\n").diagnostics().warnings()
                .contains("UNTERMINATED_PROTECTED_BLOCK"));
    }

    @Test
    void codeContinuationPreservesIndentationAndNewlines() {
        ChunkingResult result = chunker.split("```python\n" + "    nested_statement()\n".repeat(20) + "```\n");
        assertTrue(result.chunks().get(1).text().startsWith("    nested_statement()"));
        assertTrue(result.chunks().stream().allMatch(chunk -> chunk.text().endsWith("\n")));
        assertTrue(result.diagnostics().valid());
    }

    @Test
    void unicodeCrLfAndFixedSeedInputsPreserveCoverage() {
        assertTrue(chunker.split("# 中文\r\n" + "中文😀。".repeat(100)).diagnostics().valid());
        Random random = new Random(20261014);
        for (int iteration = 0; iteration < 100; iteration++) {
            StringBuilder original = new StringBuilder();
            for (int index = 0; index < 300; index++) {
                original.append(List.of("x", "中文", "😀", " ", "。", "\r\n").get(random.nextInt(6)));
            }
            assertTrue(chunker.split(original.toString()).diagnostics().valid());
        }
    }

    @Test
    void titleContextBudgetAndLimitsAreExplicit() {
        assertTrue(chunker.split("# " + "heading".repeat(30) + "\nbody")
                .diagnostics().warnings().contains("TITLE_CONTEXT_OMITTED"));
        assertEquals("CHUNK_INPUT_LIMIT", assertThrows(ChunkingException.class,
                () -> new BoundedTextChunker(100, 10, 10, 10, 20).split("x".repeat(11))).getCode());
        assertEquals("CHUNK_COUNT_LIMIT", assertThrows(ChunkingException.class,
                () -> new BoundedTextChunker(100, 10, 10, 1000, 1).split("x".repeat(500))).getCode());
        assertThrows(IllegalArgumentException.class, () -> new BoundedTextChunker(100, 10, 100, 1000, 10));
    }
}
