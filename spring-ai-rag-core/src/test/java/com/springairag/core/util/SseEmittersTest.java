package com.springairag.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SseEmitters utility tests.
 * Note: SseEmitter callbacks (onCompletion/onError) are triggered asynchronously,
 * so tests verify no exceptions are thrown rather than callback timing.
 */
class SseEmittersTest {

    @Test
    @DisplayName("create() returns a non-null SseEmitter")
    void create_returnsEmitter() {
        SseEmitter emitter = SseEmitters.create();
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("sendProgress does not throw with connected client")
    void sendProgress_noThrow() {
        SseEmitter emitter = SseEmitters.create();
        assertDoesNotThrow(() ->
            SseEmitters.sendProgress(emitter, "progress", Map.of("percent", 50), "test"));
    }

    @Test
    @DisplayName("sendProgress does not throw with disconnected client")
    void sendProgress_disconnectedClient() {
        SseEmitter emitter = SseEmitters.create();
        emitter.complete();
        assertDoesNotThrow(() ->
            SseEmitters.sendProgress(emitter, "progress", Map.of("percent", 50), "test"));
    }

    @Test
    @DisplayName("sendProgress with various event names and data types")
    void sendProgress_variousPayloads() {
        SseEmitter emitter = SseEmitters.create();
        assertDoesNotThrow(() -> {
            SseEmitters.sendProgress(emitter, "progress", Map.of("x", 1), "map");
            SseEmitters.sendProgress(emitter, "chunk", "text content", "string");
            SseEmitters.sendProgress(emitter, "data", 42, "number");
        });
    }

    @Test
    @DisplayName("sendDone does not throw with connected client")
    void sendDone_noThrow() {
        SseEmitter emitter = SseEmitters.create();
        assertDoesNotThrow(() -> SseEmitters.sendDone(emitter, Map.of("status", "ok")));
    }

    @Test
    @DisplayName("sendDone does not throw with disconnected client")
    void sendDone_disconnectedClient() {
        SseEmitter emitter = SseEmitters.create();
        emitter.complete();
        assertDoesNotThrow(() -> SseEmitters.sendDone(emitter, Map.of("status", "ok")));
    }

    @Test
    @DisplayName("sendDone with null data does not throw")
    void sendDone_withNullData() {
        SseEmitter emitter = SseEmitters.create();
        assertDoesNotThrow(() -> SseEmitters.sendDone(emitter, null));
    }

    // --- sendHeartbeat tests ---

    @Test
    @DisplayName("sendHeartbeat does not throw with connected client")
    void sendHeartbeat_noThrow() {
        SseEmitter emitter = SseEmitters.create();
        assertDoesNotThrow(() -> SseEmitters.sendHeartbeat(emitter));
    }

    @Test
    @DisplayName("sendHeartbeat does not throw with disconnected client")
    void sendHeartbeat_disconnectedClient() {
        SseEmitter emitter = SseEmitters.create();
        emitter.complete();
        assertDoesNotThrow(() -> SseEmitters.sendHeartbeat(emitter));
    }

    @Test
    @DisplayName("sendError does not throw with connected client")
    void sendError_noThrow() {
        SseEmitter emitter = SseEmitters.create();
        assertDoesNotThrow(() ->
            SseEmitters.sendError(emitter, "test error", Map.of("key", "value")));
    }

    @Test
    @DisplayName("sendError does not throw with disconnected client")
    void sendError_disconnectedClient() {
        SseEmitter emitter = SseEmitters.create();
        emitter.complete();
        assertDoesNotThrow(() ->
            SseEmitters.sendError(emitter, "test error", Map.of("key", "value")));
    }

    @Test
    @DisplayName("sendError with null extra does not throw")
    void sendError_withNullExtra() {
        SseEmitter emitter = SseEmitters.create();
        assertDoesNotThrow(() ->
            SseEmitters.sendError(emitter, "simple error", null));
    }

    @Test
    @DisplayName("sendError accepts empty extra map")
    void sendError_withEmptyExtra() {
        SseEmitter emitter = SseEmitters.create();
        assertDoesNotThrow(() ->
            SseEmitters.sendError(emitter, "error", Map.of()));
    }

    @Test
    @DisplayName("sendError calls complete() on success, not completeWithError")
    void sendError_successCompletesNormally() {
        SseEmitter emitter = SseEmitters.create();
        final boolean[] completedWithError = {false};
        emitter.onCompletion(() -> { /* normal completion */ });
        emitter.onError(cause -> completedWithError[0] = true);
        SseEmitters.sendError(emitter, "test error", Map.of("key", "value"));
        assertFalse(completedWithError[0], "onError should not be called when send succeeds");
    }

    @Test
    @DisplayName("execute with normal operation does not throw")
    void execute_normalOperation() {
        SseEmitter emitter = SseEmitters.create();
        assertDoesNotThrow(() -> SseEmitters.execute(emitter, callback -> {
            callback.accept("event 1");
            callback.accept("event 2");
        }, Map.of("total", 2)));
    }

    @Test
    @DisplayName("execute with IllegalArgumentException does not throw")
    void execute_illegalArgumentException() {
        SseEmitter emitter = SseEmitters.create();
        assertDoesNotThrow(() -> SseEmitters.execute(emitter, callback -> {
            throw new IllegalArgumentException("invalid input");
        }, Map.of("status", "aborted")));
    }

    @Test
    @DisplayName("execute with generic Exception does not throw")
    void execute_genericException() {
        SseEmitter emitter = SseEmitters.create();
        assertDoesNotThrow(() -> SseEmitters.execute(emitter, callback -> {
            throw new RuntimeException("unexpected");
        }, Map.of("status", "aborted")));
    }

    @Test
    @DisplayName("execute with empty progress events does not throw")
    void execute_emptyProgress() {
        SseEmitter emitter = SseEmitters.create();
        assertDoesNotThrow(() -> SseEmitters.execute(emitter, callback -> {
            // No events
        }, Map.of("total", 0)));
    }

    // --- escapeJson tests ---

    @Test
    @DisplayName("escapeJson null returns empty string")
    void escapeJson_null() {
        assertEquals("", SseEmitters.escapeJson(null));
    }

    @Test
    @DisplayName("escapeJson escapes backslash")
    void escapeJson_backslash() {
        assertEquals("\\\\", SseEmitters.escapeJson("\\"));
    }

    @Test
    @DisplayName("escapeJson escapes double-quote")
    void escapeJson_quote() {
        assertEquals("\\\"", SseEmitters.escapeJson("\""));
    }

    @Test
    @DisplayName("escapeJson escapes newline")
    void escapeJson_newline() {
        assertEquals("\\n", SseEmitters.escapeJson("\n"));
    }

    @Test
    @DisplayName("escapeJson escapes carriage return")
    void escapeJson_carriageReturn() {
        assertEquals("\\r", SseEmitters.escapeJson("\r"));
    }

    @Test
    @DisplayName("escapeJson escapes tab")
    void escapeJson_tab() {
        assertEquals("\\t", SseEmitters.escapeJson("\t"));
    }

    @Test
    @DisplayName("escapeJson preserves plain text")
    void escapeJson_plainText() {
        assertEquals("hello world", SseEmitters.escapeJson("hello world"));
    }

    @Test
    @DisplayName("escapeJson handles SSE injection characters")
    void escapeJson_sseInjection() {
        String input = "hello\nworld\rt\best\\special\"chars";
        String result = SseEmitters.escapeJson(input);
        assertFalse(result.contains("\n"), "Should not contain raw newline");
        assertFalse(result.contains("\r"), "Should not contain raw carriage return");
        assertFalse(result.contains("\t"), "Should not contain raw tab");
        // After escaping, the string contains \" and \\ (not bare " or \)
        assertTrue(result.contains("\\\""), "Should escape double-quote");
        assertTrue(result.contains("\\\\"), "Should escape backslash");
    }

    // --- sendRaw tests ---

    @Test
    @DisplayName("sendRaw null event name sends data without event name")
    void sendRaw_nullEventName() {
        SseEmitter emitter = SseEmitters.create();
        assertDoesNotThrow(() -> SseEmitters.sendRaw(emitter, null,
                "{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}", "test"));
    }

    @Test
    @DisplayName("sendRaw with event name sends named event")
    void sendRaw_withEventName() {
        SseEmitter emitter = SseEmitters.create();
        assertDoesNotThrow(() -> SseEmitters.sendRaw(emitter, "done",
                "{\"status\":\"complete\"}", "test"));
    }

    @Test
    @DisplayName("sendRaw handles IO exception gracefully")
    void sendRaw_ioException_handled() {
        SseEmitter emitter = SseEmitters.create();
        emitter.completeWithError(new IOException("simulated disconnect"));
        // Should not throw - sendRaw catches and logs the exception
        assertDoesNotThrow(() -> SseEmitters.sendRaw(emitter, null,
                "{\"data\":\"test\"}", "after-complete"));
    }
}
