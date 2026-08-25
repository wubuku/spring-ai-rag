package com.springairag.core.resource;

import com.springairag.core.config.RagChatProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Startup-built lexical index for deployment-provided, non-embedded knowledge.
 */
@Component
public final class StaticKnowledgeCatalog {

    private static final Logger log =
            LoggerFactory.getLogger(StaticKnowledgeCatalog.class);
    private static final Pattern HEADING =
            Pattern.compile("^\\s{0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern TERM =
            Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern CJK =
            Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]");
    private static final Pattern LATIN_OR_DIGIT =
            Pattern.compile("[\\p{IsLatin}\\p{N}]");
    private static final int MIN_CJK_MATCHES = 2;
    private static final double MIN_CJK_QUERY_COVERAGE = 0.25;

    private final ResourceCatalog resourceCatalog;
    private final RagChatProperties properties;
    private volatile Snapshot snapshot = Snapshot.empty();

    public StaticKnowledgeCatalog(
            ResourceCatalog resourceCatalog,
            RagChatProperties properties) {
        this.resourceCatalog = resourceCatalog;
        this.properties = properties;
    }

    @PostConstruct
    void initialize() {
        RagChatProperties.StaticKnowledgeProperties config =
                properties.getStaticKnowledge();
        if (!config.isEnabled() || config.getLocations().isEmpty()) {
            snapshot = Snapshot.empty();
            log.debug("Static knowledge snapshot disabled or unconfigured");
            return;
        }
        ResourceSnapshot resources = resourceCatalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                config.getLocations(),
                new HashSet<>(config.getFileExtensions()),
                config.getMaxFilesPerRoot(),
                config.getMaxFileBytes(),
                config.getMaxTotalBytes(),
                config.isFailFast());
        if (!resources.healthy()) {
            snapshot = new Snapshot(
                    resources.generation(),
                    resources.digest(),
                    false,
                    List.of());
            log.warn(
                    "Static knowledge snapshot degraded: generation={}, healthy=false, "
                            + "entries=0, bytes=0, digest={}",
                    resources.generation(),
                    shortDigest(resources.digest()));
            return;
        }
        List<StaticKnowledgeChunk> chunks = new ArrayList<>();
        for (ResourceEntry entry : resources.entries()) {
            chunks.addAll(parse(entry, config));
        }
        chunks.sort(Comparator.comparing(StaticKnowledgeChunk::id));
        snapshot = new Snapshot(
                resources.generation(),
                resources.digest(),
                resources.healthy(),
                chunks);
        log.info(
                "Static knowledge snapshot loaded: generation={}, healthy={}, "
                        + "entries={}, chunks={}, bytes={}, digest={}",
                snapshot.generation(),
                snapshot.healthy(),
                resources.entries().size(),
                snapshot.chunks().size(),
                resources.entries().stream()
                        .mapToInt(entry -> entry.content().length)
                        .sum(),
                shortDigest(snapshot.digest()));
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public List<Document> search(String query, int limit, int maxCharacters) {
        if (query == null || query.isBlank() || limit <= 0 || maxCharacters <= 0) {
            return List.of();
        }
        if (!snapshot.healthy()) {
            return List.of();
        }
        RagChatProperties.StaticKnowledgeProperties config =
                properties.getStaticKnowledge();
        int effectiveLimit = Math.min(limit, config.getRetrievalMaxResults());
        int effectiveCharacters = Math.min(
                maxCharacters,
                config.getRetrievalMaxResultCharacters());
        if (effectiveLimit <= 0 || effectiveCharacters <= 0) {
            return List.of();
        }
        String normalizedQuery = normalize(query);
        List<String> queryTerms = terms(normalizedQuery);
        Set<String> queryCjk = cjkCharacters(normalizedQuery);
        List<ScoredChunk> ranked = new ArrayList<>();
        for (StaticKnowledgeChunk chunk : snapshot.chunks()) {
            String searchable = normalize(chunk.text() + " " + chunk.titlePath()
                    + " " + chunk.relativePath());
            int termMatches = 0;
            for (String term : queryTerms) {
                if (chunk.terms().contains(term)) {
                    termMatches++;
                }
            }
            int cjkMatches = 0;
            if (!queryCjk.isEmpty()) {
                for (String character : queryCjk) {
                    if (searchable.contains(character)) {
                        cjkMatches++;
                    }
                }
            }
            boolean phrase = searchable.contains(normalizedQuery);
            boolean titlePhrase = normalize(chunk.titlePath()).contains(normalizedQuery);
            boolean latinOrNumericTermMatch = queryTerms.stream()
                    .filter(this::containsLatinOrDigit)
                    .anyMatch(chunk.terms()::contains);
            double cjkCoverage = queryCjk.isEmpty()
                    ? 0
                    : (double) cjkMatches / queryCjk.size();
            boolean meaningfulCjkOverlap = cjkMatches >= MIN_CJK_MATCHES
                    && cjkCoverage >= MIN_CJK_QUERY_COVERAGE;
            if (!phrase && !titlePhrase && !latinOrNumericTermMatch
                    && !meaningfulCjkOverlap) {
                continue;
            }
            double score = (phrase ? 1000 : 0)
                    + (titlePhrase ? 250 : 0)
                    + (queryTerms.isEmpty()
                    ? 0
                    : 100.0 * termMatches / queryTerms.size())
                    + (queryCjk.isEmpty()
                    ? 0
                    : 25.0 * cjkMatches / queryCjk.size());
            ranked.add(new ScoredChunk(chunk, score, termMatches, cjkMatches));
        }
        ranked.sort(Comparator
                .comparingDouble(ScoredChunk::score).reversed()
                .thenComparing(
                        Comparator.comparingInt(ScoredChunk::termMatches)
                                .reversed())
                .thenComparing(
                        Comparator.comparingInt(ScoredChunk::cjkMatches)
                                .reversed())
                .thenComparing(value -> value.chunk().id()));
        List<Document> result = new ArrayList<>();
        int remaining = effectiveCharacters;
        for (ScoredChunk item : ranked) {
            if (result.size() >= effectiveLimit || remaining <= 0) {
                break;
            }
            StaticKnowledgeChunk chunk = item.chunk();
            String text = fitCharacters(chunk.text(), remaining);
            if (text.isBlank()) {
                break;
            }
            Map<String, Object> metadata = new LinkedHashMap<>(chunk.metadata());
            metadata.put("score", item.score());
            metadata.put("sourceType", "STATIC_KNOWLEDGE");
            result.add(Document.builder()
                    .id(chunk.id())
                    .text(text)
                    .metadata(metadata)
                    .score(item.score())
                    .build());
            remaining -= text.length();
        }
        return List.copyOf(result);
    }

