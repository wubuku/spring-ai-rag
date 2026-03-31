package com.springairag.core.controller;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.config.RagChatService;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * RAG 聊天控制器
 *
 * <p>提供非流式和流式（SSE）两种问答接口，以及会话历史管理。
 */
@RestController
@RequestMapping("/api/v1/rag/chat")
public class RagChatController {

    private static final Logger log = LoggerFactory.getLogger(RagChatController.class);

    private final RagChatService ragChatService;
    private final RagChatHistoryRepository historyRepository;

    public RagChatController(RagChatService ragChatService, RagChatHistoryRepository historyRepository) {
        this.ragChatService = ragChatService;
        this.historyRepository = historyRepository;
    }

    /**
     * RAG 问答（非流式）
     */
    @PostMapping("/ask")
    public ResponseEntity<ChatResponse> ask(@RequestBody ChatRequest request) {
        log.info("RAG ask: sessionId={}, message={}", request.getSessionId(),
                request.getMessage().length() > 100 ? request.getMessage().substring(0, 100) + "..." : request.getMessage());

        ChatResponse response = ragChatService.chat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * RAG 问答（流式，SSE）
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        log.info("RAG stream: sessionId={}, message={}", request.getSessionId(),
                request.getMessage().length() > 100 ? request.getMessage().substring(0, 100) + "..." : request.getMessage());

        SseEmitter emitter = new SseEmitter(0L); // 无超时

        ragChatService.chatStream(request.getMessage(), request.getSessionId())
                .subscribe(
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        () -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        }
                );

        return emitter;
    }

    /**
     * 获取会话历史
     */
    @GetMapping("/history/{sessionId}")
    public ResponseEntity<List<Map<String, Object>>> getHistory(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> history = historyRepository.findBySessionId(sessionId, limit);
        return ResponseEntity.ok(history);
    }

    /**
     * 清空会话历史
     */
    @DeleteMapping("/history/{sessionId}")
    public ResponseEntity<Map<String, String>> clearHistory(@PathVariable String sessionId) {
        // 注意：只清理业务审计表，spring_ai_chat_memory 由 Spring AI 管理
        log.info("Clearing chat history for session: {}", sessionId);
        // RagChatHistoryRepository 暂无 delete 方法，返回提示
        return ResponseEntity.ok(Map.of(
                "message", "业务审计历史暂不支持删除。spring_ai_chat_memory 由 Spring AI 管理。",
                "sessionId", sessionId
        ));
    }
}
