package com.springairag.demo;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.config.RagChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Demo controller — shows how to use RagChatService in your own application.
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private final RagChatService ragChatService;

    public DemoController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    /**
     * Simplest Q&amp;A: just one line of code.
     */
    @GetMapping("/ask")
    public ResponseEntity<String> quickAsk(@RequestParam String q) {
        // Core call: pass in the question, get the answer back.
        String answer = ragChatService.chat(q, UUID.randomUUID().toString());
        return ResponseEntity.ok(answer);
    }

    /**
     * Full Q&amp;A: uses ChatRequest, supports domain extensions and source citations.
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> fullChat(@RequestBody Map<String, String> body) {
        ChatRequest request = new ChatRequest();
        request.setMessage(body.get("message"));
        request.setSessionId(body.getOrDefault("sessionId", UUID.randomUUID().toString()));
        request.setDomainId(body.get("domainId"));  // Optional: domain extension

        ChatResponse response = ragChatService.chat(request);
        return ResponseEntity.ok(response);
    }
}
