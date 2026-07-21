# 测试指南

> 📖 [English](testing-guide.md) · 📖 [中文](testing-guide-zh-CN.md)

Spring AI RAG 项目对测试的态度是"测试是生产代码"——写代码必须同步写测试，`mvn test` 不通过就不算完成。

> **永久规则**（来自项目要求，不可弱化）：  
> - 写生产代码必须同步写测试，两者同等重要  
> - `mvn test` 全部通过才算「完成」  
> - 有 REST 端点改动后运行 E2E（`scripts/e2e-test.sh`）  
> - 涉及 WebUI 修改时必须跑 Playwright（`scripts/webui-e2e-test.js` / `npm run test:e2e`）  
> - 重要改进后：重启服务 → 确认 `http://localhost:8081` 可用 → 再跑回归  

总文档导航：[index-zh-CN.md](index-zh-CN.md) · 命令速查：[../TOOLS.md](../TOOLS.md)

## 测试金字塔

```
    ┌──────────┐
    │  E2E 测试  │  scripts/e2e-test.sh
    ├──────────┤
    │ 集成测试    │  @SpringBootTest
    ├──────────┤
    │ 单元测试    │  JUnit 5 + Mockito
    └──────────┘
```

## 快速开始

```bash
# 运行全部单元测试 + 集成测试
export $(cat .env | grep -v '^#' | xargs) && mvn test

# 只测试特定模块
mvn test -pl spring-ai-rag-core

# 只运行某个测试类
mvn test -pl spring-ai-rag-core -Dtest=RagDocumentControllerTest

# 跳过测试构建
mvn clean package -DskipTests
```

## 一键发布验证

`scripts/verify-release.sh` 固化 1.0 发布门禁，包括内嵌 WebUI 入口引用、资源存在性与 Git 可跟踪性检查，并将每一步的 stdout/stderr、状态表和 Markdown 汇总写入 `target/release-verification/<run-id>/`：

```bash
# 默认：静态检查、Maven、WebUI、Playwright、Helm、Docker
./scripts/verify-release.sh

# 已有 node_modules 时可省略 npm ci
./scripts/verify-release.sh --skip-npm-ci

# 对已启动的 PostgreSQL profile 服务追加 HTTP E2E 与 goldenset
BASE_URL=http://127.0.0.1:8081 \
  ./scripts/verify-release.sh --with-runtime-e2e --with-goldenset

# 真实 LLM 服务通常由 scripts/start-real-e2e-server.sh 启动在 18081
./scripts/verify-release.sh --with-real-llm

# 完整本地门禁：自动启动 postgresql profile 服务，执行 HTTP E2E、
# goldenset 与真实 LLM smoke，归档日志后停止该服务
./scripts/verify-release.sh --with-local-runtime
```

`--with-local-runtime` 需要 PostgreSQL/pgvector 已运行，且 `.env` 中存在可用的数据库、Embedding 与 Chat LLM 配置；默认独占端口 `18081`，端口被占用时会失败，避免复用或误杀非本脚本启动的服务。可用 `RUNTIME_SERVER_PORT` 改端口。无论成功、失败或中断，脚本都会归档日志并清理自己启动的服务。

Docker 默认先用中国境内镜像并自动回退官方源；详见 [中国境内开发网络避坑指南](china-network-guide-zh-CN.md)。外部服务失败必须保留为失败或明确跳过，不能伪造发布通过。

## 测试分类

### 单元测试（JUnit 5 + Mockito）

**目标**：验证单个类/方法的逻辑，不依赖外部服务。

**命名规范**：`{ClassName}Test.java`

**位置**：各模块的 `src/test/java/`

**示例**：
```java
@SpringBootTest
@AutoConfigureMockMvc
class RagDocumentControllerTest {

    @MockBean
    private RagDocumentService ragDocumentService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnDocumentById() throws Exception {
        when(ragDocumentService.findById(1L)).thenReturn(Optional.of(doc));

        mockMvc.perform(get("/api/v1/rag/documents/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Test"));
    }
}
```

**Mock 要点**：
- 使用 `@MockBean` 替代 `@Mock`（Spring 上下文集成）
- Service 层可以 `@Mock` + `@ExtendWith(MockitoExtension.class)` 纯单元测试
- 涉及数据库的用 `@DataJpaTest` 切片测试

