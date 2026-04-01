# HEARTBEAT.md — cron 任务指令

## 每轮执行步骤

1. 运行 `export $(cat .env | grep -v '^#' | xargs) && mvn clean test` 确认构建通过
2. 读下面待办清单，选下一个未完成项
3. 参考三个项目代码（见下方），实现改进
4. `mvn test` + `scripts/e2e-test.sh` 通过
5. 提交推送，汇报到飞书

## 参考项目

- `/Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo` — Spring AI 用法
- `/Users/yangjiefeng/Documents/taisan/MaxKB4j` — Pipeline 架构
- `/Users/yangjiefeng/Documents/wubuku/RuiChuangQi-AI/src/dermai-rag-service` — 生产 RAG 服务

## 待办（用户指定顺序）

| # | 改进项 | 类型 | 状态 |
|---|--------|------|------|
| 1 | VectorStore.add() 简化嵌入 | 开发体验 | ✅ 已有 |
| 2 | RagProperties 统一配置 | 开发体验 | ✅ 已有 |
| 3 | 检索质量评估 | 检索效果 | ✅ 已有 |
| 4 | 用户反馈 | 质量闭环 | ✅ 已有 |
| 5 | 基础设施（异常类+异步+哈希） | 基础 | ✅ 已有 |
| 6 | 文档批量操作 | 使用效率 | ⏳ |
| 7 | A/B 实验 | 策略对比 | ⏳ |
| 8 | 其他事项 | — | ⏳ |

## 铁律

- 写代码前先看参考项目
- 每轮只做 1 项
- `mvn test` 不过不提交
- 进展写进度日志

## 监控 Cron

- `openclaw cron list | grep RAG`
- `running` 超 15 分钟 = 卡住，干预
- `error` = 查原因修复
- HEARTBEAT.md ≤ 30 行
