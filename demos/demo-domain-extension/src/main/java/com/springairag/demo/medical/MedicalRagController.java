package com.springairag.demo.medical;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.config.RagChatService;
import com.springairag.core.versioning.ApiVersion;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 医疗问诊控制器 — 展示领域扩展的使用方式
 *
 * <p>通过 domainId="medical" 指定医疗问诊领域，
 * RagChatService 会自动加载 MedicalRagExtension 的系统提示词和检索配置，
 * 并经过 MedicalPromptCustomizer 处理用户消息。
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/medical")
public class MedicalRagController {

    private static final String DOMAIN_ID = "medical";

    private final RagChatService ragChatService;

    public MedicalRagController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    /**
     * 医疗问诊（完整模式）
     *
     * <p>使用 domainId="medical" 启用医疗领域扩展：
     * <ol>
     *   <li>MedicalRagExtension 提供专业问诊系统提示词</li>
     *   <li>MedicalPromptCustomizer 格式化用户问题</li>
     *   <li>检索配置自动切换为医疗领域模式（高召回率）</li>
     * </ol>
     *
     * <p>请求示例：
     * <pre>
     * POST /api/v1/medical/consult
     * {
     *   "message": "最近总是头疼，特别是下午的时候",
     *   "sessionId": "optional-session-id"
     * }
     * </pre>
     */
    @PostMapping("/consult")
    public ResponseEntity<ChatResponse> consult(@RequestBody Map<String, String> body) {
        ChatRequest request = new ChatRequest();
        request.setMessage(body.get("message"));
        request.setSessionId(body.getOrDefault("sessionId", UUID.randomUUID().toString()));
        request.setDomainId(DOMAIN_ID);

        ChatResponse response = ragChatService.chat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 快速问诊（简版接口）
     *
     * <p>GET /api/v1/medical/quick?q=头疼怎么办
     *
     * <p>等价于完整模式，但参数更简单，适合前端快速调用。
     */
    @GetMapping("/quick")
    public ResponseEntity<String> quickConsult(@RequestParam String q) {
        String sessionId = UUID.randomUUID().toString();
        String answer = ragChatService.chat(q, sessionId, DOMAIN_ID, null);
        return ResponseEntity.ok(answer);
    }

    /**
     * 普通问答（不使用领域扩展）
     *
     * <p>对比展示：不传 domainId 时，使用默认的通用 RAG 配置。
     * 可以和 /consult 接口对比效果差异。
     */
    @PostMapping("/general")
    public ResponseEntity<ChatResponse> generalAsk(@RequestBody Map<String, String> body) {
        ChatRequest request = new ChatRequest();
        request.setMessage(body.get("message"));
        request.setSessionId(body.getOrDefault("sessionId", UUID.randomUUID().toString()));
        // 不设置 domainId，使用默认领域扩展

        ChatResponse response = ragChatService.chat(request);
        return ResponseEntity.ok(response);
    }
}
