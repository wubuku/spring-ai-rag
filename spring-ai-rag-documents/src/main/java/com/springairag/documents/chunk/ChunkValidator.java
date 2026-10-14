package com.springairag.documents.chunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 按有序原文区间校验守恒、定位和预算，复杂度为 O(原文长度 + 块数)。 */
public final class ChunkValidator {
    private ChunkValidator() {
    }

    public static ChunkDiagnostics analyze(
            String original, List<TextChunk> chunks, int payloadLimit, int chunkLimit,
            List<String> warnings) {
        LinkedHashSet<String> violations = new LinkedHashSet<>();
        int cursor = 0;
        int covered = 0;
        int minPayload = Integer.MAX_VALUE;
        int maxPayload = 0;
        long generated = 0;
        long overlap = 0;
        int previousStart = -1;
        if (!original.isBlank() && chunks.isEmpty()) {
            violations.add("EMPTY_OUTPUT");
        }
        if (chunks.size() > chunkLimit) {
            violations.add("CHUNK_COUNT_LIMIT");
        }
        for (TextChunk chunk : chunks) {
            if (chunk == null || chunk.text() == null || chunk.text().isBlank()) {
                violations.add("EMPTY_CHUNK");
                continue;
            }
            minPayload = Math.min(minPayload, chunk.text().length());
            maxPayload = Math.max(maxPayload, chunk.text().length());
            if (chunk.text().length() > payloadLimit) {
                violations.add("PAYLOAD_LIMIT");
            }
            int start = chunk.startPos();
            int end = chunk.endPos();
            if (start < 0 || end <= start || end > original.length()) {
                violations.add("INVALID_RANGE");
                continue;
            }
            if (start < previousStart) {
                violations.add("UNSORTED_RANGES");
            }
            previousStart = start;
            if (splitsSurrogate(original, start) || splitsSurrogate(original, end)) {
                violations.add("SPLIT_SURROGATE");
            }
            if (!chunk.text().endsWith(original.substring(start, end))) {
                violations.add("SOURCE_MISMATCH");
            }
            generated += Math.max(0, chunk.text().length() - (end - start));
            if (start > cursor && countNonWhitespace(original, cursor, start) > 0) {
                violations.add("UNCOVERED_CONTENT");
            }
            overlap += Math.max(0, Math.min(cursor, end) - start);
            covered += countNonWhitespace(original, Math.max(start, cursor), Math.max(end, cursor));
            cursor = Math.max(cursor, end);
        }
        if (countNonWhitespace(original, cursor, original.length()) > 0) {
            violations.add("UNCOVERED_CONTENT");
        }
        return new ChunkDiagnostics(violations.isEmpty(), original.length(), covered,
                minPayload == Integer.MAX_VALUE ? 0 : minPayload, maxPayload,
                generated, overlap, warnings, new ArrayList<>(violations));
    }

    public static boolean splitsSurrogate(String text, int position) {
        return position > 0 && position < text.length()
                && Character.isHighSurrogate(text.charAt(position - 1))
                && Character.isLowSurrogate(text.charAt(position));
    }

    private static int countNonWhitespace(String text, int start, int end) {
        int count = 0;
        for (int position = start; position < end; position++) {
            if (!Character.isWhitespace(text.charAt(position))) {
                count++;
            }
        }
        return count;
    }
}
