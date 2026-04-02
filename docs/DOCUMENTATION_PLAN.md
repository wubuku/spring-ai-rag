# 文档体系建设规划

> 目标：按顶级开源项目标准，为 spring-ai-rag 建立完整、高质量的文档体系。
> 原则：文档即代码，与代码同步维护，CI 可验证。

---

## 一、现状评估

| 维度 | 现状 | 评分 |
|------|------|------|
| README.md | 已有，内容中等，含快速开始/架构/API 示例 | ⭐⭐⭐ |
| 部署文档 | docs/DEPLOYMENT.md 已有，较完整 | ⭐⭐⭐⭐ |
| API 文档 | 依赖 SpringDoc/Swagger UI 自动生成，运行时可访问 | ⭐⭐⭐ |
| 架构文档 | 仅有 README 中的简单描述 | ⭐ |
| 贡献指南 | 缺失 | ❌ |
| 开发者指南 | 缺失 | ❌ |
| 配置参考 | 仅有 application.yml，无独立文档 | ⭐ |
| 扩展指南 | 仅有 README 中一段代码示例 | ⭐ |
| 测试指南 | 缺失 | ❌ |
| 变更日志 | 缺失 | ❌ |
| 故障排查 | 缺失 | ❌ |

**总评**：核心代码质量高，但文档体系严重不足。开源用户看到的是"半成品"。

---

## 二、目标文档结构

```
spring-ai-rag/
├── README.md                          # 🔄 重写：项目门面，5 分钟了解项目
├── CONTRIBUTING.md                    # 🆕 贡献指南
├── CHANGELOG.md                       # 🆕 版本变更日志
├── LICENSE                            # ✅ 已有 (Apache 2.0)
│
├── docs/
│   ├── index.md                       # 🆕 文档导航索引
│   ├── architecture.md                # 🆕 架构设计详解
│   ├── getting-started.md             # 🆕 开发者快速上手（面向贡献者）
│   ├── configuration.md               # 🆕 完整配置参考
│   ├── rest-api.md                    # 🆕 REST API 完整参考
│   ├── extension-guide.md             # 🆕 领域扩展开发指南
│   ├── testing-guide.md               # 🆕 测试指南
│   ├── deployment.md                  # ✅ 已有，需完善
│   ├── troubleshooting.md             # 🆕 故障排查手册
│   └── IMPLEMENTATION_COMPARISON.md   # ✅ 已有
│
└── .github/
    ├── PULL_REQUEST_TEMPLATE.md       # 🆕 PR 模板
    └── ISSUE_TEMPLATE/                # 🆕 Issue 模板
        ├── bug_report.md
        └── feature_request.md
```

---

## 三、每份文档详细规格

### 3.1 README.md — 重写

**目标**：5 分钟让访客了解项目价值、能力、如何用。对标 Spring Boot Starter README。

**结构**：
1. 项目简介（一句话定位 + 三个关键词）
2. 功能特性列表（精简，每个一行）
3. 快速开始（3 步走：加依赖→配数据库→启动调用）
4. 架构概览图（Pipeline 流程图，可选 Mermaid）
5. 为什么选这个框架？（vs 直接用 LangChain / Spring AI 原生）
6. 文档导航链接
7. License

**要求**：≤ 200 行，不包含详细配置、不展开 API 细节（链接到 docs/）。

### 3.2 CONTRIBUTING.md — 贡献指南

**结构**：
1. 欢迎贡献 & 如何开始
2. 开发环境搭建（IDE、数据库、.env 配置）
3. 代码规范（命名、包结构、注释要求）
4. 测试要求（写代码必须同步写测试，`mvn test` 通过）
5. 提交规范（Conventional Commits 格式）
6. PR 流程（fork→branch→test→PR→review）
7. Issue 规范（bug/feature 模板）

### 3.3 CHANGELOG.md — 变更日志

**结构**：按版本组织，每个版本包含：
- Added（新功能）
- Changed（变更）
- Fixed（修复）
- Breaking Changes（破坏性变更）

**格式**：Keep a Changelog 标准。

### 3.4 docs/architecture.md — 架构设计

**结构**：
1. 设计理念（模型无关、领域解耦、组件化）
2. 模块依赖关系图
3. 核心设计模式详解：
   - 三 Bean ChatModel 模式（含切换流程图）
   - Advisor 链式 Pipeline（每个 Advisor 的职责、输入输出、order）
   - 双表对话记忆机制
   - 领域扩展机制（DomainRagExtension 接口、注册流程）
