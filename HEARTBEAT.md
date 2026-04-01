# HEARTBEAT.md — cron 任务指令

## 每轮执行步骤

1. 运行 `export $(cat .env | grep -v '^#' | xargs) && mvn clean test` 确认构建通过
2. 读 `docs/IMPLEMENTATION_COMPARISON.md` 待办清单，选下一个未完成的 P1 项
3. 参考对应项目代码（路径见下方），实现改进
4. 运行 `mvn test` 确认测试通过
5. 启动服务跑 `scripts/e2e-test.sh`
6. 提交推送，汇报进展到飞书

## 参考项目（遇到问题必看）

- `/Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo` — Spring AI 用法
- `/Users/yangjiefeng/Documents/taisan/MaxKB4j` — Pipeline 架构
- `/Users/yangjiefeng/Documents/wubuku/RuiChuangQi-AI/src/dermai-rag-service` — 生产 RAG 服务

## P1 待办（全部完成 ✅）

| # | 改进项 | 文件 | 状态 |
|---|--------|------|------|
| 1 | API 兼容性适配层 | adapter/ | ✅ |
| 2 | 查询改写同义词 | QueryRewritingService.java | ✅ |
| 3 | 检索日志表 | V3__add_retrieval_logs.sql | ✅ |
| 4 | VectorStore.add() 简化嵌入 | RagDocumentController.java | ✅ |
| 5 | RagProperties 统一配置 | config/RagProperties.java | ✅ |
| 6 | 业务异常类 | exception/ | ✅ |
| 7 | 异步异常处理 | config/AsyncConfig.java | ✅ |

## P2 进展

| # | 改进项 | 状态 |
|---|--------|------|
| P2-1 | 实体 @Table(indexes) 注解 | ✅ 已有（无需改动） |
| P2-7 | 统一错误响应格式 | ✅ 已有（GlobalExceptionHandler 完善） |
| P2-8 | 文档内容哈希去重 | ✅ 完成 |

## 进度日志

- 2026-04-01 18:34 — cron 触发，构建通过
- 2026-04-01 18:35 — #4 VectorStore.add() 确认已有实现+测试，跳过
- 2026-04-01 18:36 — #5 RagProperties 确认已有实现+测试+服务引用，跳过
- 2026-04-01 18:40 — #6 业务异常类完成（RagException/DocumentNotFound/Embedding/Retrieval），commit 3a9c3aa
- 2026-04-01 18:45 — #7 异步异常处理完成（AsyncConfig + RagProperties.Async），commit 502f54e
- 2026-04-01 18:46 — 🎉 P1 全部完成！下一步：扫描 P2 改进项
- 2026-04-01 19:53 — cron 触发，构建通过，开始 P2 改进
- 2026-04-01 19:55 — P2-1 实体 @Table(indexes) 已存在，P2-7 GlobalExceptionHandler 已完善，均跳过
- 2026-04-01 19:56 — P2-8 文档内容哈希去重完成（SHA-256 + findByContentHash + 4 个新测试），295 tests ✅

## 铁律

- 写代码前先看参考项目怎么做的
- 每轮只做 1 个改进项
- `mvn test` 不过不提交
- 进展写 HEARTBEAT.md 进度日志

## 监控 Cron（每次会话必须做）

- 检查 cron 状态：`openclaw cron list | grep RAG`
- 如果状态 `running` 超过 15 分钟 → 任务卡住，检查并干预
- 如果状态 `error` → 查看原因（`openclaw cron runs --id ... --limit 1`），修复后触发新运行
- 如果长时间没收到飞书汇报 → 手动触发 `openclaw cron run --id ...`
- HEARTBEAT.md 保持精简（30 行以内），详细内容放 docs/ 下的单独文件
