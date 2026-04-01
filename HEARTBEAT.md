# HEARTBEAT.md — cron 任务指令

## 每轮步骤
1. `export $(cat .env | grep -v '^#' | xargs) && mvn clean test`
2. 选下一个未完成项（见待办清单）
3. 参考三项目代码（见 AGENTS.md），实现改进
4. `mvn test` 通过 → 提交推送

## 待办

| # | 改进项 | 类型 | 状态 |
|---|--------|------|------|
| 1-5 | 简化嵌入/统一配置/质量评估/用户反馈/基础设施 | — | ✅ 已有 |
| 6 | 文档批量操作 | 效率 | ✅ 完成 |
| 7 | A/B 实验 | 策略对比 | ✅ 完成 |
| 8 | 测试补充（AbTestController + Adapter） | 质量 | ✅ 完成 |
| 9 | Collection（知识库）管理 API | 功能 | ✅ 完成 |
| 10 | 告警服务（AlertService） | 运维 | ✅ 完成 |

## 进度日志

- 2026-04-02 02:23: 告警服务完成。5 文件 1347 行。455 tests, 0 failures。
- 2026-04-02 01:55: Collection 管理 API 完成。3 文件 527 行，15 个测试。64 tests。
- 2026-04-02 01:17: 修复 Starter 自动配置。添加 Boot 3.x 发现文件 + @ComponentScan，50 tests。
- 2026-04-02 00:54: 测试补充。4 文件 371 行。49 tests, 0 failures。
- 2026-04-01 23:53: A/B 实验完成。9 文件 1195 行。

## 铁律
- 每轮只做 1 项，`mvn test` 不过不提交
- HEARTBEAT.md ≤ 30 行
