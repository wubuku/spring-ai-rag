package com.springairag.core.retrieval;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端已校验的检索资格过滤。公开 API 只接收单个 payloadContains，
 * 列表用于同时保留调用者条件与 Agent tool 的额外收窄条件。
 */
public record RetrievalFilters(
        JsonbContainmentFilter metadataContains,
        List<JsonbContainmentFilter> payloadContainsAll) {

    public RetrievalFilters {
        payloadContainsAll = payloadContainsAll == null
                ? List.of()
                : List.copyOf(payloadContainsAll);
    }

    public static RetrievalFilters none() {
        return new RetrievalFilters(null, List.of());
    }

    public static RetrievalFilters ofPayload(JsonbContainmentFilter payload) {
        return payload == null
                ? none()
                : new RetrievalFilters(null, List.of(payload));
    }

    public RetrievalFilters withAdditionalPayload(JsonbContainmentFilter payload) {
        if (payload == null) {
            return this;
        }
        List<JsonbContainmentFilter> next = new ArrayList<>(payloadContainsAll);
        next.add(payload);
        return new RetrievalFilters(metadataContains, next);
    }

    public boolean isEmpty() {
        return metadataContains == null && payloadContainsAll.isEmpty();
    }
}
