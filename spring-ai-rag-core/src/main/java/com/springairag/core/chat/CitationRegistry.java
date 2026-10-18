package com.springairag.core.chat;

import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.RetrievalResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attempt-local registry for model-visible source handles.
 *
 * <p>Handles are intentionally short-lived. They are aliases for an
 * authorized source in one logical retrieval attempt, not resource
 * identifiers that can be used to address a document later.</p>
 */
public final class CitationRegistry {

    public static final int VERSION = 1;
    public static final String HANDLE_PREFIX = "c";

    private final int maxSources;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public CitationRegistry(int maxSources) {
        this.maxSources = Math.max(1, maxSources);
    }

    public synchronized Registration register(RetrievalResult result) {
        String sourceKey = sourceKey(result);
        if (sourceKey == null) {
            return null;
        }
        Entry existing = entries.get(sourceKey);
        if (existing != null) {
            var previous = existing.result().getEvidenceSnapshot();
            var current = result.getEvidenceSnapshot();
            if (previous == null ? current != null : !previous.compatibleEvidence(current)) {
                throw EvidenceSnapshotGuard.conflict();
            }
            if (result.getScore() > existing.result().getScore()) {
                entries.put(sourceKey, existing.withResult(result));
            }
            return existing.registration();
        }
        if (entries.size() >= maxSources) {
            return null;
        }
        int ordinal = entries.size() + 1;
        Entry entry = new Entry(
                sourceKey,
                HANDLE_PREFIX + ordinal,
                "S" + ordinal,
                result);
        entries.put(sourceKey, entry);
        return entry.registration();
    }

    public synchronized Registration registration(RetrievalResult result) {
        String sourceKey = sourceKey(result);
        Entry entry = sourceKey != null ? entries.get(sourceKey) : null;
        return entry != null ? entry.registration() : null;
    }

    public synchronized String sourceHandle(RetrievalResult result) {
        Registration registration = registration(result);
        return registration != null ? registration.sourceHandle() : null;
    }

    public synchronized String sourceHandle(String sourceKey) {
        Entry entry = sourceKey != null ? entries.get(sourceKey) : null;
        return entry != null ? entry.sourceHandle() : null;
    }

    public synchronized String citationId(RetrievalResult result) {
        Registration registration = registration(result);
        return registration != null ? registration.citationId() : null;
    }

    public synchronized String citationId(String sourceKey) {
        Entry entry = sourceKey != null ? entries.get(sourceKey) : null;
        return entry != null ? entry.citationId() : null;
    }

    /**
     * Projects only the handles present in the final response source list.
     * This prevents intermediate retrieval candidates from leaking into the
     * durable response metadata.
     */
    public synchronized Map<String, Object> metadataFor(
            Collection<ChatSource> sources) {
        List<String> handles = new ArrayList<>();
        if (sources != null) {
            for (ChatSource source : sources) {
                if (source == null || source.getSourceHandle() == null
                        || source.getSourceHandle().isBlank()) {
                    continue;
                }
                if (!handles.contains(source.getSourceHandle())) {
                    handles.add(source.getSourceHandle());
                }
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("version", VERSION);
        metadata.put("handlePrefix", HANDLE_PREFIX);
        metadata.put("sourceCount", handles.size());
        metadata.put("handles", List.copyOf(handles));
        return Map.copyOf(metadata);
    }

    public static String sourceKey(RetrievalResult result) {
        if (result == null || result.getDocumentId() == null
                || result.getDocumentId().isBlank()
                || !result.hasChunkIndex()) {
            return null;
        }
        return result.getDocumentId().trim() + ":" + result.getChunkIndex();
    }

    public record Registration(
            String sourceKey,
            String sourceHandle,
            String citationId) {
    }

    private record Entry(
            String sourceKey,
            String sourceHandle,
            String citationId,
            RetrievalResult result) {

        private Registration registration() {
            return new Registration(sourceKey, sourceHandle, citationId);
        }

        private Entry withResult(RetrievalResult replacement) {
            return new Entry(sourceKey, sourceHandle, citationId, replacement);
        }
    }
}
