package com.springairag.core.rag;

import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.config.RagProperties;
import com.springairag.core.rag.RetrievalContextRepository.Chunk;
import com.springairag.core.rag.RetrievalContextRepository.Seed;
import com.springairag.documents.chunk.ChunkValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

@Component
public class RetrievalContextAssemblyPostProcessor implements DocumentPostProcessor {
    static final String ANCHORS = "__ragContextAnchors";
    private static final Logger log = LoggerFactory.getLogger(RetrievalContextAssemblyPostProcessor.class);
    private final RetrievalContextRepository repository;
    private final RagProperties properties;

    public RetrievalContextAssemblyPostProcessor(RetrievalContextRepository repository, RagProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        var limits = properties.getChat().getContextAssembly();
        if (!limits.isEnabled() || documents == null || documents.isEmpty()) {
            return documents;
        }
        Object value = query != null && query.context() != null
                ? query.context().get(ProjectDocumentRetriever.CONTEXT_KEY)
                : null;
        if (!(value instanceof AuthorizedRetrievalContext context)) {
            throw new IllegalStateException("Missing server-owned authorized retrieval context");
        }
        long started = System.nanoTime();
        List<Seed> seeds = new ArrayList<>();
        for (int ordinal = 0; ordinal < documents.size() && seeds.size() < limits.getMaxSeeds(); ordinal++) {
            Document document = documents.get(ordinal);
            Seed seed = seed(ordinal, document, context);
            if (seed != null && seed.text().length() <= limits.getMaxWindowCharacters()) {
                seeds.add(seed);
            }
        }
        Map<Integer, List<Chunk>> rows = new LinkedHashMap<>();
        String degradedCode = "NONE";
        if (!seeds.isEmpty()) {
            try {
                for (Chunk chunk : repository.load(seeds, context, limits.getNeighborRadius(), limits.getMaxWindowCharacters())) {
                    rows.computeIfAbsent(chunk.ordinal(), ignored -> new ArrayList<>()).add(chunk);
                }
            } catch (RuntimeException failure) {
                degradedCode = "CONTEXT_LOOKUP_UNAVAILABLE";
                log.warn("证据组装降级 code=CONTEXT_LOOKUP_UNAVAILABLE");
            }
        }
        List<Document> output = new ArrayList<>();
        Window pending = null;
        int windows = 0;
        int merged = 0;
        int added = 0;
        int overlap = 0;
        for (int ordinal = 0; ordinal < documents.size(); ordinal++) {
            Window window = window(ordinal, documents.get(ordinal), rows.get(ordinal), limits.getMaxWindowCharacters());
            if (window != null) {
                windows++;
                added += Math.max(0, window.chunks().size() - 1);
            }
            Window combined = pending != null && window != null && pending.lastOrdinal() + 1 == ordinal
                    ? combine(pending, window, limits.getMaxWindowCharacters()) : null;
            if (combined != null) {
                pending = combined;
                merged++;
                continue;
            }
            if (pending != null) {
                output.add(document(pending));
                overlap += pending.removed();
                pending = null;
            }
            if (window != null) {
                pending = window;
            } else {
                output.add(documents.get(ordinal));
            }
        }
        if (pending != null) {
            output.add(document(pending));
            overlap += pending.removed();
        }
        context.trace().recordContextAssembly(Map.ofEntries(
                Map.entry("enabled", true), Map.entry("inputDocuments", documents.size()),
                Map.entry("eligibleSeeds", seeds.size()), Map.entry("expandedWindows", windows),
                Map.entry("neighborChunksAdded", added), Map.entry("mergedGroups", merged),
                Map.entry("overlapCharactersRemoved", overlap), Map.entry("outputDocuments", output.size()),
                Map.entry("documentCoverage", output.stream().map(doc -> doc.getMetadata().get("documentId")).filter(Objects::nonNull).distinct().count()),
                Map.entry("databaseQueries", seeds.isEmpty() ? 0 : 1), Map.entry("degradedCode", degradedCode),
                Map.entry("elapsedMs", (System.nanoTime() - started) / 1_000_000L)));
        return List.copyOf(output);
    }

