# 业务服务接入就绪 P0 实施进度

> **状态**：实施中
>
> **日期**：2026-08-25
>
> **规划**：[BUSINESS_CLIENT_INTEGRATION_READINESS_PLAN.md](BUSINESS_CLIENT_INTEGRATION_READINESS_PLAN.md)
>
> **分支**：`feat/business-client-integration-readiness-20260825`
>
> **基线**：`origin/main` @ `7e701e30`
>
> **隔离 worktree**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-business-client-integration-readiness`

本文只记录恢复实施所需的项目内上下文、验证证据和下一步，不记录 raw credential、
完整业务 payload 或外部项目路径。

## 1. 当前结论

- 自包含 P0 规划已在 `main` 上连续通过 `3/3` 实质审查。
- 规划和上一轮归档文档已提交并推送到 `origin/main`，提交为 `7e701e30`。
- 专用分支和隔离 worktree 已从该最新远端基线创建。
- 本轮不改 schema，不新增 Flyway migration；目标迁移版本仍为 `V48`。
- 本轮不改变 Chat/LLM 行为；ASYNC 数据路径使用本地确定性 embedding stub 验证。
- query credential 拒绝测试只使用一次性数据库 credential；secret 不进入 shell argv、
  日志或持久化证据，测试后立即吊销并由 trap 兜底清理。

## 2. 实施清单

- [x] Slice A：扩展 `/api/v1/rag/auth/me` DTO、controller、OpenAPI 和测试。
- [x] Slice B：新增业务客户端真实 HTTP 合同与一键就绪验证脚本。
- [x] Slice C：同步 WebUI TypeScript、Mock 和真实 API Key Playwright。
- [x] Slice D：新增双语业务客户端接入 runbook，并同步 API/测试/开发/发布/TODO 文档。
- [x] 后端 PostgreSQL 集成矩阵、`mvn clean compile test-compile` 和服务启动通过。
- [x] 前端 typecheck、Vitest、生产 build、核心 Mock 与真实 Playwright 通过。
- [x] 真实全栈合同、文档、锁策略、密钥和 diff 门禁通过。
- [ ] 合并最新 `origin/main` 后按合并基线完整复验。
- [ ] 特性分支和 `main` 均已提交推送，主工作区干净，隔离 worktree 已安全移除。

## 3. 验证账本

| 时间 | 范围 | 结果 | 证据/备注 |
|---|---|---|---|
| 2026-08-25 | 规划最终文本 | PASS | 连续 `3/3` 无修改审查 |
| 2026-08-25 | 规划提交前门禁 | PASS | `verify-project-docs.sh` 10/10、锁策略、密钥扫描、`git diff --check` |
| 2026-08-25 | Git 基线 | PASS | `main == origin/main == 7e701e30`；特性 worktree 基于该提交 |
| 2026-08-25 | Slice A focused 后端 | PASS | 43 tests，failures/errors/skipped 均为 0；identity controller、真实认证过滤链、OpenAPI contract |
| 2026-08-25 | Slice C 前端类型门槛 | PASS | `npm ci` 后 `npm run typecheck` 通过 |
| 2026-08-25 | Slice B 脚本静态检查 | PASS | HTTP 合同 `bash -n`、embedding stub `py_compile` 通过 |
| 2026-08-25 | Slice B 一键编排静态检查 | PASS | readiness 脚本 `bash -n`、ShellCheck（可用时）、执行权限与 `git diff --check` 通过 |
| 2026-08-25 | 首次完整 readiness 运行 | FAIL | focused 109 tests 通过；发现生命周期迁移测试仍断言过时的最新版本 `V45`，以及步骤包装器未传播函数内中间失败；容器、端口和 private artifacts 已清理 |
| 2026-08-25 | 修复后基础与集成门槛 | PASS | focused 109 tests；PostgreSQL 23 tests；`mvn clean compile test-compile`；前端 typecheck、218 Vitest、build、Mock Playwright；文档/锁/密钥/diff 均通过且无跳过 |
| 2026-08-25 | 首次真实全栈合同 | FAIL | 合同通过认证、ACL、边界和 ASYNC 入队；确定性 embedding stub 未读取 Spring AI 的 chunked request body，返回 400；生产数据未丢失，trap 已清理全部隔离资源 |
| 2026-08-25 | embedding stub chunked 探针 | PASS | Python 编译、两份 Bash 语法检查通过；HTTP/1.1 chunked JSON 返回两条确定性 embedding，计数器确认 1 请求/2 输入 |
| 2026-08-25 | 第二次真实全栈合同 | FAIL | chunked provider 路径和 ASYNC embedding 收敛已通过；合同继续通过 replay/CAS/payload filter/tombstone，随后发现 tombstone lookup 的脚本断言字段位置与实际 API DTO 不一致；trap 已清理隔离资源 |
| 2026-08-25 | 第三次真实全栈合同 | FAIL | tombstone lookup 修正通过，恢复请求返回 200；随后发现恢复断言仍引用不存在的 `lifecycle.enabled`，属于验收脚本与 `DocumentLifecycleResponse` 契约不一致；trap 已清理隔离资源 |
| 2026-08-25 | 第四次真实全栈合同 | FAIL | 真实 HTTP 合同 64 项全部通过；后续数据库事实检查读取 `summary.txt` 时错误地用逐行 `jq -R` 执行整文件映射，解析器失败，真实前端 Playwright 尚未执行；trap 已清理隔离资源 |
| 2026-08-25 | 聚焦真实全栈验收 | PASS | `real` 阶段 5/5：全仓 Maven 编译、一次性 PostgreSQL、真实服务启动、HTTP 合同 64 项、Flyway V48/明文 credential 0/成功 embedding job、真实 API Key Playwright 1/1；trap 清理端口、进程、容器和 private artifacts |
| 2026-08-25 | Slice D 文档门禁 | PASS | 新增双语业务服务接入指南并同步 API/测试/开发/索引/发布/TODO；`verify-project-docs.sh` 10/10、双语结构、链接、脚本语法、密钥和空白检查全部通过 |
| 2026-08-25 | 合并前完整 readiness 基线 | PASS | 一键脚本 16/16：focused 后端 109、PostgreSQL 23、Maven compile/test-compile、WebUI typecheck + Vitest 218 + build、Mock Playwright 1/1、文档 10/10、禁锁/密钥/diff、真实 HTTP 64 + 数据库事实 + 真实 Playwright 1/1 |

## 4. 下一步

本地提交当前已验证实现，fetch/merge 最新 `origin/main`，然后按合并后基线重新执行完整
readiness 验收。