### 集成测试（@SpringBootTest）

**目标**：验证组件协作，用真实 Spring 上下文。

**命名规范**：`{ClassName}IntegrationTest.java`

**关键注解**：
```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers   // 如果需要 PostgreSQL
class RagChatControllerIntegrationTest {
    // 测试完整的 RAG Pipeline：查询 → 改写 → 检索 → 重排 → LLM
}
```

### E2E 测试（Shell + curl）

**目标**：验证 HTTP 端点完整链路（真实服务运行）。

**脚本**：`scripts/e2e-test.sh`

**用法**：
```bash
# 启动服务
export $(cat .env | grep -v '^#' | xargs) && bash scripts/start-server.sh

# 在另一个终端运行 E2E 测试
export $(cat .env | grep -v '^#' | xargs) && bash scripts/e2e-test.sh
```

E2E 测试覆盖的端点：
1. `GET /api/v1/rag/health` — 健康检查
2. `POST /api/v1/rag/documents` — 创建文档
3. `GET /api/v1/rag/documents/{id}` — 获取文档（含 JSONB metadata）
4. `GET /api/v1/rag/documents` — 文档列表（分页）
5. `POST /api/v1/rag/documents/{id}/embed` — 生成嵌入向量
6. `GET /api/v1/rag/search` — 直接检索
7. `POST /api/v1/rag/chat/ask` — RAG 问答
8. `POST /api/v1/rag/chat/stream` — 流式响应（SSE）
9. `GET /api/v1/rag/chat/history/{sessionId}` — 对话历史
10. `DELETE /api/v1/rag/documents/{id}` — 删除文档 + 验证 404

## 覆盖率

JaCoCo 已集成到所有模块：

```bash
# 生成覆盖率报告
mvn clean test jacoco:report

# 报告位置
# spring-ai-rag-core/target/site/jacoco/index.html
# spring-ai-rag-api/target/site/jacoco/index.html
# spring-ai-rag-documents/target/site/jacoco/index.html
# spring-ai-rag-starter/target/site/jacoco/index.html
```

**覆盖率目标**：
- 指令覆盖率 ≥ 90%
- 分支覆盖率 ≥ 75%

**查看覆盖率**：
```bash
# 快速查看（终端输出）
mvn jacoco:check

# 合并多模块报告
mvn jacoco:report-aggregate
```

## 测试数据库

单元测试使用 H2 内存数据库（默认），集成测试可用 Testcontainers：

```java
@Testcontainers
@SpringBootTest
class PgVectorStoreIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
    }
}
```

## 编写新测试的规则

1. **先写测试再写实现**（TDD 友好）
2. **每个 public 方法至少一个正向测试 + 一个边界测试**
3. **Controller 测试**用 `MockMvc`，不启动真实 HTTP
4. **Service 测试**用 `@Mock` 纯单元测试或 `@SpringBootTest` 集成测试
5. **不要用 `@Ignore` 跳过测试**——修好它或删掉它
6. **测试名称用 `should_描述预期行为` 格式**
7. **每个测试独立**——不依赖执行顺序

## 性能基准测试

`RetrievalBenchmarkTest` 验证核心操作在合理时间内完成：

| 操作 | 目标 | 实测 |
|------|------|------|
| 向量检索 | < 500ms | ~1.9ms |
| 融合检索 | < 500ms | ~6ms |
| Cosine 计算 (10万次) | < 200ms | ~75ms |

## 常见问题

### `mvn test` 报错 "Connection refused"

PostgreSQL 未启动或 `.env` 未加载：

```bash
# 确认 PostgreSQL 运行
pg_isready -h localhost -p 5432

# 加载环境变量
export $(cat .env | grep -v '^#' | xargs)
```

### 嵌入模型测试太慢

嵌入模型调用（SiliconFlow API）需要网络，在 CI 中可以 Mock：

```java
@MockBean
private EmbeddingModel embeddingModel;

@BeforeEach
void setup() {
    when(embeddingModel.embed(any(String.class)))
        .thenReturn(new float[1024]); // 返回固定向量
}
```

### JaCoCo 覆盖率不准确

```bash
# 清理后重新测试
mvn clean test jacoco:report
```
