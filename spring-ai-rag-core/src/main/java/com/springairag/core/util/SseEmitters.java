package com.springairag.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.function.Consumer;

/**
 * SSE emitter helper utilities for streaming responses.
 *
 * <p>Provides reusable patterns for SSE lifecycle management:
 * <ul>
 *   <li>Bounded stream lifetime (emitter timeout) so a silent upstream cannot
 *       hold a request-admission slot forever</li>
 *   <li>Progress event sending with best-effort error handling</li>
 *   <li>Standardized done/error completion</li>
 *   <li>Heartbeat comments to keep connections alive through proxies</li>
 * </ul>
 */
public final class SseEmitters {

    private static final Logger log = LoggerFactory.getLogger(SseEmitters.class);

    private SseEmitters() {}

    /**
     * Creates a no-timeout SSE emitter.
     * Use only for snapshots that complete immediately; live streams should use
     * {@link #create(long)} so a stalled upstream is reclaimed.
     *
     * @return a new SseEmitter with no timeout
     */
    public static SseEmitter create() {
        return new SseEmitter(0L);
    }

    /**
     * Creates an SSE emitter with an explicit async timeout.
     *
     * <p>Spring MVC's async timeout is the backstop for a stream whose upstream
     * never sends another event. Without it the client connection, the upstream
     * socket and the request-admission lease stay held even though the chat
     * execution budget has already expired.
     *
     * @param timeoutMs maximum lifetime in milliseconds; {@code 0} means no timeout
     * @return a new SseEmitter
     */
    public static SseEmitter create(long timeoutMs) {
        return new SseEmitter(timeoutMs);
    }

    /**
     * Sends a progress event to the emitter.
     * Failures are logged but not propagated (best-effort; client likely disconnected).
     *
     * @param emitter the SSE emitter
     * @param eventName event name (e.g., "progress", "chunk")
     * @param data the event data (will be serialized to JSON)
     * @param context human-readable context for warning logs (e.g., "document 123")
     */
    public static void sendProgress(SseEmitter emitter, String eventName, Object data, String context) {
        sendProgress(emitter, eventName, data, context, null);
    }

    /**
     * Sends a progress event carrying an SSE {@code id} field.
     *
     * <p>The id makes a stream resumable: a client that reconnects can send
     * {@code Last-Event-ID} and the server skips events it already delivered.
     *
     * @param emitter the SSE emitter
     * @param eventName event name
     * @param data the event data (will be serialized to JSON)
     * @param context human-readable context for warning logs
     * @param id stable event id within the turn, or {@code null} for non-resumable events
     */
    public static void sendProgress(
            SseEmitter emitter, String eventName, Object data, String context, Long id) {
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(eventName);
            if (id != null) {
                event.id(Long.toString(id));
            }
            emitter.send(event.data(data));
        } catch (Exception ex) { // best-effort: client likely disconnected
            log.warn("SSE send failed for {}: {}", context, ex.getMessage());
        }
    }

    /**
     * Sends a "done" event and completes the emitter normally.
     *
     * @param emitter the SSE emitter
     * @param data the final data payload
     */
    public static void sendDone(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().name("done").data(data));
            emitter.complete();
        } catch (Exception ex) { // best-effort: client likely disconnected
            log.warn("SSE done send failed: {}", ex.getMessage());
            emitter.completeWithError(ex);
        }
    }

    /**
     * Sends an "error" event and completes the emitter with the given exception.
     * If the error event itself fails to send, falls back to completeWithError only.
     *
     * @param emitter the SSE emitter
     * @param error the error message
     * @param extra additional fields to include in the error event data
     */
    public static void sendError(SseEmitter emitter, String error, Map<String, Object> extra) {
        try {
            emitter.send(SseEmitter.event().name("error").data(buildErrorData(error, extra)));
            emitter.complete();
        } catch (Exception ex) { // best-effort: error event could not be sent, complete with error as fallback
            emitter.completeWithError(new RuntimeException(error));
        }
    }

    /**
     * Wraps an SSE operation with standard error handling.
     * Handles IllegalArgumentException (bad request) separately from other exceptions.
     *
     * @param emitter the SSE emitter (created via {@link #create()})
     * @param operation the SSE-producing operation (progress events are sent via the callback)
     * @param doneData the data to send with the "done" event
     * @param <T> the type of the done data
     */
    public static <T> void execute(SseEmitter emitter, Consumer<Consumer<Object>> operation, T doneData) {
        try {
            operation.accept((data) -> sendProgress(emitter, "progress", data, "stream"));
            sendDone(emitter, doneData);
        } catch (IllegalArgumentException e) {
            sendError(emitter, e.getMessage(), Map.of());
        } catch (Exception e) { // SSE error: propagate to caller
            emitter.completeWithError(e);
        }
    }

    /**
     * Sends a named SSE event with raw JSON data string.
     * Use for OpenAI-compatible SSE chunk format: {@code data:{"choices":[{"delta":{"content":"..."}}]}}.
     *
     * @param emitter the SSE emitter
     * @param eventName event name (e.g., "chunk", "done"), or null for comment-only
     * @param rawJson raw JSON string to send as data
     * @param context human-readable context for warning logs
     */
    public static void sendRaw(SseEmitter emitter, String eventName, String rawJson, String context) {
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event().data(rawJson);
            if (eventName != null) {
                event.name(eventName);
            }
            emitter.send(event);
        } catch (Exception ex) { // best-effort: client likely disconnected
            log.warn("SSE raw send failed for {}: {}", context, ex.getMessage());
        }
    }

    /**
     * Sends a heartbeat comment to keep the connection alive through proxies/load balancers.
     * Heartbeat is sent as a comment line: {@code : heartbeat\n\n}
     * Proxies may close idle connections after ~60s, so a heartbeat every 30s is recommended.
     *
     * @param emitter the SSE emitter
     */
    public static void sendHeartbeat(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment(": heartbeat"));
        } catch (Exception ex) { // best-effort: heartbeat is optional
            log.warn("SSE heartbeat failed: {}", ex.getMessage());
        }
    }

    /**
     * Escapes a string for safe inclusion in a JSON string value.
     *
     * @param text the raw text
     * @return JSON-escaped text (null-safe)
     */
    public static String escapeJson(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static Map<String, Object> buildErrorData(String error, Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return Map.of("error", error);
        }
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("error", error);
        result.putAll(extra);
        return result;
    }
}
