package com.springairag.api.dto;

import java.util.List;

/** 当前文档快照的只读分块预览，不代表索引已入库状态。 */
public record DocumentChunkPreviewResponse(
        Long documentId, Long documentRevision, String documentKind, String profile,
        String chunkerVersion, int totalChunks, int offset, int limit, boolean hasMore,
        Diagnostics diagnostics, List<Chunk> chunks) {
    public DocumentChunkPreviewResponse {
        chunks = List.copyOf(chunks);
    }

    public record Chunk(int index, String text, int startPos, int endPos,
                        String kind, List<String> titlePath, int generatedChars) {
        public Chunk {
            titlePath = List.copyOf(titlePath);
        }
    }

    public record Diagnostics(
            boolean valid, int originalChars, int coveredNonWhitespaceChars,
            int minPayloadChars, int maxPayloadChars, long generatedChars,
            long overlapChars, List<String> warnings, List<String> violations) {
        public Diagnostics {
            warnings = List.copyOf(warnings);
            violations = List.copyOf(violations);
        }
    }
}
