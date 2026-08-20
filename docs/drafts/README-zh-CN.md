# 活跃规划

> [English](README.md) | [中文](README-zh-CN.md)

`docs/drafts/` 只保存**当前仍准备实施或正在实施**的规划与进度记录。这里不是稳定项目
事实的长期存放处；已经落地的能力必须提炼到对应的双语长青文档。

## 使用规则

1. 活跃规划使用稳定文件名，例如 `NEXT_HIGH_VALUE_FEATURES_PLAN.md`，避免仅凭日期判断状态。
2. 规划进入实施后，可在同目录增加对应的 `*_PROGRESS.md`，记录恢复任务所需的上下文。
3. 完成、取消或被替代后，先把仍有效的事实同步到 `docs/` 长青文档，再将 plan/progress
   用 `git mv` 移入 [`archive/`](archive/README-zh-CN.md)。
4. 归档时保留或补充 `YYYY-MM-DD_` 前缀，日期表示归档或最后有效的时间点。
5. `AGENTS.md`、`docs/index*` 和长青文档只链接当前活跃规划或归档总入口，不把单份历史稿
   当作当前事实入口。

## 真相顺序

发生冲突时按以下顺序判断：

1. 当前代码、迁移和自动化测试。
2. `docs/` 下的长青 guide/reference。
3. 本目录中的当前活跃规划。
4. `archive/` 中的历史规划和实施账本。

当前活跃规划会在本页和 `docs/index*` 中列出。目录为空时，表示没有已批准或正在编写的
活跃规划。

## 当前活跃规划

- [下一批高价值功能实施规划](NEXT_HIGH_VALUE_FEATURES_PLAN.md)：外部文档跨 Collection
  原子迁移，以及 Collection 派生索引完整性诊断与 preview-first 修复。
- [规划进度](NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)：当前规划的调研、检查计数与可恢复
  上下文。
