# Contributing to spring-ai-rag

感谢你对 spring-ai-rag 的关注！欢迎以任何形式参与贡献：报告 Bug、提出功能建议、改进文档、提交代码。

## 快速开始

### 1. Fork & Clone

```bash
# Fork 仓库后
git clone https://github.com/<your-username>/spring-ai-rag.git
cd spring-ai-rag
git remote add upstream https://github.com/spring-ai-rag/spring-ai-rag.git
```

### 2. 开发环境

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| JDK | 17+ | 推荐 21 |
| Maven | 3.9+ | 构建工具 |
| PostgreSQL | 15+ | 需安装 `vector` 和 `pg_trgm` 扩展 |
| API Key | LLM 提供商的 Key | OpenAI / DeepSeek / Anthropic 任选 |

### 3. 数据库初始化

```sql
CREATE DATABASE spring_ai_rag;
\c spring_ai_rag
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

Flyway 会自动创建表结构（首次启动时执行 `db/migration/V1__init_rag_schema.sql`）。

### 4. 环境配置

在项目根目录创建 `.env`（已加入 `.gitignore`）：

```bash
# LLM 配置
OPENAI_API_KEY=your-key
OPENAI_BASE_URL=https://api.deepseek.com/v1
LLM_PROVIDER=openai

# 数据库
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DATABASE=spring_ai_rag_dev
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your-password

# 嵌入模型（SiliconFlow）
SILICONFLOW_API_KEY=your-key
```

### 5. 验证环境

```bash
export $(cat .env | grep -v '^#' | xargs)
mvn clean test
```

全部测试通过 = 环境就绪。

## 项目结构

```
spring-ai-rag/
├── spring-ai-rag-api/          # DTO、接口定义
├── spring-ai-rag-core/         # 核心实现（Advisor / Controller / Service）
├── spring-ai-rag-starter/      # Spring Boot 自动配置
├── spring-ai-rag-documents/    # 文档处理（分块、清洗）
└── demos/                      # 示例项目
```

核心包结构（`spring-ai-rag-core`）：

```
com.springairag.core
├── advisor/       # QueryRewrite / HybridSearch / Rerank Advisor
├── controller/    # REST Controller
├── config/        # 配置类
├── entity/        # JPA 实体
├── exception/     # 异常处理
├── extension/     # 领域扩展接口
├── filter/        # 安全过滤器
├── memory/        # 对话记忆
├── metrics/       # 监控指标
├── repository/    # Spring Data JPA
├── retrieval/     # 混合检索核心
├── service/       # 业务服务
├── storage/       # 存储抽象
└── util/          # 工具类
```

## 代码规范

### Java 基本约定

- **Java 17+**，不使用 Lombok（手写 getter/setter/constructor）
- 包名：`com.springairag.*`
- 类名：PascalCase，接口不加 `I` 前缀
- 方法名：camelCase，布尔方法用 `is`/`has`/`can` 前缀
- 常量：UPPER_SNAKE_CASE

### Spring 约定

- Controller 层只做参数校验和响应构造，业务逻辑放 Service
- 用 `@ConfigurationProperties` 管理配置（前缀 `rag.*`）
- 异常用全局 `@ControllerExceptionHandler` 处理，返回统一 `ErrorResponse`
- API 路径统一 `/api/v1/rag/` 前缀

### 注释要求

- **类级 Javadoc**：每个类必须有，说明职责
- **public 方法 Javadoc**：参数、返回值、异常
- **复杂逻辑**：行内注释说明"为什么"，不解释"是什么"
- 不要写无意义注释（`// set name`）

## 测试要求

> **铁律：写生产代码必须同步写测试，两者同等重要。**

### 测试金字塔

| 层级 | 工具 | 比例 | 说明 |
|------|------|------|------|
| 单元测试 | JUnit 5 + Mockito | 70% | 覆盖正常路径和边界情况 |
| 集成测试 | @SpringBootTest | 20% | 组件协作，用真实数据库 |
| E2E 测试 | Shell + curl | 10% | 验证 HTTP 端点完整链路 |

### 运行测试

```bash
# 全量测试
export $(cat .env | grep -v '^#' | xargs) && mvn test

# 单模块测试
export $(cat .env | grep -v '^#' | xargs) && mvn test -pl spring-ai-rag-core

# 单个测试类
export $(cat .env | grep -v '^#' | xargs) && mvn test -pl spring-ai-rag-core -Dtest=RagDocumentControllerTest

# 查看覆盖率报告
mvn clean test jacoco:report
# 报告位置：target/site/jacoco/index.html
```

### 测试规则

- `mvn test` 全部通过才算"完成"
- 测试失败 = 未完成，不提交、不汇报
- 新增 Controller 必须有对应的 Controller 单元测试
- 新增 Service 必须有 Service 单元测试
- 覆率目标：指令覆盖 ≥ 90%

## 提交规范

使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

### Type 说明

| Type | 用途 |
|------|------|
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `docs` | 文档变更 |
| `refactor` | 重构（不改变功能） |
| `test` | 添加/修改测试 |
| `chore` | 构建/工具变更 |
| `perf` | 性能优化 |

### 示例

```
feat(core): 添加检索质量评估服务

- 实现 RetrievalEvaluationService 接口
- 支持 MRR、NDCG、Recall@K 指标
- 新增 12 个单元测试

Closes #42
```

### 提交前检查清单

- [ ] `mvn test` 全部通过
- [ ] 新代码有对应测试
- [ ] 代码符合项目规范（无 Lombok、包名正确）
- [ ] 如有配置变更，同步更新文档
- [ ] Commit message 符合 Conventional Commits 格式

## PR 流程

1. **创建分支**：`git checkout -b feat/your-feature-name`
2. **开发 & 测试**：本地 `mvn test` 通过
3. **保持同步**：`git rebase upstream/main`
4. **推送**：`git push origin feat/your-feature-name`
5. **创建 PR**：填写变更说明，关联 Issue
6. **Code Review**：至少一位维护者 Review 通过
7. **合并**：Squash merge 到 main

### PR 标题

同样使用 Conventional Commits 格式：`feat(core): 添加 XXX 功能`

### PR 描述模板

```markdown
## 变更说明
<!-- 描述你做了什么 -->

## 变更类型
- [ ] 新功能
- [ ] Bug 修复
- [ ] 重构
- [ ] 文档
- [ ] 测试

## 测试
- [ ] `mvn test` 全部通过
- [ ] 新增了 XX 个测试

## 关联 Issue
Closes #
```

## 报告 Bug

在 GitHub Issues 中提交 Bug 报告，包含：

1. **环境信息**：JDK 版本、OS、PostgreSQL 版本
2. **复现步骤**：最小可复现步骤
3. **期望行为**：你期望发生什么
4. **实际行为**：实际发生了什么
5. **日志/截图**：相关错误日志

## 提出功能建议

1. 先搜索已有 Issue，避免重复
2. 说明使用场景（为什么需要这个功能）
3. 描述期望的 API 或交互方式
4. 如果可能，附上替代方案

## 文档贡献

文档与代码同等重要。发现文档问题可以直接提交 PR：

- 修正错别字或错误链接
- 补充缺失的使用说明
- 改进示例代码

文档位于：
- `README.md` — 项目门面
- `docs/` — 详细文档
- 代码 Javadoc — API 文档

## 问题？

- GitHub Issues：报告 Bug / 提功能建议
- 代码问题：在 PR 中讨论

---

再次感谢你的贡献！每一个 Issue、PR 和 Star 都是对项目的支持。
