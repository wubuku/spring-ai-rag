# HEARTBEAT.md — cron 任务指令

## 每轮步骤

1. `export $(cat .env | grep -v '^#' | xargs) && mvn clean test` 确认构建通过
2. 读待办清单，选下一个 ⏳ 项
3. 实现改进
4. `mvn test` 通过 → 提交推送汇报


## 待办

| # | 改进项 | 类型 | 状态 |
|---|--------|------|------|
| 1 | demo-basic-rag 补测试 | 测试覆盖 | ✅ 2026-04-02 |
| 2 | 集成测试覆盖率提升（Controller 层） | 测试覆盖 | ⏳ |
| 3 | 检索质量评估（RetrievalEvaluationService） | 业务功能 | ⏳ |
| 4 | 用户反馈端点（FeedbackController） | 业务功能 | ⏳ |
| 5 | 文档批量操作 | 使用效率 | ⏳ |
| 6 | A/B 实验框架 | 策略对比 | ⏳ |
| 7 | 性能基准测试（单次检索 <500ms） | 性能 | ⏳ |
| 8 | SSE 流式响应 E2E 测试 | 测试覆盖 | ⏳ |
| 9 | 对话记忆多轮验证 | 测试覆盖 | ⏳ |
| 10 | SpringDoc OpenAPI 端点文档完善 | 文档 | ⏳ |

## 进度日志

- 2026-04-02 03:29 — ✅ #1 demo-basic-rag 补测试：DemoControllerTest 8 个单元测试，commit 94723d4

## 铁律

写代码前看参考项目 | 每轮只做 1 项 | `mvn test` 不过不提交 | 进展写进度日志 | ≤ 40 行

## 永不停止

待办清空后：审查 IMPLEMENTATION_COMPARISON.md → 扫描 TODO/FIXME → 检查覆盖率 → 性能优化 → 提新建议。没有可做？重构长方法、提取重复、改善命名。
