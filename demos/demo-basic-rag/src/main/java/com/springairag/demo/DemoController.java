package com.springairag.demo;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.config.RagChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Demo 控制器 — 展示如何在自己的应用中使用 RagChatService
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private final RagChatService ragChatService;

    public DemoController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    /**
     * 最简单的问答：只需一行代码
     */
    @GetMapping("/ask")
    public ResponseEntity<String> quickAsk(@RequestParam String q) {
        // 核心调用：传入问题，返回回答
        String answer = ragChatService.chat(q, UUID.randomUUID().toString());
        return ResponseEntity.ok(answer);
    }

    /**
     * 完整问答：使用 ChatRequest，支持领域扩展和引用来源
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> fullChat(@RequestBody Map<String, String> body) {
        ChatRequest request = new ChatRequest();
        request.setMessage(body.get("message"));
        request.setSessionId(body.getOrDefault("sessionId", UUID.randomUUID().toString()));
        request.setDomainId(body.get("domainId"));  // 可选：领域扩展

        ChatResponse response = ragChatService.chat(request);
        return ResponseEntity.ok(response);
    }
}
