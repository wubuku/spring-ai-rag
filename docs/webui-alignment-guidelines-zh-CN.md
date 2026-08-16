# WebUI 水平对齐指南

> [English](webui-alignment-guidelines.md) | [中文](webui-alignment-guidelines-zh-CN.md)

## 目的

WebUI 是管理后台，不是展示型营销页面。默认对齐应服务于扫描、比较和连续操作；“居中”
只在区域本身具有明确空间语义时使用。

## 默认规则

- 页面标题、正文、卡片、表单 label/hint/error、表格文本：`text-align: start`。
- 数值、金额、百分比等适合比较的列：按列需要使用 `text-align: end`。
- 普通 loading、error、empty：保持在当前内容流中，使用 `start`。
- 使用 CSS 逻辑值 `start` / `end`，不新增 `left` / `right` 文本对齐。
- `align-items: center`、`justify-content: center` 等盒级布局不等同于文本居中，不因本规则
  被禁止。

## 允许居中的场景

以下场景可以使用 `text-align: center`：

- 文件上传/拖放投放区；
- 占据明确区域的完整空状态；
- 阻断式错误边界、首次解锁或路由级加载状态；
- 文件、PDF、差异等预览面板的占位状态；
- 固定宽度的分段选项、diff 行前缀等短小控件；
- 表格 `colSpan` 的整行空数据提示。

每个 CSS 居中声明都必须紧邻写出理由：

```css
/* alignment-policy: allow-center -- file upload drop zone */
text-align: center;
```

不要把根节点设为 `text-align: center`，也不要用 `!important` 覆盖普通页面内容。

## 实施与检查

全局样式入口是 `spring-ai-rag-webui/src/styles/global.css`，由 `main.tsx` 加载。
对齐门禁位于 `spring-ai-rag-webui/scripts/check-alignment-policy.mjs`：

```bash
cd spring-ai-rag-webui
npm run check:alignment
npm run lint
npx tsc -b
npm run test:run
npm run build
BASE_URL=http://127.0.0.1:<vite-port> npm run test:e2e
```

新增页面或组件时，先让普通内容继承 `start`，再判断某个完整区域是否确实属于上面的
居中例外。视觉上觉得“整齐”不是使用居中的理由。
