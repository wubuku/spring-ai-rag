package com.springairag.documents.chunk;

import java.util.List;

/** 原文覆盖与最终 payload 的确定性诊断；不保存原文。 */
public record ChunkDiagnostics(
        boolean valid, int originalChars, int coveredNonWhitespaceChars,
        int minPayloadChars, int maxPayloadChars, long generatedChars,
        long overlapChars, List<String> warnings, List<String> violations) {
    public ChunkDiagnostics {
        warnings = List.copyOf(warnings);
        violations = List.copyOf(violations);
    }
}
