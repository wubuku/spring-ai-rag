package com.springairag.core.service;

import com.springairag.core.entity.RagChatHistory;
import com.springairag.core.repository.RagChatHistoryJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chat export service — exports session history as JSON, Markdown, or CSV.
 */
@Service
public class ChatExportService {

    private static final Logger log = LoggerFactory.getLogger(ChatExportService.class);

    private final RagChatHistoryJpaRepository historyRepository;

    public ChatExportService(RagChatHistoryJpaRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    /**
     * Export chat history for a session as JSON bytes.
     *
     * @param sessionId the session ID
     * @param limit max records to export (0 = unlimited)
     * @return JSON string bytes
     */
    public byte[] exportAsJson(String sessionId, int limit) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        List<RagChatHistory> records = fetchRecords(sessionId, limit);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"sessionId\": \"").append(escapeJson(sessionId)).append("\",\n");
        sb.append("  \"totalMessages\": ").append(records.size()).append(",\n");
        sb.append("  \"messages\": [\n");

        for (int i = 0; i < records.size(); i++) {
            RagChatHistory r = records.get(i);
            sb.append("    {");
            sb.append("\"id\": ").append(r.getId()).append(",");
            sb.append("\"role\": \"user\",");
            sb.append("\"content\": \"").append(escapeJson(r.getUserMessage())).append("\",");
            sb.append("\"timestamp\": \"").append(formatTime(r.getCreatedAt())).append("\"");
            sb.append("}");
            if (r.getAiResponse() != null && !r.getAiResponse().isBlank()) {
                sb.append(",\n    {");
                sb.append("\"id\": ").append(r.getId()).append(",");
                sb.append("\"role\": \"assistant\",");
                sb.append("\"content\": \"").append(escapeJson(r.getAiResponse())).append("\",");
                sb.append("\"timestamp\": \"").append(formatTime(r.getCreatedAt())).append("\"");
                sb.append("}");
            }
            if (i < records.size() - 1) {
                sb.append(",\n");
            } else {
                sb.append("\n");
            }
        }

        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Export chat history for a session as Markdown bytes.
     *
     * @param sessionId the session ID
     * @param limit max records to export (0 = unlimited)
     * @return Markdown string bytes
     */
    public byte[] exportAsMarkdown(String sessionId, int limit) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        List<RagChatHistory> records = fetchRecords(sessionId, limit);
        StringBuilder sb = new StringBuilder();
        sb.append("# Chat Export: `").append(sessionId).append("`\n\n");
        sb.append("**Total messages:** ").append(records.size()).append("\n");
        sb.append("**Exported at:** ").append(java.time.LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");
        sb.append("---\n\n");

        for (RagChatHistory r : records) {
            String time = formatTime(r.getCreatedAt());
            sb.append("## User [").append(time).append("]\n\n");
            sb.append(escapeMarkdown(r.getUserMessage())).append("\n\n");
            if (r.getAiResponse() != null && !r.getAiResponse().isBlank()) {
                sb.append("## Assistant [").append(time).append("]\n\n");
                sb.append(escapeMarkdown(r.getAiResponse())).append("\n\n");
            }
            sb.append("---\n\n");
        }

        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Export chat history for a session as CSV bytes.
     *
     * @param sessionId the session ID
     * @param limit max records to export (0 = unlimited)
     * @return CSV string bytes
     */
    public byte[] exportAsCsv(String sessionId, int limit) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        List<RagChatHistory> records = fetchRecords(sessionId, limit);
        StringBuilder sb = new StringBuilder();
        sb.append("timestamp,role,content\n");

        for (RagChatHistory r : records) {
            sb.append(escapeCsv(formatTime(r.getCreatedAt()))).append(",");
            sb.append("user,");
            sb.append(escapeCsv(r.getUserMessage())).append("\n");
            if (r.getAiResponse() != null && !r.getAiResponse().isBlank()) {
                sb.append(escapeCsv(formatTime(r.getCreatedAt()))).append(",");
                sb.append("assistant,");
                sb.append(escapeCsv(r.getAiResponse())).append("\n");
            }
        }

        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private List<RagChatHistory> fetchRecords(String sessionId, int limit) {
        List<RagChatHistory> allRecords = historyRepository.findBySessionIdAsc(sessionId);
        if (limit > 0 && limit < allRecords.size()) {
            return allRecords.subList(allRecords.size() - limit, allRecords.size());
        }
        return allRecords;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String escapeMarkdown(String s) {
        if (s == null) return "";
        return s.replace("```", "\\`\\`\\`");
    }

    private String escapeCsv(String s) {
        if (s == null) return "";
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String escaped = s.replace("\"", "\"\"");
        return needsQuotes ? "\"" + escaped + "\"" : escaped;
    }

    private String formatTime(java.time.LocalDateTime dt) {
        if (dt == null) return "";
        return dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
