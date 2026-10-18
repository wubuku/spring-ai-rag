package com.springairag.core.evaluation;

import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EvaluationDiagnosticCapture implements AutoCloseable {
    private static final ThreadLocal<EvaluationDiagnosticCapture> ACTIVE = new ThreadLocal<>();
    private final Map<String, Object> data = new LinkedHashMap<>();
    private boolean truncated;
    private boolean redacted;

    private EvaluationDiagnosticCapture() { }

    public static EvaluationDiagnosticCapture open() {
        if (ACTIVE.get() != null) throw new IllegalStateException("nested diagnostic capture");
        var capture = new EvaluationDiagnosticCapture();
        ACTIVE.set(capture);
        return capture;
    }

    public static void prompt(Prompt prompt) {
        var capture = ACTIVE.get();
        if (capture == null) return;
        List<Map<String, Object>> messages = new ArrayList<>();
        int remaining = 32768;
        for (var message : prompt.getInstructions()) {
            if (remaining <= 0) { capture.truncated = true; break; }
            String text = capture.safe(message.getText(), remaining);
            remaining -= text.getBytes(StandardCharsets.UTF_8).length;
            messages.add(Map.of("role", message.getMessageType().getValue(), "content", text));
        }
        capture.data.put("lastInvocationMessages", messages);
    }

    public static void result(ChatExecutionResult result) {
        var capture = ACTIVE.get();
        if (capture == null) return;
        capture.data.put("answer", capture.safe(result.answer(), 32768));
        capture.data.put("answerOutcome", result.metadata().getOrDefault("answerOutcome", Map.of()));
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (var source : result.sources()) {
            if (evidence.size() >= 16) { capture.truncated = true; break; }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("citationId", source.getCitationId());
            item.put("documentId", source.getDocumentId());
            item.put("text", capture.safe(source.getChunkText(), 4096));
            evidence.add(item);
        }
        capture.data.put("sourceProjections", evidence);
    }

    public static void evidence(List<Document> documents) {
        var capture = ACTIVE.get();
        if (capture == null) return;
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (var document : documents) {
            if (evidence.size() >= 16) { capture.truncated = true; break; }
            evidence.add(Map.of("text", capture.safe(document.getText(), 4096)));
        }
        capture.data.put("evidence", evidence);
        capture.data.put("evidenceOrigin", "RETRIEVAL_AUGMENTATION_DOCUMENT_CONTEXT");
    }

    public Map<String, Object> snapshot() {
        var result = new LinkedHashMap<>(data);
        result.put("truncated", truncated);
        result.put("redacted", redacted);
        result.put("complete", !truncated && !redacted && data.containsKey("lastInvocationMessages") && data.containsKey("answer") && data.containsKey("evidence") && data.containsKey("modelAnswer"));
        return result;
    }

    private String safe(String value, int limit) {
        String text = value == null ? "" : value;
        String masked = SensitiveDataMaskingConverter.maskSensitiveData(text);
        if (!text.equals(masked)) redacted = true;
        int used = 0;
        int end = 0;
        while (end < masked.length()) {
            int point = masked.codePointAt(end);
            int bytes = new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > limit) { truncated = true; break; }
            used += bytes;
            end += Character.charCount(point);
        }
        masked = masked.substring(0, end);
        return masked;
    }

    public static void modelAnswer(String answer, com.springairag.api.dto.CitationValidation validation) {
        var capture = ACTIVE.get();
        if (capture == null) return;
        capture.data.put("modelAnswer", capture.safe(answer, 32768));
        if (validation != null) capture.data.put("citationValidation", validation);
    }

    @Override
    public void close() {
        ACTIVE.remove();
        data.clear();
    }
}
