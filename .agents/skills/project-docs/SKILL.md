---
name: project-docs
description: "Create and maintain the tracked, bilingual, AI-agent-friendly documentation system for spring-ai-rag. Use for documentation initiatives, project onboarding, navigation, evergreen context, command references, and EN/ZH synchronization."
metadata:
  short-description: "Maintain spring-ai-rag project documentation"
---

# Project Documentation System

维护 spring-ai-rag 的 Git 跟踪文档体系。目标是让开发者和不同 Agent 能从短入口逐层发现可靠信息。

本 Skill 的项目级 canonical 路径是 `.agents/skills/project-docs/`。模板和检查清单位于同目录 `references/`。

## 1. 核心原则

1. **代码和已跟踪长青文档是项目真相源**。
2. **导航优先于重复正文**：Hub → Guides → Reference。
3. **已有历史文件默认不移动**；只有用户明确要求，或活跃规划按已约定生命周期结束时，
   才使用 `git mv` 归档。不要为了套模板搬迁 live reference。
4. **行为变化同步文档与测试**。
5. **中英文成对文档必须同步**。
6. **密钥和本地状态不得进入 Git 文档**。

## 2. 边界

### 属于项目文档体系并提交 Git

| 层级 | 路径 | 职责 |
|------|------|------|
| Agent Hub | `AGENTS.md` | 硬性规则、读文档顺序、任务入口 |
| Claude Hub | `CLAUDE.md` | Claude Code 极短入口 |
| 人类入口 | `README.md` / `README-zh-CN.md` | 项目门面 |
| 文档 Hub | `docs/index.md` / `docs/index-zh-CN.md` | 全库导航 |
| 项目上下文 | `docs/project-context*.md` | 模块、稳定能力、关键边界 |
| 开发者参考 | `docs/developer-reference*.md` | 构建、启动、DB、模型、E2E 命令 |
| Guides / Reference | `docs/architecture*`、`configuration*`、`rest-api*` 等 | 专题事实 |
| 项目 Skills | `.agents/skills/<name>/SKILL.md` | 可复用工作流，不替代项目文档 |

### 不属于项目文档体系，不提交 Git

以下是 OpenClaw 或其他本地 Agent 状态，只能引用项目长青文档：

- `TOOLS.md`
- `MEMORY.md`
- `memory/`
- `HEARTBEAT.md`
- `SOUL.md`
- `IDENTITY.md`
- `USER.md`
- `.openclaw/`
- 根目录 `skills/`

不得从 `AGENTS.md`、`CLAUDE.md`、README 或 `docs/index*` 链接这些本地文件。

## 3. 项目级 Skill 规则

- canonical 目录：`.agents/skills/<skill-name>/SKILL.md`
- Skill 与项目代码一起提交 Git。
- 根目录 `skills/` 保留给 OpenClaw 本地状态并保持 ignore。
- Skill 只描述工作流、触发条件、检查步骤和可复用脚本。
- 架构事实、命令大全和产品状态写入 `docs/`，Skill 使用相对链接引用。
- 工具专属目录需要兼容时，应指向 `.agents/skills/` 的 canonical 内容，禁止复制两份正文。

## 4. spring-ai-rag 文档分层

```text
AGENTS.md / CLAUDE.md
  -> docs/index*.md
     -> docs/project-context*.md / docs/developer-reference*.md
     -> architecture* / configuration* / rest-api* / testing-guide*
     -> active drafts / component references
     -> drafts/archive（仅历史追溯）
```

现有 live 文档保持原位置。不要为了套用通用模板创建空目录并搬迁文件。

## 5. 文档职责

| 变化 | 更新位置 |
|------|----------|
| 模块、Pipeline、运行拓扑 | `docs/architecture*`，并摘要到 `project-context*` |
| 配置项、环境变量 | `docs/configuration*` |
| HTTP / SSE 契约 | `docs/rest-api*`、`docs/SSE-PROTOCOL.md` |
| 构建、启动、验证命令 | `docs/developer-reference*`、`docs/testing-guide*` |
| 常见故障 | `docs/troubleshooting*` |
| 境内网络 | `docs/china-network-guide*` |
| 发布状态和门禁 | `docs/release-checklist*`、相关进度记录 |
| 稳定项目认知 | `docs/project-context*` |
| 当前目标设计 | `docs/drafts/`，使用稳定文件名并明确状态 |
| 历史规划/进度 | `docs/drafts/archive/`，仅追溯，不作为当前事实 |

## 6. 双语规则

成对文档使用：

```text
foo.md
foo-zh-CN.md
```

页首包含：

```markdown
> [English](foo.md) | [中文](foo-zh-CN.md)
```

要求：

- 结构和事实等价。
- 链接分别指向正确语言。
- 修改一侧必须修改另一侧。
- 单语文档从两个索引指向同一文件，不创建空壳翻译。

## 7. 生命周期

| 类型 | 位置 | 规则 |
|------|------|------|
| Live | 现有 `docs/*.md` | 与代码同改 |
| Draft | `docs/drafts/` | 只保留正在准备或实施的方案，使用稳定文件名 |
| Historical | `docs/drafts/archive/` | 使用 `YYYY-MM-DD_` 前缀；不默认阅读，不追新 |
| Component reference | 组件目录内 README | 留在组件旁 |

规划完成、取消或被替代时：

1. 先把已落地且仍有效的事实提炼到对应双语长青文档。
2. 用 `git mv` 将 plan/progress 移入 `docs/drafts/archive/`，保留或补充日期前缀。
3. `docs/index*` 只保留活跃规划和归档总入口，不列单份历史稿。
4. 归档稿只修复迁移造成的链接问题，不继续维护实时准确性。

## 8. 工作流

### 审计

```bash
find . -name '*.md' \
  -not -path '*/node_modules/*' \
  -not -path '*/.git/*' \
  -not -path '*/target/*' \
  -not -path '*/memory/*' \
  -not -path '*/.openclaw/*' \
  | sort
```

同时检查：

- `docs/index.md` 与 `docs/index-zh-CN.md` 是否结构一致。
- 仓库入口是否只链接 Git 跟踪文件。
- 服务默认端口是否为 `8081`，`scripts/dev.sh` 后端端口是否为 `18082`，
  真实 LLM 端口是否为 `18081`。
- Flyway 是否为 V1–V43。
- OpenAI / Embedding `base-url` 示例是否没有尾部 `/v1`。

### 计划

1. 先修 Hub 和死链。
2. 再修 live guides/reference。
3. 最后处理 active drafts，并把已结束材料移入 archive。
4. 能链接现有文档时不新建平行正文。

### 验证

- 先运行 `./scripts/verify-project-docs.sh` 执行一键文档门禁。
- 相对链接全部存在。
- EN/ZH 成对同步。
- `AGENTS.md` 不超过 120 行，`CLAUDE.md` 不超过 60 行。
- 没有真实密钥、Token、密码。
- `git diff --check` 通过。
- `.agents/skills/` 可跟踪；OpenClaw 本地状态仍被 ignore。

完整清单见 [references/checklist.md](references/checklist.md)。

## 9. 维护约定

- 本 Skill 维护文档工作流，不保存日常项目记忆。
- 新知识先判断是稳定事实、命令、故障、计划还是本地状态，再写入对应位置。
- 因“找不到信息”导致失败时先修索引；因“信息过时”导致失败时修专题文档。
- 模板与本文件冲突时以本文件为准。
