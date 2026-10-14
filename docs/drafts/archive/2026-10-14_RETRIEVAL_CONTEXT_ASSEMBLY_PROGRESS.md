# 知识库 Chat 证据组装：实施账本

> 2026-10-14，main/e5f87d5f；[自包含规划](2026-10-14_RETRIEVAL_CONTEXT_ASSEMBLY_PLAN.md)。
> 规划已连续3/3，开始实施；仅第1项功能，第2项作为验收，不做路由授权。

## 当前切片

- 配置、SQL、组装：已完成，默认启用；无 schema 迁移，最高版本保持 V63。
- 主链/预算/引用/诊断：已完成；仅 KNOWLEDGE 接入，原 anchor citation handle 保持不变。
- 单测/PG/版本化质量夹具：已完成；夹具为 `testdata/regression/context-assembly-v1.json`。
- 全量回归、dev.sh、真实模型、双语文档：已完成。
- 实现连续 3/3：已完成；待归档并执行 Git 交付。

## 冻结边界

默认启用；radius1、seeds20、window4000字符；每次最多1个有界SQL。仅KNOWLEDGE，
Search/AGENT/PLAIN/JSON/static不变。不迁移schema、不重嵌入，保持V63。原anchor/handle
不变，安全范围映射为加法；预算不足先退anchor，不让隐藏邻居进入引用范围。
所有位置是UTF-16，合成前缀与正文分别处理；不得用PG substring解释偏移。

## 实施结果

- 新增 `RagContextAssemblyProperties`、`RetrievalContextRepository`、
  `RetrievalContextAssemblyPostProcessor`；通过 `rag.chat.context-assembly.*` 配置，默认
  `enabled=true`、`neighbor-radius=1`、`max-seeds=20`、`max-window-characters=4000`。
- 主链固定为 `rerank -> context assembly -> prompt budget -> citation`。上下文查询复用
  `KeywordIndexSqlScope`、`RetrievalScopeSql` 和 `RetrievalFilters`，单次参数化 SQL，重新
  校验 seed 文本并要求同 document/hash/chunker/generation；删除、禁用、JSON record、过期
  或不新鲜索引均不扩展。
- 窗口组装按 Java UTF-16 范围去除 overlap，保留合成标题/表头，禁止跨派生身份合并；
  数据库异常或不安全范围均保留原 anchor，并记录有限 `degradedCode`。
- 预算不足时先恢复原 anchor；私有 `__ragContextAnchors` 不进入 prompt 或公开 source，
  截断后移除未展示窗口的 `contextAssembly` 范围；公开 source metadata 仅增加加法字段。
- 新增/扩展测试覆盖配置边界、算法与质量夹具、真实 advisor prompt、mapper、预算、Collection/
  document/metadata scope、freshness、关键词-only、失配回退和 JSON record 隔离。

## 验证证据

- `mvn clean compile test-compile`：通过。
- `mvn test`：`7824` tests，`0` failures，`0` errors，`201` skipped，BUILD SUCCESS。
- 聚焦回归：`32` tests passed；含新增配置边界测试后，`RagChatPropertiesValidationTest` 为
  `9/9` passed。
- `TESTCONTAINERS_RYUK_DISABLED=true mvn -pl spring-ai-rag-core -am
  -Dmulti.collection.it.enabled=true -Dtest=MultiCollectionRetrievalPostgresIntegrationTest
  -Dsurefire.failIfNoSpecifiedTests=false test`：`11/11` passed；空库 Flyway V1–V63 成功，
  真实 PostgreSQL 16/pgvector 通过。
- `BASE_URL=http://127.0.0.1:18082 ./scripts/verify-quality-regression.sh`：6 cases 全部
  `Hit Rate/MRR/Recall@5/nDCG=1.0`，无 baseline 回退。
- `scripts/dev.sh start`：后端 `18082`、WebUI `15173` 启动；health/readiness、模型目录鉴权
  和 WebUI 代理通过；随后已 `./scripts/dev.sh --stop`。
- 真实 `.env` 联调：真实 embedding `BAAI/bge-m3` 1024 维、真实 OpenAI-compatible Chat；
  既有 smoke `PASS=10 FAIL=0`。另用临时四 chunk 文档验证中间 chunk 命中时答案包含邻居
  解除码，sources 保持 `c1`，诊断为 `databaseQueries=1`、`neighborChunksAdded=2`、
  `overlapCharactersRemoved=198`、`degradedCode=NONE`；fixture 已自动清理。
- `./scripts/verify-project-docs.sh`：16 checks passed；`verify-no-pessimistic-locks.sh`、
  `git diff --check`：通过。
- `./scripts/verify-project-tests.sh`：57 checks passed；WebUI `npm run test:run` 为 87 个文件/
  1030 tests 全部通过，`npm run build` 的 TypeScript 与 Vite 生产构建通过。

## 实现收敛检查

连续三轮只读检查均无问题，未在检查期间修改实现：

1. 事务、SQL 参数顺序、授权 scope、freshness、UTF-16 合并、预算和引用保真：通过。
2. Chat/API/SSE 兼容性、KNOWLEDGE-only 接入、非目标模式、成本边界和回退：通过。
3. 测试证据、真实启动/模型、双语文档、禁锁、回滚边界和 Git 交付准备：通过。

一次首次 PG 尝试因 Docker Hub 拉取 `testcontainers/ryuk:0.11.0` TLS 超时中断，未产生测试
失败；按项目既有本地兼容方式关闭 Ryuk 后重跑，11/11 和 Maven BUILD SUCCESS 均通过。
失败保留原结果，安全有限code；无需修改.env，不触碰其他服务/WIP。
