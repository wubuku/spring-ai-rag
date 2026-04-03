package com.springairag.demo.component;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 组件级集成演示控制器
 *
 * <p>展示两种使用方式：
 *
 * <h3>方式一：ChatClient + RAG Advisors（无对话记忆）</h3>
 * <pre>
 * chatClient.prompt()
 *     .user(message)
 *     .call()
 *     .content();
 * </pre>
 *
 * <h3>方式二：ChatClient + RAG Advisors + Memory（多轮）</h3>
 * <pre>
 * chatClientWithMemory.prompt()
 *     .user(message)
 *     .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
 *     .call()
 *     .content();
 * </pre>
 */
@RestController
@RequestMapping("/demo/component")
public class ComponentLevelController {

    private final ChatClient ragChatClient;
    private final ChatClient ragChatClientWithMemory;

    public ComponentLevelController(
            ChatClient ragChatClient,
            @org.springframework.beans.factory.annotation.Qualifier("ragChatClientWithMemory")
            ChatClient ragChatClientWithMemory) {
        this.ragChatClient = ragChatClient;
        this.ragChatClientWithMemory = ragChatClientWithMemory;
    }

    // ================================================================
    // 方式一：ChatClient + RAG Advisors（无对话记忆）
    // ================================================================

    @GetMapping("/ask")
    public ResponseEntity<String> ask(@RequestParam String q) {
        String answer = ragChatClient.prompt()
                .user(q)
                .call()
                .content();
        return ResponseEntity.ok(answer);
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String sessionId = body.getOrDefault("sessionId", UUID.randomUUID().toString());

        String answer = ragChatClientWithMemory.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        return ResponseEntity.ok(Map.of(
                "answer", answer,
                "sessionId", sessionId,
                "mode", "ChatClient + RAG Advisors + Memory"
        ));
    }

    // ================================================================
    // 对比：带记忆 vs 不带记忆
    // ================================================================

    @PostMapping("/compare-memory")
    public ResponseEntity<Map<String, String>> compareMemory(
            @RequestParam String sessionId,
            @RequestBody Map<String, String> body) {

        String q1 = body.getOrDefault("q1", "我叫张三");
        String q2 = body.getOrDefault("q2", "我叫什么名字？");

        // 第一轮（无记忆）
        String a1 = ragChatClient.prompt().user(q1).call().content();

        // 第二轮（无记忆）
        String a2NoMemory = ragChatClient.prompt().user(q2).call().content();

        // 第二轮（有记忆）
        String a2WithMemory = ragChatClientWithMemory.prompt()
                .user(q2)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        return ResponseEntity.ok(Map.of(
                "q1", q1,
                "a1_no_memory", a1,
                "q2", q2,
                "a2_no_memory", a2NoMemory,
                "a2_with_memory", a2WithMemory,
                "note", "第二轮：有记忆版本应能回答'张三'，无记忆版本无法知道"
        ));
    }
}
