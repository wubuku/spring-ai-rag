# spring-ai-rag WebUI 实现规划

> 基于 claude-mem WebUI 参考分析 + 改进（青出于蓝）

## 1. 概述

spring-ai-rag WebUI 是一个内置的可视化管理界面，用于管理文档、知识库、RAG 对话、监控系统健康等。

### 1.1 参考基准：claude-mem WebUI

| 维度 | claude-mem | spring-ai-rag WebUI（目标） |
|------|-------------|--------------------------|
| UI 框架 | React 18.3.1 | **React 19** |
| TypeScript | 5.3 | **5.7+** |
| 构建工具 | 裸用 esbuild | **Vite 6** |
| 样式方案 | Vanilla CSS（全局） | **CSS Modules** |
| 服务器状态 | 手写 Hooks | **TanStack Query** |
| 实时通信 | SSE（已实现） | SSE（继承） |
| Source Map | 关闭 ❌ | **开启 ✅** |
| 代码分割 | 无 | **按路由懒加载 ✅** |
| 测试 | 缺失 | **Vitest + Playwright ✅** |
| 代码规范 | 未配置 | **ESLint + Prettier ✅** |

### 1.2 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 框架 | React | 19.x |
| 语言 | TypeScript | 5.7+ |
| 构建工具 | Vite | 6.x |
| 样式方案 | CSS Modules | - |
| 服务器状态 | TanStack Query | 5.x |
| 实时通信 | SSE | - |
| 测试框架 | Vitest | 3.x |
| E2E 测试 | Playwright | 1.x |
| 代码规范 | ESLint + Prettier | - |

---

## 2. 功能模块

### 2.1 页面结构

```
/                     # 首页仪表盘
/documents            # 文档管理
/documents/upload     # 文件上传
/collections          # 知识库管理
/chat                 # RAG 对话（Chat UI）
/chat/:sessionId      # 指定会话
/search               # 实时检索
/metrics              # 指标监控
/alerts               # 告警管理
/settings             # 系统设置
```

### 2.2 模块详情

#### Dashboard 首页
- 系统状态概览（健康状态、文档数量、活跃会话数）
- 最近活动时间线（SSE 实时推送）
- 快速操作入口（上传文档、新建知识库、发起检索）
- 指标卡片（检索延迟、缓存命中率、API 调用统计）

#### 文档管理 `/documents`
- 文档列表（分页、搜索、过滤）
- 文档详情（内容预览、元数据、分块信息）
- 批量操作（批量删除、批量嵌入）
- 版本历史查看

#### 文件上传 `/documents/upload`
- 拖拽上传区域
- 多文件选择
- 上传进度条（支持 SSE 进度事件）
- 上传结果报告（成功/失败/跳过）

#### 知识库管理 `/collections`
- 知识库列表（CRUD）
- 知识库详情（文档数量、向量维度、创建时间）
- 文档关联管理（添加/移除文档到知识库）
- 导出/导入知识库

#### RAG 对话 `/chat`
- 对话列表（历史会话）
- 对话详情（消息列表 + 引用来源）
- 流式响应（SSE，支持打字机效果）
- 引用文档高亮（点击跳转到文档详情）
- 会话管理（清空历史）

#### 实时检索 `/search`
- 搜索框（支持高级语法）
- 检索结果展示（得分、来源摘要）
- 混合检索开关（向量/全文/融合）
- 检索历史

#### 指标监控 `/metrics`
- 检索质量指标（NDCG、Precision@K）
- 模型调用统计（延迟分布、Token 消耗）
- 缓存命中率趋势
- A/B 实验结果

#### 告警管理 `/alerts`
- 活跃告警列表
- 告警历史
- 手动触发告警
- 静默配置

#### 系统设置 `/settings`
- LLM 提供商配置（DeepSeek / Anthropic / OpenAI）
- 嵌入模型配置（SiliconFlow BGE-M3）
- 检索参数配置（混合检索权重、重排数量）
- 告警阈值配置（SLO）
- API Key 管理

---

## 3. API 映射

WebUI 通过以下 REST 端点与后端交互：

| 页面 | 后端端点 | 方法 |
|------|---------|------|
| Dashboard | `/api/v1/rag/health` | GET |
| Dashboard | `/api/v1/rag/metrics` | GET |
| Dashboard | `/api/v1/rag/documents/stats` | GET |
| 文档列表 | `/api/v1/rag/documents` | GET |
| 文档详情 | `/api/v1/rag/documents/{id}` | GET |
| 文档删除 | `/api/v1/rag/documents/{id}` | DELETE |
| 文件上传 | `/api/v1/rag/documents/upload` | POST (multipart) |
| 批量创建+嵌入 | `/api/v1/rag/documents/batch/create-and-embed` | POST |
| 知识库列表 | `/api/v1/rag/collections` | GET |
| 知识库创建 | `/api/v1/rag/collections` | POST |
| 知识库详情 | `/api/v1/rag/collections/{id}` | GET |
| 知识库导出 | `/api/v1/rag/collections/{id}/export` | GET |
| RAG 对话 | `/api/v1/rag/chat/ask` | POST |
| RAG 流式对话 | `/api/v1/rag/chat/stream` | POST (SSE) |
| 对话历史 | `/api/v1/rag/chat/history/{sessionId}` | GET |
| 检索 | `/api/v1/rag/search` | GET |
| 指标 | `/api/v1/rag/metrics` | GET |
| 告警列表 | `/api/v1/rag/alerts/active` | GET |
| 触发告警 | `/api/v1/rag/alerts/fire` | POST |

