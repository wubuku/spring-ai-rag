package com.springairag.documents.chunk;

/**
 * Text chunk result.
 *
 * @param text      chunk content (already trimmed)
 * @param startPos  start position in original text (character index)
 * @param endPos    end position in original text (character index, exclusive)
 */
public record TextChunk(String text, int startPos, int endPos) {
}