    private Seed seed(int ordinal, Document document, AuthorizedRetrievalContext context) {
        Map<String, Object> metadata = document.getMetadata();
        if ("STATIC_KNOWLEDGE".equals(metadata.get("sourceType"))
                || "json-record".equalsIgnoreCase(String.valueOf(metadata.get("documentType")))) {
            return null;
        }
        try {
            long documentId = Long.parseLong(String.valueOf(metadata.get("documentId")));
            int chunkIndex = Integer.parseInt(String.valueOf(metadata.get("chunkIndex")));
            if (documentId <= 0 || chunkIndex < 0 || document.getText() == null || document.getText().isBlank()
                    || context.trace().sourceHandle(documentId + ":" + chunkIndex) == null) {
                return null;
            }
            var snapshot = com.springairag.api.dto.EvidenceSnapshot.from(metadata.get("evidenceSnapshot"));
            return new Seed(ordinal, documentId, chunkIndex, document.getText(),
                    snapshot != null ? snapshot.documentRevision() : null);
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private Window window(int ordinal, Document anchor, List<Chunk> chunks, int limit) {
        Object rawIndex = anchor.getMetadata().get("chunkIndex");
        if (chunks == null || chunks.size() <= 1 || !(rawIndex instanceof Number number)
                || chunks.stream().noneMatch(chunk -> chunk.index() == number.intValue()
                        && chunk.text().equals(anchor.getText()))) {
            return null;
        }
        return render(ordinal, ordinal, List.of(anchor), chunks, limit);
    }

    private Window combine(Window first, Window second, int limit) {
        var firstSnapshot = com.springairag.api.dto.EvidenceSnapshot.from(
                first.anchors().getFirst().getMetadata().get("evidenceSnapshot"));
        var secondSnapshot = com.springairag.api.dto.EvidenceSnapshot.from(
                second.anchors().getFirst().getMetadata().get("evidenceSnapshot"));
        if (firstSnapshot == null ? secondSnapshot != null : !firstSnapshot.sameDocument(secondSnapshot)) {
            return null;
        }
        Chunk left = first.chunks().getFirst();
        Chunk right = second.chunks().getFirst();
        if (!sameIdentity(left, right)
                || (long) first.chunks().getLast().index() + 1 < right.index()
                || (long) second.chunks().getLast().index() + 1 < left.index()) {
            return null;
        }
        List<Chunk> chunks = new ArrayList<>(first.chunks());
        chunks.addAll(second.chunks());
        List<Document> anchors = new ArrayList<>(first.anchors());
        anchors.addAll(second.anchors());
        return render(first.ordinal(), second.lastOrdinal(), List.copyOf(anchors), chunks, limit);
    }

    private Window render(int ordinal, int lastOrdinal, List<Document> anchors, List<Chunk> input, int limit) {
        TreeMap<Integer, Chunk> unique = new TreeMap<>();
        for (Chunk chunk : input) {
            Chunk previous = unique.putIfAbsent(chunk.index(), chunk);
            if (previous != null && (!sameIdentity(previous, chunk) || !previous.text().equals(chunk.text())
                    || previous.start() != chunk.start() || previous.end() != chunk.end())) {
                return null;
            }
        }
        StringBuilder text = new StringBuilder();
        Set<String> prefixes = new HashSet<>();
        int previousEnd = -1;
        int previousStart = -1;
        int removed = 0;
        Chunk identity = unique.firstEntry().getValue();
        for (Chunk chunk : unique.values()) {
            long payloadSize = (long) chunk.end() - chunk.start();
            if (!sameIdentity(identity, chunk) || chunk.start() < 0 || payloadSize <= 0
                    || payloadSize > chunk.text().length() || chunk.start() < previousStart) {
                return null;
            }
            int prefixSize = chunk.text().length() - (int) payloadSize;
            if (ChunkValidator.splitsSurrogate(chunk.text(), prefixSize)) {
                return null;
            }
            String prefix = chunk.text().substring(0, prefixSize);
            int trim = Math.max(0, Math.min((int) payloadSize, previousEnd - chunk.start()));
            if (ChunkValidator.splitsSurrogate(chunk.text(), prefixSize + trim)) {
                return null;
            }
            if (text.length() > 0 && (chunk.start() > previousEnd || !prefix.isEmpty() && !prefixes.contains(prefix))) {
                text.append("\n\n");
            }
            if (!prefix.isEmpty() && prefixes.add(prefix)) {
                text.append(prefix);
            }
            text.append(chunk.text(), prefixSize + trim, chunk.text().length());
            removed += trim;
            previousEnd = Math.max(previousEnd, chunk.end());
            previousStart = chunk.start();
            if (text.length() > limit) {
                return null;
            }
        }
        return new Window(ordinal, lastOrdinal, anchors, List.copyOf(unique.values()), text.toString(), removed);
    }

    private boolean sameIdentity(Chunk first, Chunk second) {
        return first.documentId() == second.documentId() && first.generation() == second.generation()
                && Objects.equals(first.hash(), second.hash()) && Objects.equals(first.chunker(), second.chunker());
    }

    private Document document(Window window) {
        Document anchor = window.anchors().getFirst();
        Chunk identity = window.chunks().getFirst();
        Map<String, Object> metadata = new LinkedHashMap<>(anchor.getMetadata());
        var snapshot = com.springairag.api.dto.EvidenceSnapshot.from(metadata.get("evidenceSnapshot"));
        if (snapshot != null) {
            metadata.put("evidenceSnapshot", new com.springairag.api.dto.EvidenceSnapshot(
                    snapshot.documentRevision(), identity.hash(), snapshot.collectionId(), "KEYWORD", null,
                    identity.generation(), identity.chunker(), null).withText(window.text()));
        }
        metadata.put("contextAssembly", Map.of("version", 1,
                "anchorChunkIndex", anchor.getMetadata().get("chunkIndex"), "contentHash", identity.hash(),
                "chunkerVersion", identity.chunker(), "localIndexGeneration", identity.generation(),
                "ranges", window.chunks().stream().map(chunk -> Map.of("chunkIndex", chunk.index(),
                        "start", chunk.start(), "end", chunk.end(),
                        "syntheticPrefixCharacters", chunk.text().length() - (chunk.end() - chunk.start()))).toList()));
        metadata.put(ANCHORS, window.anchors());
        return anchor.mutate().text(window.text()).metadata(metadata).build();
    }

    private record Window(int ordinal, int lastOrdinal, List<Document> anchors, List<Chunk> chunks,
                          String text, int removed) { }
}