---

## 4. SSE 实时事件

> **注意**：当前后端 SSE 仅在 `/chat/stream` 实现，用于对话流式响应。文档上传进度等事件的 SSE 端点需在 Phase W1 新增。

| 事件 | 触发时机 | 后端状态 |
|------|----------|---------|
| `new_chat_message` | 流式对话消息块 | ✅ 已有 |
| `embedding_progress` | 嵌入进度（批量上传时） | ❌ 需新增 |
| `new_document_embedded` | 文档嵌入完成 | ❌ 需新增 |
| `alert_triggered` | 触发告警 | ❌ 需新增 |
| `alert_resolved` | 告警解决 | ❌ 需新增 |

---

## 5. 技术架构

### 5.1 项目结构

```
spring-ai-rag/
├── spring-ai-rag-webui/              # 前端独立子项目
│   ├── public/
│   │   └── index.html
│   ├── src/
│   │   ├── main.tsx                 # 入口
│   │   ├── App.tsx                  # 根组件
│   │   ├── routes/                  # 路由
│   │   │   ├── Dashboard.tsx
│   │   │   ├── Documents.tsx
│   │   │   ├── Collections.tsx
│   │   │   ├── Chat.tsx
│   │   │   ├── Search.tsx
│   │   │   ├── Metrics.tsx
│   │   │   ├── Alerts.tsx
│   │   │   └── Settings.tsx
│   │   ├── components/              # 共享组件
│   │   │   ├── Layout/             # 布局（侧边栏、顶栏）
│   │   │   ├── Card/               # 通用卡片
│   │   │   ├── Modal/              # 模态框
│   │   │   ├── Table/             # 通用表格
│   │   │   ├── Upload/            # 上传组件
│   │   │   └── Chat/              # 聊天组件
│   │   ├── hooks/                  # 自定义 Hooks
│   │   │   ├── useSSE.ts          # SSE 实时通信
│   │   │   ├── useAPI.ts          # TanStack Query 封装
│   │   │   ├── useTheme.ts        # 主题切换
│   │   │   └── usePagination.ts    # 分页
│   │   ├── api/                    # API 客户端
│   │   │   ├── client.ts           # Axios 实例
│   │   │   ├── documents.ts        # 文档 API
│   │   │   ├── collections.ts      # 知识库 API
│   │   │   ├── chat.ts             # 对话 API
│   │   │   ├── search.ts           # 检索 API
│   │   │   ├── metrics.ts          # 指标 API
│   │   │   └── alerts.ts           # 告警 API
│   │   ├── types/                  # TypeScript 类型
│   │   │   └── api.ts
│   │   └── styles/                 # 全局样式
│   │       ├── global.css
│   │       └── variables.css
│   ├── vite.config.ts
│   ├── package.json
│   └── tsconfig.json
├── spring-ai-rag-core/              # 后端核心
│   └── src/main/resources/
│       └── static/                   # 构建产物目录
│           └── index.html             # WebUI 入口（由构建脚本拷贝）
```

### 5.2 构建系统

**构建工具**：Vite 6
- 开发服务器 + HMR（解决 claude-mem 裸用 esbuild 的 DX 问题）
- 按路由代码分割（解决单文件 IIFE 的性能问题）
- 内置 CSS Modules 支持

**Java 集成**：同 claude-mem 方案
- 前端构建产物拷贝到 `spring-ai-rag-core/src/main/resources/static/`
- Spring MVC 静态资源映射 + SPA fallback
- Maven 构建时自动触发前端构建（通过 `exec-maven-plugin`）

### 5.3 样式架构

**CSS Modules**：解决 Vanilla CSS 全局污染问题

```css
/* DocumentCard.module.css */
.card {
  border-radius: 8px;
  padding: 16px;
  background: var(--color-surface);
}
```

```tsx
/* DocumentCard.tsx */
import styles from './DocumentCard.module.css';
export function DocumentCard() {
  return <div className={styles.card}>...</div>;
}
```

**主题支持**：CSS Variables

```css
:root {
  --color-bg: #ffffff;
  --color-surface: #f5f5f5;
  --color-text: #1a1a1a;
  --color-primary: #3b82f6;
}
[data-theme="dark"] {
  --color-bg: #0f0f0f;
  --color-surface: #1a1a1a;
  --color-text: #e5e5e5;
  --color-primary: #60a5fa;
}
```

