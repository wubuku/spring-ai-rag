package com.springairag.demo.component;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 组件级集成演示控制器
 *
 * <p>展示两种使用方式：
 *
 * <h3>方式一：纯 ChatClient + RAG Advisors</h3>
 * <pre>
 * // 已有 ChatClient，把 RAG Advisors 挂上去，自动经过：
 * // QueryRewrite → HybridSearch → Rerank → LLM
 * String answer = ragChatClient.prompt()
 *     .user(message)
 *     .call()
 *     .content();
 * </pre>
 *
 * <h3>方式二：ChatClient + RAG Advisors + Memory（多轮对话）</h3>
 * <pre>
 * // 加上对话记忆 Advisor，支持多轮
 * String answer = ragChatClientWithMemory.prompt()
 *     .user(message)
 *     .param("sessionId", sessionId)
 *     .call()
 *     .content();
 * </pre>
 */
@RestController
@RequestMapping("/demo/component")
public class ComponentLevelController {

    // 方式一：纯 ChatClient + RAG Advisors（不带对话记忆）
    private final ChatClient ragChatClient;

    // 方式二：ChatClient + RAG Advisors + Memory（带对话记忆）
    private final ChatClient ragChatClientWithMemory;

    public ComponentLevelController(
            ChatClient ragChatClient,
            @Qualifier("ragChatClientWithMemory") ChatClient ragChatClientWithMemory) {
        this.ragChatClient = ragChatClient;
        this.ragChatClientWithMemory = ragChatClientWithMemory;
    }

    // ================================================================
    // 方式一：纯 ChatClient + RAG Advisors
    // ================================================================

    /**
     * 简单问答（GET，适合快速测试）
     */
    @GetMapping("/ask")
    public ResponseEntity<String> ask(@RequestParam String q) {
        String answer = ragChatClient.prompt()
                .user(q)
                .call()
                .content();
        return ResponseEntity.ok(answer);
    }

    /**
     * POST 问答（支持传入 sessionId）
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String sessionId = body.getOrDefault("sessionId", UUID.randomUUID().toString());

        String answer = ragChatClientWithMemory.prompt()
                .user(message)
                .param("sessionId", sessionId)
                .call()
                .content();

        return ResponseEntity.ok(Map.of(
                "answer", answer,
                "sessionId", sessionId,
                "mode", "ChatClient + RAG Advisors + Memory"
        ));
    }

    // ================================================================
    // 对比端点：展示不同模式的行为
    // ================================================================

    /**
     * 对比：带记忆 vs 不带记忆（同一问题的多轮对话）
     */
    @PostMapping("/compare-memory")
    public ResponseEntity<Map<String, String>> compareMemory(
            @RequestParam String sessionId,
            @RequestBody Map<String, String> body) {

        String q1 = body.getOrDefault("q1", "我叫张三");
        String q2 = body.getOrDefault("q2", "我叫什么名字？");

        // 第一轮（无记忆）
        String a1 = ragChatClient.prompt().user(q1).call().content();

        // 第一轮（有记忆）
        String a1WithMemory = ragChatClientWithMemory.prompt()
                .user(q1).param("sessionId", sessionId).call().content();

        // 第二轮（无记忆 — LLM 不知道之前说了什么）
        String a2NoMemory = ragChatClient.prompt().user(q2).call().content();

        // 第二轮（有记忆 — 能记住"我叫张三"）
        String a2WithMemory = ragChatClientWithMemory.prompt()
                .user(q2).param("sessionId", sessionId).call().content();

        return ResponseEntity.ok(Map.of(
                "q1", q1,
                "a1_no_memory", a1,
                "a1_with_memory", a1WithMemory,
                "q2", q2,
                "a2_no_memory", a2NoMemory,
                "a2_with_memory", a2WithMemory,
                "note", "第二轮中，有记忆的版本应该能记住用户名字，无记忆版本则不能"
        ));
    }
}
