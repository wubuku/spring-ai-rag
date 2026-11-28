# Spring AI RAG Operator Console（运营方管理控制台）

独立的平台运营方多租户管理前端（端口 `5174`），严格按**租户粒度**治理生命周期、凭据、配额与用量、知识库聚合健康、隔离合规与持久化审计；与租户工作台 `spring-ai-rag-webui` 物理隔离。

## 快速启动

```bash
npm install

# 1. Mock 原型模式（默认，无需后端，顶栏显式标注「原型 · mock · 未接真」）
npm run dev

# 2. 真接后端模式（连接 /api/v1/rag/operator/**）
VITE_OPERATOR_CLIENT=real npm run dev
```

## 类型检查、构建与 Playwright 冒烟

```bash
npm run typecheck
npm run build
npm run test:e2e:mock
```
