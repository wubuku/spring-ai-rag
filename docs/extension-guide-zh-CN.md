# 领域扩展指南

> [English](extension-guide.md) | [中文](extension-guide-zh-CN.md)

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
| `DomainRagExtension` | 模式安全的领域 Prompt + 检索配置 | 是 |
| `PromptCustomizer` | 细粒度 Prompt 定制（链式调用） | 可选 |
| `RagAdvisorProvider` | 按模式和作用域注入 Spring AI Advisor | 可选 |

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
                你是医疗健康助手。

                规则：
                1. 明确标注"仅供参考，不构成医疗建议"
                2. 涉及用药建议时，提醒用户咨询专业医生
                """;
    }

    @Override
    public String getSystemPromptTemplate(ChatMode mode) {
        return getSystemPromptTemplate();
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

}
```

### 2. 注册即生效

`@Component` 注解 → Spring Boot 自动发现 → `DomainExtensionRegistry` 自动注册。

无需额外配置。

### 3. 调用时指定 domainId

```bash
curl -X POST http://localhost:8081/api/v1/rag/chat/ask \
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
| `getSystemPromptTemplate()` | ✅ | 兼容入口；只写角色、安全和风格 instruction，不拼接检索上下文 |
| `getSystemPromptTemplate(ChatMode)` | | 为 `KNOWLEDGE`、`AGENT`、`PLAIN` 返回模式安全 instruction |
| `getRetrievalConfig()` | | 检索配置（默认：10 结果，0.5 阈值，混合检索） |
| `postProcessAnswer()` | deprecated | 新 Chat 主链不调用；真流式无法安全执行整段答案后处理 |
| `isApplicable()` | deprecated | 新 Chat 只使用调用者显式提供的 `domainId`，不做隐式领域分类 |

`CitationQueryAugmenter` 和 `KnowledgeSearchTool` 负责注入证据，因此新模板不要包含
`{context}`。旧模板仍可用于 `KNOWLEDGE`；在 `AGENT/PLAIN` 中会返回
`DOMAIN_MODE_UNSUPPORTED`，直到扩展覆盖模式感知方法。

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

请求时通过 `domainId` 显式选择。未指定时使用通用 Chat 默认值，不使用“第一个注册的
扩展”；未知 ID 返回 `UNKNOWN_DOMAIN`。

---

## 扩展与 RAG Pipeline 的协作

```
请求 → ChatCommandMapper（合并显式领域 RetrievalConfig）
    → ChatExecutionService
      → KNOWLEDGE：RetrievalAugmentationAdvisor + 项目检索
      → AGENT：ToolCallAdvisor + 授权 searchKnowledge
      → PLAIN：ChatClient + Memory
    → 模式 instruction + 领域 instruction
    → PromptCustomizer 链
    → ChatClient.call/stream()
    → 响应
```

领域扩展影响 2 个环节：
1. **检索阶段**：`getRetrievalConfig()` 控制检索参数
2. **Prompt 组装**：模式感知 Prompt 方法提供领域 instruction

### RagAdvisorProvider

旧 provider 默认是 `KNOWLEDGE + ATTEMPT`。需要在其他模式运行时显式声明：

```java
@Override
public Set<ChatMode> supportedModes() {
    return Set.of(ChatMode.KNOWLEDGE, ChatMode.PLAIN);
}

@Override
public AdvisorScope advisorScope() {
    return AdvisorScope.ATTEMPT;
}
```

- `ATTEMPT`：每个模型候选/重试执行一次，位于 Memory 和模式 Advisor 外层。
- `MODEL_CALL`：位于模式 Advisor 内层；在 AGENT 工具循环中每轮模型调用都会执行，
  只适合可重复、幂等且不写 turn 级状态的 Advisor。
- `getOrder()` 只决定同一作用域内 provider 的相对顺序；框架会映射到稳定且不重叠的
  order band。

---

## 最佳实践

### Prompt 模板

- 不包含 `{context}`；RAG/Tool Calling 负责注入证据
- 明确角色定义和回答规则
- 对三种 Chat 模式都安全；只在确有差异时覆盖模式感知方法

### 检索配置

- 医疗/法律等专业领域：提高 `minScore`（0.6+）确保准确性
- 客服/FAQ 场景：降低 `minScore`（0.3）提高召回率
- 长文档领域：增加 `maxResults`（15+）提供更完整上下文

医疗、法律等免责声明应写入领域 system instruction 或流式安全的 Advisor，不要依赖
deprecated 的整段答案后处理。

---

## 更多信息

- [架构设计](architecture-zh-CN.md) — RAG Pipeline 和模块结构
- [配置参考](configuration-zh-CN.md) — 检索参数配置
- [REST API 参考](rest-api-zh-CN.md) — API 端点文档
