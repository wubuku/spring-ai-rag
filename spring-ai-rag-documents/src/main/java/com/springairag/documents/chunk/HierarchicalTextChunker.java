package com.springairag.documents.chunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hierarchical document chunker
 *
 * <p>Supports:
 * <ul>
 *   <li>Multi-level Markdown heading recursive splitting (# - ######)</li>
 *   <li>Table integrity preservation</li>
 *   <li>Heading inheritance mechanism (child headings inherit parent heading context)</li>
 *   <li>Sentence-level fine splitting (fallback for overly long paragraphs)</li>
 * </ul>
 *
 * <p>Based on MaxKB4j document chunking strategy.
 */
public class HierarchicalTextChunker {

    private static final String[] HEADER_PATTERNS = {
            "(?m)^# .*$",
            "(?m)^## .*$",
            "(?m)^### .*$",
            "(?m)^#### .*$",
            "(?m)^##### .*$",
            "(?m)^###### .*$"
    };

    private static final Pattern MD_TABLE_PATTERN = Pattern.compile(
            "(?m)^\\s*\\|.*?\\|\\s*(?:\\n|$)" +
                    "(?:\\s*\\|[-:\\s|]*\\|\\s*(?:\\n|$))?" +
                    "(?:\\s*\\|.*?\\|\\s*(?:\\n|$))*"
    );

    private final int maxChunkSize;
    private final int minChunkSize;
    private final int chunkOverlap;

    public HierarchicalTextChunker(int maxChunkSize, int minChunkSize, int chunkOverlap) {
        this.maxChunkSize = maxChunkSize;
        this.minChunkSize = minChunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    /**
     * Execute hierarchical chunking
     *
     * @param content raw document content
     * @return chunk list (sorted by original text position)
     */
    public List<TextChunk> split(String content) {
        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }

        // 1. Extract and preserve tables
        List<TableBlock> tables = extractTables(content);
        String contentWithoutTables = MD_TABLE_PATTERN.matcher(content).replaceAll("");

        // 2. Split recursively by heading level
        List<SectionBlock> sections = splitByHeaders(contentWithoutTables);

        // 3. Further split oversized paragraphs by sentence
        List<TextChunk> chunks = new ArrayList<>();
        for (SectionBlock section : sections) {
            if (section.content.length() > maxChunkSize) {
                List<TextChunk> sentChunks = splitBySentences(section.content, section.titles);
                if (sentChunks.isEmpty()) {
                    chunks.addAll(splitByFixedSize(section.content, section.titles));
                } else {
                    chunks.addAll(sentChunks);
                }
            } else {
                String titleContext = String.join(" > ", section.titles);
                String chunkText = titleContext.isEmpty() ? section.content
                        : titleContext + "\n" + section.content;
                chunks.add(new TextChunk(chunkText.trim(), section.startPos, section.endPos));
            }
        }

        // 4. Insert table blocks
        chunks.addAll(convertTablesToChunks(tables));

        // 5. Filter out too-short fragments and sort
        return filterAndSortChunks(chunks);
    }

    /**
     * Static factory method
     */
    public static List<TextChunk> split(String content, int chunkSize, int chunkOverlap) {
        return new HierarchicalTextChunker(chunkSize, 10, chunkOverlap).split(content);
    }

    private List<TextChunk> splitByFixedSize(String content, List<String> titles) {
        List<TextChunk> chunks = new ArrayList<>();
        String titleContext = String.join(" > ", titles);

        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + maxChunkSize, content.length());
            String chunkText = content.substring(start, end);
            if (!titleContext.isEmpty()) {
                chunkText = titleContext + "\n" + chunkText;
            }
            chunks.add(new TextChunk(chunkText.trim(), start, end));
            start = end;
        }

        return chunks;
    }

    private List<TableBlock> extractTables(String content) {
        List<TableBlock> tables = new ArrayList<>();
        Matcher matcher = MD_TABLE_PATTERN.matcher(content);

        while (matcher.find()) {
            String tableText = matcher.group();
            if (isValidTable(tableText)) {
                tables.add(new TableBlock(tableText, matcher.start(), matcher.end()));
            }
        }
        return tables;
    }

    private boolean isValidTable(String text) {
        if (text == null || !text.contains("|")) return false;
        String[] lines = text.trim().split("\n");
        if (lines.length < 2) return false;
        int colCount = -1;
        for (String line : lines) {
            if (line.contains("|")) {
                int cols = line.split("\\|").length - 1;
                if (colCount == -1) colCount = cols;
                else if (cols != colCount) return false;
            }
        }
        return true;
    }

    private List<SectionBlock> splitByHeaders(String content) {
        List<SectionBlock> result = new ArrayList<>();
        if (content.isBlank()) return result;

        result.add(new SectionBlock(new ArrayList<>(), content, 0, content.length()));
        for (String pattern : HEADER_PATTERNS) {
            result = processLevel(result, pattern);
        }
        return result;
    }

    private List<SectionBlock> processLevel(List<SectionBlock> blocks, String pattern) {
        List<SectionBlock> result = new ArrayList<>();
        Pattern regex = Pattern.compile(pattern);

        for (SectionBlock block : blocks) {
            Matcher matcher = regex.matcher(block.content);
            int lastEnd = 0;
            List<String> currentTitles = new ArrayList<>(block.titles);

            while (matcher.find()) {
                String beforeContent = block.content.substring(lastEnd, matcher.start()).trim();
                if (!beforeContent.isEmpty()) {
                    result.add(new SectionBlock(currentTitles, beforeContent,
                            block.startPos + lastEnd, block.startPos + matcher.start()));
                }

                String title = cleanTitle(matcher.group());
                List<String> newTitles = new ArrayList<>(currentTitles);
                newTitles.add(title);
                currentTitles = newTitles;
                lastEnd = matcher.end();
            }

            String remaining = block.content.substring(lastEnd).trim();
            if (!remaining.isEmpty()) {
                result.add(new SectionBlock(currentTitles, remaining,
                        block.startPos + lastEnd, block.startPos + block.content.length()));
            }
        }

        return result;
    }

    private String cleanTitle(String headerLine) {
        return headerLine.replaceAll("^#+\\s*", "").trim();
    }

    private List<TextChunk> splitBySentences(String content, List<String> titles) {
        List<TextChunk> chunks = new ArrayList<>();
        String[] sentences = content.split("(?<=[.!?。！？])\\s+");

        if (sentences.length == 0 || (sentences.length == 1 && sentences[0].isBlank())) {
            return chunks;
        }

        StringBuilder currentChunk = new StringBuilder();
        int chunkStart = 0;
        String titleContext = String.join(" > ", titles);

        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) continue;

            if (currentChunk.length() + trimmed.length() > maxChunkSize && currentChunk.length() > 0) {
                String chunkText = titleContext.isEmpty() ? currentChunk.toString()
                        : titleContext + "\n" + currentChunk.toString();
                chunks.add(new TextChunk(chunkText.trim(), chunkStart, chunkStart + currentChunk.length()));

                // Overlap handling
                int overlapStart = Math.max(0, currentChunk.length() - chunkOverlap);
                String overlapText = currentChunk.substring(overlapStart);
                currentChunk = new StringBuilder(overlapText);
                chunkStart = chunkStart + currentChunk.length() - overlapText.length();
            }

            currentChunk.append(trimmed).append(" ");
        }

        if (currentChunk.length() > 0) {
            String chunkText = titleContext.isEmpty() ? currentChunk.toString()
                    : titleContext + "\n" + currentChunk.toString();
            chunks.add(new TextChunk(chunkText.trim(), chunkStart, chunkStart + currentChunk.length()));
        }

        return chunks;
    }

    private List<TextChunk> convertTablesToChunks(List<TableBlock> tables) {
        return tables.stream()
                .map(t -> new TextChunk("[表格]\n" + t.content, t.startPos, t.endPos))
                .toList();
    }

    private List<TextChunk> filterAndSortChunks(List<TextChunk> chunks) {
        return chunks.stream()
                .filter(c -> c.text().length() >= minChunkSize)
                .sorted(Comparator.comparingInt(TextChunk::startPos))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static class TableBlock {
        final String content;
        final int startPos;
        final int endPos;

        TableBlock(String content, int startPos, int endPos) {
            this.content = content;
            this.startPos = startPos;
            this.endPos = endPos;
        }
    }

    private static class SectionBlock {
        final List<String> titles;
        final String content;
        final int startPos;
        final int endPos;

        SectionBlock(List<String> titles, String content, int startPos, int endPos) {
            this.titles = titles;
            this.content = content;
            this.startPos = startPos;
            this.endPos = endPos;
        }
    }
}
