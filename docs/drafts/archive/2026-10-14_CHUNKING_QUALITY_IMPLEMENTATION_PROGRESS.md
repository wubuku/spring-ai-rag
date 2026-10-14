# 第三批实施进度：分块正确性、质量校验与诊断

> 规划：[第三批实施规划](2026-10-14_CHUNKING_QUALITY_IMPLEMENTATION_PLAN.md)
> 基线：`6c58187f`，main，2026-10-14。

## 当前状态

- 规划检查3/3已完成：14:46需求/权限、14:47身份/失败、14:47验收/发布；三轮无修改。
- documents算法与validator、core/Profile/API已实现；最新聚焦15+35个测试全部通过。
- 后端基本硬门槛、真实依赖验收与实现连续三轮只读检查全部通过；检查证据见下表。
  本次一次性记录结论后，对最终文档状态再做三轮无修改复核；后续结果以任务输出为准。
- 恢复先读plan，再核对git status和本账本；不丢弃任何WIP。

## 切片

| 切片 | 状态 |
|---|---|
| documents算法、validator与回归 | 实现完成，聚焦15/15通过 |
| core Profile/prepare/preview | 完成，legacy默认与JSON_RECORD不变 |
| API、ACL、MockMvc | 完成，4个HTTP测试与RAG_READ分类通过 |
| PostgreSQL、全量、真实依赖 | 聚焦15+35、4套件40IT、编译/全量8451、全量PG24套件171、真实goldenset/两策略启动全部通过 |
| 双语文档与实现收敛 | 完成：双语文档同步、文档16/16及禁锁/diff门禁通过，材料归档，实现3/3 |
| Git交付 | 验收完成后在main提交并推送；提交ID、远端同步及最终工作区状态以任务最终输出为准 |

## 实施/环境记录

- documents最新14测试通过；core聚焦28测试通过；专项4个PG套件40测试全部通过。
- 新增测试初次编译因测试使用不存在的USER枚举/跨包访问包内分类方法失败，已修正测试。
- MockMvc初次因application.yml覆盖手工测试bean参数运行legacy；改为显式测试properties后通过。
- `mvn clean compile test-compile`和`mvn test`均通过，无失败/错误；全量gated待执行。
- 真实测试自建库`rag_chunk_real_20261014_1518`；18084已有他人服务，启动脚本安全拒绝，
  未停止该服务。改为18093（本次PID51808），M3临时registry、BGE-M3，v3启动健康通过。
- code continuation保留原始缩进/换行；超长保护行显式失败，不通过trim伪造预算通过。
- 处理阶段针对`ChunkingException`归属CHUNKING FAILED，固定code且非自动重试；修改正文后
  可按既有任务机制重新调度。补测试确认不会错误归属PARSING或反复调用provider。
- 首次真实样本脚本按GET SearchResponse误读POST /search数组，已修正脚本；正式3样本通过。
- 已通过真实table/code/CJK样本的原文范围、payload<=1000、preview无模型调用、关键词与
  向量物理块完全相等、检索命中；M3答案验证值和引用chunkIndex有效。
- 全量PG首跑171测试有2个NoClassDefFoundError：与另一门禁的mvn clean共用target产生
  产物删除竞争；该次不是全绿证据。后续严格串行编译/全量/PG，禁止并行clean同一工作区。
- 最新串行`mvn clean compile test-compile`、`mvn test`通过：API559、documents89、
  core7758、starter45，共8451测试，failures=0/errors=0，171个门控测试在默认运行中跳过，
  由独立全量PG门禁补验。编译日志`/tmp/rag-third-final2-compile.log`，全量日志
  `/tmp/rag-third-final2-full.log`。
- 全量PG串行重跑于2026-10-14 15:57通过：24套件171测试，failures=0/errors=0/
  skipped=0；日志`/tmp/rag-third-final2-gated.log`。先前产物删除竞争已消除。
- 最新代码真实验收：`/tmp/rag-third-final3-real-goldenset.log`，三个样本3/3；table4块
  max999、code5块max994、CJK3块max996，所有payload<=1000；真实M3答案/引用VALID，
  preview与关键词/向量物理块及检索结果完全相同，preview模型调用0。
- 使用同一隔离库在18093分别启动v3（PID78266）与legacy（PID79569），健康均UP；
  legacy仍报告旧策略diagnostic violations而不改变旧输出，两进程均正常停止。
- 没有修改`.env`、现有服务或任何外部参考仓库；无新增迁移，WebUI未改，不把本次称为
  真实浏览器E2E。真实goldenset是本批固定样本回归，不代表全语料检索质量全面提升。
- 已删除本次隔离库`rag_chunk_real_20261014_1518`并验证不存在；18093已无监听。
- 归档期间文档门禁发现相对链接仍指向旧目录；已修复归档链接和双语导航后重新验证。
  不将该失败轮作为文档全绿证据。

## 实现收敛检查记录

基本硬门槛通过后于2026-10-14执行以下连续三轮；每轮均未修改实现或文档。
本表在三轮全部完成后一次性落档，不在无问题轮次之间写入。

| 时间（Asia/Shanghai） | 范围 | 发现、处理及结果 |
|---|---|---|
| 16:09 | 内容守恒、保护块预算、UTF-16边界、失败阶段、CAS/lease及持久化身份 | 无实质问题；无需修复；通过1/3 |
| 16:10 | HTTP/序列化、ACL/read-only、分页/成本、legacy/JSON_RECORD及Profile兼容性 | 无实质问题；无需修复；通过2/3 |
| 16:12 | 全量/PG证据、真实Embedding/Chat、两策略启动、双语文档、回滚、完整diff及环境清理 | 无实质问题；无需修复；通过3/3 |

完整diff回看覆盖本批43个文件，无凭据、外部参考仓库、WebUI或迁移改动。暂存后再跑
文档门禁（含新增行密钥扫描）16/16通过；临时库不存在、18093无监听。

## 发布与恢复

- 默认仍为`legacy-v2`，原有索引身份及JSON_RECORD单块契约不变。
- 显式设置`RAG_CHUNK_PROFILE=bounded-v3`并重索引才能启用严格分块；回滚也须按身份
  重建派生物，不能把v3结果视为legacy fresh。
- 不扩展到parent/neighbor、上下文装配、Parser SPI或前端预览；它们不是本批完成承诺。
- 规划/实现均已完成3/3；最终记录落档后复验文档/禁锁/diff门禁及三轮只读检查，
  commit后fetch并按需merge、复验，push main；用任务最终输出记录提交及工作区状态。
