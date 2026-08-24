package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在保持 provider 排名的前提下，优先增加最终证据的文档覆盖。
 */
final class RerankResultSelector {

    private RerankResultSelector() {
    }

    static List<RetrievalResult> select(
            List<RetrievalResult> ranked,
            int finalLimit,
            int preferredMaxChunksPerDocument) {
        if (ranked == null || ranked.isEmpty() || finalLimit <= 0) {
            return ranked;
        }
        int target = Math.min(finalLimit, ranked.size());
        if (target == ranked.size()) {
            return ranked;
        }
        if (preferredMaxChunksPerDocument <= 0
                || preferredMaxChunksPerDocument >= finalLimit) {
            return new ArrayList<>(ranked.subList(0, target));
        }

        boolean[] selected = new boolean[ranked.size()];
        Map<String, Integer> countsByDocument = new HashMap<>();
        int selectedCount = 0;

        for (int index = 0; index < ranked.size() && selectedCount < target; index++) {
            RetrievalResult result = ranked.get(index);
            String documentId = result != null ? result.getDocumentId() : null;
            if (documentId == null || documentId.isBlank()) {
                selected[index] = true;
                selectedCount++;
                continue;
            }
            int count = countsByDocument.getOrDefault(documentId, 0);
            if (count < preferredMaxChunksPerDocument) {
                selected[index] = true;
                selectedCount++;
                countsByDocument.put(documentId, count + 1);
            }
        }

        for (int index = 0; index < ranked.size() && selectedCount < target; index++) {
            if (!selected[index]) {
                selected[index] = true;
                selectedCount++;
            }
        }

        List<RetrievalResult> output = new ArrayList<>(target);
        for (int index = 0; index < ranked.size() && output.size() < target; index++) {
            if (selected[index]) {
                output.add(ranked.get(index));
            }
        }
        return output;
    }
}