### 5.4 状态管理

**TanStack Query（React Query）**：解决手写 Hooks 的工程化问题

```tsx
// 替代 claude-mem 的手写 useStats Hook
const { data: stats } = useQuery({
  queryKey: ['metrics'],
  queryFn: () => api.get('/metrics'),
  refetchInterval: 30000,  // 自动轮询
});
```

**SSE 集成**：通过 TanStack Query 的 `queryClient.setQueryData` 更新缓存

```tsx
function useSSE() {
  const queryClient = useQueryClient();
  useEffect(() => {
    // 复用已有的 /chat/stream SSE 端点
    const es = new EventSource('/api/v1/rag/chat/stream');
    es.onmessage = (e) => {
      const event = JSON.parse(e.data);
      if (event.type === 'new_document') {
        queryClient.setQueryData(['documents'], old => [event.document, ...old]);
      }
    };
    return () => es.close();
  }, [queryClient]);
}
```

**注意**：当前后端 SSE 仅在 `/chat/stream` 实现。文档上传进度等事件可在后续新增 `/stream` 端点统一推送。

### 5.5 与 claude-mem 的关键差异

| 改进点 | claude-mem | spring-ai-rag WebUI |
|--------|-------------|---------------------|
| 构建工具 | esbuild（无 HMR） | Vite（内置 HMR） |
| 样式隔离 | Vanilla CSS 全局污染风险 | CSS Modules 编译时隔离 |
| 服务器状态 | 手写 Hooks（缺重试/缓存） | TanStack Query（自动缓存+重试） |
| Source Map | 生产禁用 ❌ | 生产开启 ✅ |
| 代码分割 | 单文件 IIFE | 按路由懒加载 |
| 测试 | 缺失 | Vitest + Playwright |
| 代码规范 | 未配置 | ESLint + Prettier |

---

## 6. 分阶段实施计划

### Phase W1: 项目初始化（预计 1-2 轮 cron）
- 搭建 spring-ai-rag-webui 项目（Vite + React 19 + TypeScript）
- 配置 ESLint + Prettier + CSS Modules
- 配置 TanStack Query + Axios
- 搭建路由结构（React Router v7）
- 搭建布局组件（侧边栏 + 顶栏）
- 构建脚本（Vite build + Maven 集成）

### Phase W2: 核心页面（预计 2-3 轮 cron）
- Dashboard 首页（指标卡片 + 健康状态）
- 文档管理页面（列表 + 详情 + 搜索）
- 文件上传页面（拖拽上传 + 进度 + 结果）

### Phase W3: 高级功能（预计 2-3 轮 cron）
- RAG 对话页面（流式响应 + 引用高亮）
- 实时检索页面（结果展示 + 混合检索开关）
- SSE 实时推送集成

### Phase W4: 监控与配置（预计 1-2 轮 cron）
- 指标监控页面
- 告警管理页面
- 系统设置页面

### Phase W5: 工程化收尾（预计 1 轮 cron）
- 补充 Vitest 单元测试
- 补充 Playwright E2E 测试
- 生产 Source Map 配置
- 性能优化（代码分割验证）

---

## 7. API 兼容性注意事项

### 7.1 SSE 端点
当前后端仅在 `/chat/stream` 提供对话流式 SSE。文档上传进度、告警等事件的统一推送端点需要新增：

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamEvents() {
    // 推送文档状态变更、告警、嵌入进度等事件
}
```

此端点在 Phase W1 实现，作为 WebUI SSE 实时功能的基础设施。

### 7.2 文档列表分页
当前 `GET /documents` 返回格式：
```json
{
  "documents": [...],
  "total": 100,
  "page": 0,
  "pageSize": 10
}
```

需要确认是否需要新增 `page`/`size` 查询参数端点。

---

## 8. 测试策略

| 类型 | 工具 | 覆盖目标 |
|------|------|---------|
| 组件单元测试 | Vitest + Testing Library | 所有 React 组件 |
| Hook 测试 | Vitest | useSSE、usePagination 等 |
| API 集成测试 | Vitest + MSW | API 客户端函数 |
| E2E 测试 | Playwright | 核心用户流程（上传、检索、对话） |
| 样式回归 | Playwright 截图 | 关键 UI 组件 |

---

## 9. 已知风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| SSE 端点不存在 | 实时功能无法实现 | Phase W1 新增端点 |
| 大文件上传内存问题 | Mac SIGKILL | 前端分片上传 + 后端流式处理 |
| 多 Tab 状态同步 | 用户在多 Tab 操作数据不一致 | TanStack Query 自动 refetch |

---

## 10. 成功标准

- WebUI 可通过 `mvn spring-boot:run` 启动后直接访问（`localhost:8081`）
- 所有核心页面可交互（文档上传、知识库管理、对话检索）
- ESLint + Prettier 检查通过
- Vitest 测试覆盖率 > 70%
- 构建产物 < 500KB（首屏 JS）
