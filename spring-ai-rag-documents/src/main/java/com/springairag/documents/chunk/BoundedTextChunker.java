package com.springairag.documents.chunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 原文 span 驱动的有界结构分块策略；不改写兼容 v2 算法。 */
public final class BoundedTextChunker {
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");

    private final int size;
    private final int minSize;
    private final int overlap;
    private final int maxInputChars;
    private final int maxChunks;

    public BoundedTextChunker(int size, int minSize, int overlap, int maxInputChars, int maxChunks) {
        if (size < 32 || minSize < 0 || minSize > size || overlap < 0 || overlap >= size
                || maxInputChars <= 0 || maxChunks <= 0) {
            throw new IllegalArgumentException("Invalid bounded chunking budgets");
        }
        this.size = size;
        this.minSize = minSize;
        this.overlap = overlap;
        this.maxInputChars = maxInputChars;
        this.maxChunks = maxChunks;
    }

    public ChunkingResult split(String original) {
        String source = original == null ? "" : original;
        if (source.length() > maxInputChars) {
            throw new ChunkingException("CHUNK_INPUT_LIMIT");
        }
        Builder builder = new Builder(source);
        builder.scan();
        ChunkDiagnostics diagnostics = builder.diagnostics();
        if (!diagnostics.valid()) {
            if (builder.hasProtectedBlocks) {
                throw new ChunkingException("CHUNK_VALIDATION_FAILED");
            }
            builder.chunks.clear();
            builder.metadata.clear();
            builder.warnings.add("VALIDATION_FIXED_WINDOW_FALLBACK");
            builder.fixedOnly = true;
            builder.plain(0, source.length(), List.of());
            diagnostics = builder.diagnostics();
            if (!diagnostics.valid()) {
                throw new ChunkingException("CHUNK_VALIDATION_FAILED");
            }
        }
        return new ChunkingResult(builder.chunks, builder.metadata, diagnostics);
    }

    private final class Builder {
        private final String source;
        private final List<TextChunk> chunks = new ArrayList<>();
        private final List<ChunkingResult.ChunkMetadata> metadata = new ArrayList<>();
        private final LinkedHashSet<String> warnings = new LinkedHashSet<>();
        private boolean hasProtectedBlocks;
        private boolean fixedOnly;

        private Builder(String source) {
            this.source = source;
        }

        private ChunkDiagnostics diagnostics() {
            if (chunks.stream().filter(chunk -> chunk.text().length() < minSize).count() > 2) {
                warnings.add("SMALL_FRAGMENTS");
            }
            return ChunkValidator.analyze(source, chunks, size, maxChunks, new ArrayList<>(warnings));
        }

        private void scan() {
            List<Line> lines = lines();
            List<String> titles = new ArrayList<>();
            int plainStart = 0;
            for (int index = 0; index < lines.size(); index++) {
                Line line = lines.get(index);
                Matcher heading = HEADING.matcher(line.body());
                Matcher fence = FENCE.matcher(line.body());
                boolean formula = line.body().strip().equals("$$");
                boolean table = index + 1 < lines.size() && line.body().contains("|")
                        && isTableSeparator(lines.get(index + 1).body())
                        && columns(line.body()) == columns(lines.get(index + 1).body());
                if (heading.matches()) {
                    plain(plainStart, line.start(), titles);
                    int depth = heading.group(1).length();
                    while (titles.size() >= depth) {
                        titles.removeLast();
                    }
                    while (titles.size() < depth - 1) {
                        titles.add("");
                    }
                    titles.add(heading.group(2));
                    plainStart = line.start();
                } else if (fence.matches() || formula || table) {
                    plain(plainStart, line.start(), titles);
                    hasProtectedBlocks = true;
                    int last = index;
                    String kind;
                    String tableHeader = "";
                    if (table) {
                        kind = "TABLE";
                        last = index + 1;
                        tableHeader = source.substring(line.start(), lines.get(last).end());
                        while (last + 1 < lines.size() && lines.get(last + 1).body().contains("|")
                                && !lines.get(last + 1).body().isBlank()) {
                            last++;
                        }
                    } else {
                        kind = formula ? "FORMULA" : "CODE";
                        String marker = formula ? "$$" : fence.group(1);
                        boolean closed = false;
                        while (last + 1 < lines.size()) {
                            last++;
                            String candidate = lines.get(last).body().strip();
                            if (formula ? candidate.equals(marker) : closesFence(candidate, marker)) {
                                closed = true;
                                break;
                            }
                        }
                        if (!closed) {
                            warnings.add("UNTERMINATED_PROTECTED_BLOCK");
                        }
                    }
                    protectedLines(lines, index, last, kind, titles, tableHeader);
                    plainStart = lines.get(last).end();
                    index = last;
                }
            }
            plain(plainStart, source.length(), titles);
        }

