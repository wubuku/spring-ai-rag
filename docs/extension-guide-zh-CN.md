# 领域扩展指南

> 📖 English | 📖 中文

> spring-ai-rag 的核心设计理念：**领域解耦**。一个通用 RAG 引擎支撑 N 个垂直领域。

---

## 扩展机制概览

```
用户请求(domainId=medical)
    ↓
DomainExtensionRegistry → 找到 MedicalDomainExtension
    ↓
注入领域 System Prompt + 领域检索配置
    ↓
RAG Pipeline 正常执行
```

扩展点：

| 接口 | 用途 | 必须实现 |
|------|------|---------|
| `DomainRagExtension` | 领域 Prompt + 检索配置 + 答案后处理 | ✅ |
| `PromptCustomizer` | 细粒度 Prompt 定制（链式调用） | 可选 |

---

## 快速开始：3 步接入领域

### 1. 实现 DomainRagExtension

```java
@Component
public class MedicalDomainExtension implements DomainRagExtension {

    @Override
    public String getDomainId() {
        return "medical";
    }

    @Override
    public String getDomainName() {
        return "医疗健康";
    }

    @Override
    public String getSystemPromptTemplate() {
        return """
                你是医疗健康知识助手。请基于检索到的医学文献回答用户问题。

                规则：
                1. 严格依据参考资料，不编造医学信息
                2. 明确标注"仅供参考，不构成医疗建议"
                3. 涉及用药建议时，提醒用户咨询专业医生

                参考资料：
                {context}
                """;
    }

    @Override
    public RetrievalConfig getRetrievalConfig() {
        return RetrievalConfig.builder()
                .maxResults(15)        // 医疗领域需要更多上下文
                .minScore(0.6)         // 更高的相似度阈值
                .useHybridSearch(true)
                .useRerank(true)
                .vectorWeight(0.7)     // 偏向语义检索
                .fulltextWeight(0.3)
                .build();
    }

    @Override
    public String postProcessAnswer(String answer) {
        // 自动追加免责声明
        if (!answer.contains("免责声明")) {
            return answer + "\n\n---\n⚠️ 免责声明：以上内容仅供参考，不构成医疗建议。"
                    + " 如有健康问题，请咨询专业医生。";
        }
        return answer;
    }

    @Override
    public boolean isApplicable(String query) {
        // 简单关键词判断（生产环境可用 NLP 分类器）
        String lower = query.toLowerCase();
        return lower.contains("病") || lower.contains("症状")
                || lower.contains("药") || lower.contains("治疗")
                || lower.contains("医") || lower.contains("健康");
    }
}
```

### 2. 注册即生效

`@Component` 注解 → Spring Boot 自动发现 → `DomainExtensionRegistry` 自动注册。

无需额外配置。

### 3. 调用时指定 domainId

```bash
curl -X POST http://localhost:8080/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{
    "message": "高血压有什么症状？",
    "sessionId": "s1",
    "domainId": "medical"
  }'
```

---

## 接口详解

### DomainRagExtension

| 方法 | 必须 | 说明 |
|------|------|------|
| `getDomainId()` | ✅ | 领域唯一标识（英文，如 `medical`、`legal`） |
| `getDomainName()` | ✅ | 领域显示名称（如"医疗健康"） |
| `getSystemPromptTemplate()` | ✅ | 系统提示词模板，`{context}` 占位符会被替换为检索结果 |
| `getRetrievalConfig()` | | 检索配置（默认：10 结果，0.5 阈值，混合检索） |
| `postProcessAnswer()` | | 答案后处理（默认：原样返回） |
| `isApplicable()` | | 查询领域判断（默认：全部接受） |

### PromptCustomizer

用于更细粒度的 Prompt 控制，多个实现按 `getOrder()` 排序链式调用。

```java
@Component
public class SafetyPromptCustomizer implements PromptCustomizer {

    @Override
    public String customizeSystemPrompt(String originalSystemPrompt,
                                         String context,
                                         Map<String, Object> metadata) {
        return originalSystemPrompt + "\n\n请确保回答符合安全准则，不包含有害内容。";
    }

    @Override
    public int getOrder() {
        return 100; // 最后执行
    }
}
```

---

## 多领域并存

一个服务可以注册多个领域扩展：

```java
@Configuration
public class DomainConfig {

    @Bean
    public DomainRagExtension medicalExtension() {
        return new MedicalDomainExtension();
    }

    @Bean
    public DomainRagExtension legalExtension() {
        return new LegalDomainExtension();
    }

    @Bean
    public DomainRagExtension financeExtension() {
        return new FinanceDomainExtension();
    }
}
```

请求时通过 `domainId` 选择，未指定时使用第一个注册的扩展。

---

## 扩展与 RAG Pipeline 的协作

```
请求 → QueryRewriteAdvisor（查询改写，不感知领域）
    → HybridSearchAdvisor（混合检索，使用领域 RetrievalConfig）
    → RerankAdvisor（重排序，使用领域 RetrievalConfig）
    → 组装 Prompt（领域 System Prompt + 检索上下文）
    → PromptCustomizer 链（细粒度定制）
    → ChatClient.call/stream()
    → DomainRagExtension.postProcessAnswer()
    → 响应
```

领域扩展影响 3 个环节：
1. **检索阶段**：`getRetrievalConfig()` 控制检索参数
2. **Prompt 组装**：`getSystemPromptTemplate()` 提供领域 Prompt
3. **结果处理**：`postProcessAnswer()` 后处理答案

---

## 最佳实践

### Prompt 模板

- 包含 `{context}` 占位符，RAG 会替换为检索到的文档片段
- 明确角色定义和回答规则
- 控制在 20 行以内，复杂逻辑放 `postProcessAnswer()`

### 检索配置

- 医疗/法律等专业领域：提高 `minScore`（0.6+）确保准确性
- 客服/FAQ 场景：降低 `minScore`（0.3）提高召回率
- 长文档领域：增加 `maxResults`（15+）提供更完整上下文

### 答案后处理

- 追加免责声明（医疗、法律领域）
- 格式化输出（添加 Markdown 结构）
- 质量校验（检查关键信息是否缺失）

---

## 更多信息

- [架构设计](architecture.md) — RAG Pipeline 和模块结构
- [配置参考](configuration.md) — 检索参数配置
- [REST API 参考](rest-api.md) — API 端点文档
