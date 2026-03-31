# Demo: 领域扩展（Domain Extension）

展示如何通过 `DomainRagExtension` + `PromptCustomizer` 为通用 RAG 框架添加特定领域能力。

本示例以**医疗问诊**为例，但相同的模式适用于任何领域。

## 三步添加新领域

### 第 1 步：实现 DomainRagExtension

```java
@Component
public class MedicalRagExtension implements DomainRagExtension {

    @Override
    public String getDomainId() {
        return "medical";  // 领域唯一标识
    }

    @Override
    public String getSystemPromptTemplate() {
        return """
            你是一个专业的医疗问诊助手...
            参考资料：{context}
            """;  // 使用 {context} 占位符
    }

    @Override
    public RetrievalConfig getRetrievalConfig() {
        return RetrievalConfig.builder()
            .maxResults(15)     // 高召回率
            .minScore(0.3)
            .build();
    }
}
```

### 第 2 步（可选）：实现 PromptCustomizer

```java
@Component
public class MedicalPromptCustomizer implements PromptCustomizer {

    @Override
    public String customizeUserMessage(String original, Map<String, Object> metadata) {
        if ("medical".equals(metadata.get("domainId"))) {
            return "[问诊模式] 用户咨询：" + original;
        }
        return original;
    }
}
```

### 第 3 步：调用时传入 domainId

```java
// 方式 1：通过 ChatRequest
ChatRequest request = new ChatRequest();
request.setMessage("头疼怎么办");
request.setDomainId("medical");  // 指定领域
ChatResponse response = ragChatService.chat(request);

// 方式 2：简洁调用
String answer = ragChatService.chat("头疼怎么办", sessionId, "medical", null);
```

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/medical/consult` | 医疗问诊（完整模式，含引用来源） |
| GET | `/api/v1/medical/quick?q=头疼` | 快速问诊（简版接口） |
| POST | `/api/v1/medical/general` | 普通问答（不使用领域扩展，对比效果） |

### 请求示例

```bash
# 医疗问诊
curl -X POST http://localhost:8081/api/v1/medical/consult \
  -H "Content-Type: application/json" \
  -d '{"message": "最近总是头疼，特别是下午，是怎么回事？"}'

# 快速问诊
curl "http://localhost:8081/api/v1/medical/quick?q=发烧38.5度怎么办"

# 对比：普通问答（无领域扩展）
curl -X POST http://localhost:8081/api/v1/medical/general \
  -H "Content-Type: application/json" \
  -d '{"message": "头疼是怎么回事？"}'
```

## 前置条件

与 [demo-basic-rag](../demo-basic-rag) 相同：
- PostgreSQL + pgvector
- DeepSeek API Key
- SiliconFlow API Key（嵌入模型）

## 启动

```bash
# 先构建主项目并安装到本地仓库
cd /path/to/spring-ai-rag
mvn clean install -DskipTests

# 启动 demo
cd demos/demo-domain-extension
mvn spring-boot:run -Dspring-boot.run.arguments="--DEEPSEEK_API_KEY=sk-xxx --SILICONFLOW_API_KEY=sk-xxx"
```

## 扩展更多领域

添加新领域只需：
1. 新建 `XxxRagExtension implements DomainRagExtension`
2. 添加 `@Component` 注解
3. 调用时传入对应的 `domainId`

不需要修改任何框架代码。每个领域扩展独立开发、独立测试、独立部署。

## 扩展机制说明

| 组件 | 作用 | 自动发现 |
|------|------|----------|
| `DomainRagExtension` | 领域 Prompt 模板 + 检索配置 | @Component 自动注册 |
| `PromptCustomizer` | Prompt 链式定制 | @Component 自动注册 |
| `RagAdvisorProvider` | 自定义 Advisor 注入 | @Component 自动注册 |

多个实现按 `getOrder()` 排序，Starter 自动扫描并组装。