4. 数据流图（从请求到响应的完整链路）
5. 数据库 ER 图或表关系说明
6. 关键设计决策（ADR 格式，为什么这么选）

### 3.5 docs/getting-started.md — 开发者上手

**结构**：
1. 前置环境（JDK、Maven、PostgreSQL、API Key）
2. 克隆 & 构建
3. 数据库初始化
4. 启动核心服务
5. 运行测试
6. 启动 Demo 应用
7. 项目结构导航（指引到各模块 README）
8. 常见问题 FAQ

**目标**：新贡献者 30 分钟内跑通整个项目。

### 3.6 docs/configuration.md — 配置参考

**结构**：
1. 配置总览（YAML 层级结构）
2. 逐项说明（名称、类型、默认值、说明、示例）
3. 环境变量映射表（.env → YAML）
4. 多环境配置（local / postgresql / docker）
5. 安全注意事项（密钥管理）

### 3.7 docs/rest-api.md — REST API 参考

**结构**：
1. 基础信息（Base URL、认证方式、通用错误码）
2. 按 Controller 分组：
   - 每个端点：方法/路径/描述/请求体/响应体/示例
3. SSE 流式端点特殊说明
4. 错误处理（ErrorResponse 结构）
5. 指向 `/swagger-ui.html` 自动生成文档

**来源**：从 @ApiResponse 注解 + Controller 代码提取，不手写重复内容。

### 3.8 docs/extension-guide.md — 领域扩展指南

**结构**：
1. 什么是领域扩展？为什么需要？
2. DomainRagExtension 接口详解
3. 实现步骤（含完整代码示例）
4. PromptCustomizerChain 使用
5. 注册与激活（@Component 自动注册）
6. 高级用法：自定义检索策略、重排规则
7. 参考：demo-domain-extension（医疗领域）

### 3.9 docs/testing-guide.md — 测试指南

**结构**：
1. 测试金字塔（单元 / 集成 / E2E）
2. 如何运行测试（命令速查）
3. 单元测试规范（Mock 策略、@WebMvcTest 用法）
4. 集成测试规范（@SpringBootTest、Testcontainers）
5. E2E 测试（scripts/e2e-test.sh 用法）
6. JaCoCo 覆盖率查看
7. 编写新测试的模板/示例
8. 性能基准测试

### 3.10 docs/troubleshooting.md — 故障排查

**结构**：按症状组织（不是按组件）：
1. 启动失败
   - 数据库连接失败
   - pgvector 扩展未安装
   - 嵌入模型 API 不可达
2. 检索无结果
   - 向量维度不匹配
   - 文档未嵌入
   - min-score 阈值过高
3. LLM 调用失败
   - API Key 无效
   - 模型不存在
   - Token 超限
4. 性能问题
   - 检索慢（索引缺失）
   - 响应慢（LLM 延迟）
5. 测试失败
   - 常见 flaky 测试原因
   - 环境变量缺失

### 3.11 GitHub 配置

- `.github/PULL_REQUEST_TEMPLATE.md`：PR 描述模板（关联 Issue、变更说明、测试清单）
- `.github/ISSUE_TEMPLATE/bug_report.md`：Bug 报告模板（环境、复现步骤、期望行为）
- `.github/ISSUE_TEMPLATE/feature_request.md`：功能请求模板（场景、方案建议）

---

## 四、实施策略

### 优先级排序

| 优先级 | 文档 | 理由 |
|--------|------|------|
| P0 | README.md 重写 | 项目门面，第一印象 |
| P0 | CONTRIBUTING.md | 开源项目必备，影响协作 |
| P1 | architecture.md | 核心开发者必须理解 |
| P1 | configuration.md | 用户最常查阅 |
| P1 | testing-guide.md | 贡献者必读 |
| P2 | getting-started.md | 降低上手门槛 |
| P2 | rest-api.md | 可部分依赖 Swagger 自动生成 |
| P2 | extension-guide.md | 面向高级用户 |
| P2 | troubleshooting.md | 提升用户体验 |
| P3 | CHANGELOG.md | 从下一个正式版本开始维护 |
| P3 | GitHub templates | 可与 CONTRIBUTING.md 同步 |

### 实施原则

1. **每轮只写 1 份文档**，不贪多
2. **先写后审**：写完后以 Reviewer 角度自查
3. **代码即文档**：示例代码必须可编译可运行
4. **不重复造轮**：已有 Swagger 生成的 API 文档，rest-api.md 只补说明不抄格式
5. **与代码同步**：改了代码影响文档的，必须同步更新