        private List<Line> lines() {
            List<Line> result = new ArrayList<>();
            int start = 0;
            while (start < source.length()) {
                int newline = source.indexOf('\n', start);
                int end = newline < 0 ? source.length() : newline + 1;
                String body = source.substring(start, end).stripTrailing();
                result.add(new Line(start, end, body));
                start = end;
            }
            return result;
        }

        private String context(List<String> titles) {
            String context = String.join(" > ", titles.stream().filter(title -> !title.isEmpty()).toList());
            if (context.isEmpty()) {
                return "";
            }
            if (context.length() + 1 > size / 2) {
                warnings.add("TITLE_CONTEXT_OMITTED");
                return "";
            }
            return context + "\n";
        }

        private void plain(int start, int end, List<String> titles) {
            String prefix = context(titles);
            int capacity = size - prefix.length();
            int position = start;
            while (position < end) {
                int boundary = position + Math.min(capacity, end - position);
                if (boundary < end && !fixedOnly) {
                    int minimum = position + capacity / 2;
                    int preferred = boundary;
                    while (preferred > minimum && !isBoundary(source.charAt(preferred - 1))) {
                        preferred--;
                    }
                    if (preferred > minimum) {
                        boundary = preferred;
                    } else {
                        warnings.add("FIXED_WINDOW_FALLBACK");
                    }
                }
                if (ChunkValidator.splitsSurrogate(source, boundary)) {
                    boundary--;
                }
                emit(position, boundary, prefix, "TEXT", titles);
                if (boundary == end) {
                    break;
                }
                int next = Math.max(position + 1, boundary - Math.min(overlap, capacity / 2));
                if (ChunkValidator.splitsSurrogate(source, next)) {
                    next++;
                }
                position = next;
            }
        }

        private void protectedLines(List<Line> lines, int first, int last, String kind,
                                    List<String> titles, String header) {
            String titleContext = context(titles);
            int start = lines.get(first).start();
            int end = lines.get(last).end();
            if (end - start + titleContext.length() <= size) {
                emit(start, end, titleContext, kind, titles);
                return;
            }
            warnings.add("PROTECTED_BLOCK_SPLIT_BY_LINE");
            int groupStart = first;
            while (groupStart <= last) {
                String prefix = titleContext + (groupStart > first ? header : "");
                int groupEnd = groupStart;
                while (groupEnd < last && lines.get(groupEnd + 1).end() - lines.get(groupStart).start()
                        + prefix.length() <= size) {
                    groupEnd++;
                }
                if (kind.equals("TABLE") && groupStart == first && groupEnd < first + 1) {
                    throw new ChunkingException("CHUNK_ATOMIC_BLOCK_TOO_LARGE");
                }
                if (lines.get(groupEnd).end() - lines.get(groupStart).start() + prefix.length() > size) {
                    throw new ChunkingException("CHUNK_ATOMIC_BLOCK_TOO_LARGE");
                }
                emit(lines.get(groupStart).start(), lines.get(groupEnd).end(), prefix, kind, titles);
                groupStart = groupEnd + 1;
            }
        }

        private void emit(int start, int end, String prefix, String kind, List<String> titles) {
            while (kind.equals("TEXT") && start < end && Character.isWhitespace(source.charAt(start))) {
                start++;
            }
            while (kind.equals("TEXT") && end > start && Character.isWhitespace(source.charAt(end - 1))) {
                end--;
            }
            if (start == end) {
                return;
            }
            if (chunks.size() >= maxChunks) {
                throw new ChunkingException("CHUNK_COUNT_LIMIT");
            }
            chunks.add(new TextChunk(prefix + source.substring(start, end), start, end));
            metadata.add(new ChunkingResult.ChunkMetadata(kind,
                    titles.stream().filter(title -> !title.isEmpty()).toList(), prefix.length()));
        }
    }

    private static int columns(String line) {
        return cells(line).length;
    }

    private static String[] cells(String line) {
        String body = line.strip();
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        return body.split("\\|", -1);
    }

    private static boolean isTableSeparator(String line) {
        String[] cells = cells(line);
        if (cells.length < 1) {
            return false;
        }
        for (String cell : cells) {
            String separator = cell.strip();
            if (separator.startsWith(":")) {
                separator = separator.substring(1);
            }
            if (separator.endsWith(":")) {
                separator = separator.substring(0, separator.length() - 1);
            }
            if (separator.length() < 3 || !separator.chars().allMatch(character -> character == '-')) {
                return false;
            }
        }
        return true;
    }

    private static boolean closesFence(String line, String marker) {
        return line.length() >= marker.length()
                && line.chars().allMatch(character -> character == marker.charAt(0));
    }

    private static boolean isBoundary(char character) {
        return Character.isWhitespace(character) || ".!?。！？".indexOf(character) >= 0;
    }

    private record Line(int start, int end, String body) {
    }
}
