# spring-ai-rag WebUI

> **spring-ai-rag RAG 服务框架的内置管理 Web 界面。**

[![][pipeline-badge]][pipeline] [![][java-badge]][java] [![][react-badge]][react] [![][typescript-badge]][typescript]

[English](./README.md) · [中文](./README-zh-CN.md)

---

## 功能特性

| 模块 | 说明 |
|------|------|
| **Dashboard** | 系统健康概览，文档/集合数量，活跃会话，系统指标 |
| **Documents** | 上传文件，关键词搜索文档，行内预览分块内容，批量删除 |
| **Collections** | 创建知识库，管理文档关联，导出/导入 |
| **Chat** | RAG 对话助手，支持 SSE 流式响应，对话历史侧边栏，导出 JSON/Markdown |
| **Search** | 实时混合检索（向量 + 全文），可配置检索参数 |
| **Metrics** | RAG 系统指标（查询速率、平均延迟、缓存命中率），LLM 性能图表 |
| **Alerts** | SLO 监控，告警历史，静默计划（维护窗口） |
| **Settings** | LLM 提供商选择、检索参数、缓存配置——持久化到 localStorage |

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 框架 | React | 19 |
| 语言 | TypeScript | 5.9 |
| 构建工具 | Vite | 8 |
| 样式方案 | CSS Modules | — |
| 服务端状态 | TanStack Query | 5 |
| 实时通信 | Server-Sent Events (SSE) | — |
| HTTP 客户端 | Axios + axios-retry | — |
| 路由 | React Router | 7 |
| 图表 | Recharts | — |

## 快速开始

### 前置条件

- Node.js 18+
- spring-ai-rag 后端运行在 `http://localhost:8081`

### 开发模式

```bash
cd spring-ai-rag-webui
npm install

# 启动 Vite 开发服务器（代理 /api/* 到 localhost:8081）
npm run dev
# → http://localhost:5173/webui/
```

后端需单独启动：

```bash
# 从项目根目录
mvn spring-boot:run -pl spring-ai-rag-core
```

### 生产构建

```bash
npm run build
# 输出到 dist/（需复制到 spring-ai-rag-core/src/main/resources/static/webui/）
```

## 项目结构

```
src/
├── api/                    # 类型化 API 客户端
│   ├── client.ts          # Axios 实例（含重试拦截器）
│   ├── chat.ts            # 对话端点 + SSE 流式
│   ├── collections.ts     # 集合 CRUD + 导出/导入
│   ├── documents.ts       # 文档上传 + 批量操作
│   └── metrics.ts         # 指标概览
├── components/            # 共享 UI 组件
│   ├── ChatSidebar/       # 对话历史侧边栏（localStorage）
│   ├── CreateCollectionModal/  # 集合创建表单
│   ├── Layout/            # 应用外壳（侧边栏 + 顶栏）
│   ├── Skeleton/          # 加载骨架屏
│   └── Toast/             # 通知系统
├── hooks/                 # 自定义 React Hooks
│   ├── useSSE.ts          # SSE 流式 Hook（对话 + 嵌入进度）
│   └── useFileUpload.ts   # 文件上传（支持进度）
├── pages/                 # 路由级页面组件
│   ├── Dashboard.tsx       # 系统概览 + 指标图表
│   ├── Documents.tsx       # 文档管理
│   ├── Chat.tsx           # RAG 对话界面
│   ├── Collections.tsx    # 集合管理
│   ├── Metrics.tsx        # 详细指标页
│   ├── Alerts.tsx         # 告警管理
│   └── Settings.tsx        # 配置面板
├── styles/
│   ├── global.css         # CSS 变量、重置、基础样式
│   └── variables.css      # 主题 Token（颜色、间距）
├── types/
│   └── api.ts             # 共享 TypeScript 接口
└── App.tsx               # 路由 + QueryClient Provider
```

## 关键 API 端点

| 功能 | 方法 | 端点 |
|------|------|------|
| 健康检查 | GET | `/api/v1/rag/health` |
| 文档列表 | GET | `/api/v1/rag/documents?page=0&size=20&title=keyword` |
| 文档批量创建 | POST | `/api/v1/rag/documents/batch` |
| 文件上传 | POST | `/api/v1/rag/documents/upload` |
| 单文档 SSE 嵌入 | POST | `/api/v1/rag/documents/{id}/embed/stream` |
| 批量 SSE 嵌入 | POST | `/api/v1/rag/documents/batch/embed/stream` |
| 集合内文档搜索 | GET | `/api/v1/rag/collections/{id}/documents?keyword=` |
| 创建集合 | POST | `/api/v1/rag/collections` |
| 集合导出 | GET | `/api/v1/rag/collections/{id}/export` |
| 对话流式 | POST | `/api/v1/rag/chat/stream` |
| 对话历史 | GET | `/api/v1/rag/chat/history/{conversationId}` |
| 对话导出 | GET | `/api/v1/rag/chat/export/{conversationId}?format=json\|md` |
| 混合检索 | POST | `/api/v1/rag/search` |
| 指标概览 | GET | `/api/v1/rag/metrics/overview` |
| 缓存统计 | GET | `/api/v1/rag/cache/stats` |
| 告警列表 | GET | `/api/v1/rag/alerts` |
| SLO 配置 | CRUD | `/api/v1/rag/alerts/slos/configs/{name}` |
| 静默计划 | CRUD | `/api/v1/rag/alerts/silence-schedules/{name}` |

## 开发说明

### SSE 流式

对话使用 `useSSE` Hook：

```typescript
const { messages, sendMessage, isStreaming } = useSSE({
  endpoint: '/api/v1/rag/chat/stream',
  sessionId: conversationId,
});
```

### 文件上传

多文件上传，支持每个文件的进度追踪：

```typescript
const { uploadFiles, isUploading } = useFileUpload({
  onComplete: (fileName) => showToast(`${fileName} 上传成功`),
  onError: (fileName, error) => showToast(`${fileName}: ${error}`, 'error'),
});
```

### API 自动重试

Axios 客户端对 429 和 5xx 响应自动重试（指数退避）：

```typescript
// src/api/client.ts
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 429 || error.response?.status >= 500) {
      // axios-retry 自动处理退避重试
      return Promise.reject(error);
    }
    return Promise.reject(error);
  }
);
```

### CSS Modules

每个组件/页面有独立的作用域 `.module.css`。主题变量定义在 `src/styles/variables.css`：

```css
.myComponent {
  background: var(--color-surface);
  color: var(--color-text);
  border: 1px solid var(--color-border);
}
```

### 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `VITE_API_BASE` | 后端 API 基础 URL | `/api/v1/rag` |

```bash
# .env.local（Vite 自动加载 .env.* 文件）
VITE_API_BASE=/api/v1/rag
```

## 常用脚本

```bash
npm run dev          # 启动 Vite 开发服务器
npm run build        # 生产构建（tsc + vite build）
npm run lint         # ESLint 检查
npm run lint:fix     # ESLint 自动修复
npm run preview      # 本地预览生产构建
```

## License

与 [spring-ai-rag](../README.md) 项目相同。

[pipeline-badge]: https://img.shields.io/github/actions/workflow/status/wubuku/spring-ai-rag/ci.yml
[java-badge]: https://img.shields.io/badge/Java-21%2B-blue
[react-badge]: https://img.shields.io/badge/React-19-blue
[typescript-badge]: https://img.shields.io/badge/TypeScript-5.9-blue
[pipeline]: https://github.com/wubuku/spring-ai-rag/actions
[java]: https://adoptium.net/
[react]: https://react.dev/
[typescript]: https://www.typescriptlang.org/