    private List<StaticKnowledgeChunk> parse(
            ResourceEntry entry,
            RagChatProperties.StaticKnowledgeProperties config) {
        String text = decode(entry.content());
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String digest = sha256(entry.content());
        List<StaticKnowledgeChunk> result = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        String title = entry.relativePath();
        StringBuilder current = new StringBuilder();
        int chunkIndex = 0;
        for (String line : normalized.split("\n", -1)) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                if (!current.toString().isBlank()) {
                    result.add(chunk(entry, digest, chunkIndex++, title,
                            current.toString(), config));
                    current.setLength(0);
                }
                String headingText = heading.group(1).trim();
                int level = headingLevel(line);
                while (headingPath.size() >= level) {
                    headingPath.removeLast();
                }
                headingPath.add(headingText);
                title = String.join(" / ", headingPath);
                current.append(title).append('\n');
                continue;
            }
            if (line.isBlank() && current.length() > 0
                    && current.length() >= config.getChunkMaxCharacters()) {
                result.add(chunk(entry, digest, chunkIndex++, title,
                        current.toString(), config));
                current.setLength(0);
                continue;
            }
            if (current.length() + line.length() + 1
                    <= config.getChunkMaxCharacters()) {
                current.append(line).append('\n');
            } else {
                String value = current.toString();
                if (!value.isBlank()) {
                    result.add(chunk(
                            entry, digest, chunkIndex++, title, value, config));
                }
                String overlap = overlap(value, config.getChunkOverlapCharacters());
                current.setLength(0);
                current.append(overlap).append(line).append('\n');
                while (current.length() > config.getChunkMaxCharacters()) {
                    String window = current.substring(0, config.getChunkMaxCharacters());
                    result.add(chunk(entry, digest, chunkIndex++, title, window, config));
                    current.delete(0, Math.max(1,
                            config.getChunkMaxCharacters()
                                    - config.getChunkOverlapCharacters()));
                }
            }
        }
        if (!current.toString().isBlank()) {
            result.add(chunk(entry, digest, chunkIndex, title,
                    current.toString(), config));
        }
        return result;
    }

    private int headingLevel(String line) {
        String normalized = line.stripLeading();
        int level = 0;
        while (level < normalized.length() && normalized.charAt(level) == '#') {
            level++;
        }
        return Math.max(1, Math.min(6, level));
    }

    private StaticKnowledgeChunk chunk(
            ResourceEntry entry,
            String digest,
            int index,
            String title,
            String rawText,
            RagChatProperties.StaticKnowledgeProperties config) {
        String text = rawText.trim();
        if (text.length() > config.getChunkMaxCharacters()) {
            text = text.substring(0, config.getChunkMaxCharacters());
        }
        String id = "static:" + shortHash(entry.root().rootKey() + ":"
                + entry.relativePath() + ":" + index + ":" + digest);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", id);
        metadata.put("chunkIndex", index);
        metadata.put("title", title);
        metadata.put("sourceType", "STATIC_KNOWLEDGE");
        metadata.put("rootKey", entry.root().rootKey());
        metadata.put("sourceLabel", entry.root().sourceLabel());
        metadata.put("relativePath", entry.relativePath());
        metadata.put("contentDigest", digest);
        metadata.put("titlePath", title);
        return new StaticKnowledgeChunk(
                id,
                entry.root().rootKey(),
                entry.root().sourceLabel(),
                entry.relativePath(),
                digest,
                index,
                title,
                text,
                terms(normalize(text + " " + title)),
                metadata);
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("Static knowledge is not valid UTF-8", e);
        }
    }

    private String overlap(String text, int characters) {
        if (characters <= 0 || text.isBlank()) {
            return "";
        }
        int start = Math.max(0, text.length() - characters);
        return text.substring(start);
    }

    private String fitCharacters(String text, int maxCharacters) {
        return text.length() <= maxCharacters
                ? text
                : text.substring(0, maxCharacters);
    }

    private List<String> terms(String value) {
        List<String> result = new ArrayList<>();
        Matcher matcher = TERM.matcher(value);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return List.copyOf(new LinkedHashSet<>(result));
    }

    private Set<String> cjkCharacters(String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = CJK.matcher(value);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private boolean containsLatinOrDigit(String value) {
        return LATIN_OR_DIGIT.matcher(value).find();
    }

    private String normalize(String value) {
        return java.text.Normalizer.normalize(
                        value == null ? "" : value,
                        java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String shortHash(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }

    private String shortDigest(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 16 ? value : value.substring(0, 16);
    }

    public record Snapshot(
            long generation,
            String digest,
            boolean healthy,
            List<StaticKnowledgeChunk> chunks) {

        public Snapshot {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
            digest = digest == null ? "" : digest;
        }

        static Snapshot empty() {
            return new Snapshot(0, "", true, List.of());
        }
    }

    private record ScoredChunk(
            StaticKnowledgeChunk chunk,
            double score,
            int termMatches,
            int cjkMatches) {
    }
}
