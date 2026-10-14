# 前三批能力默认启用与自动升级规划

> 状态：已实施并通过验收，2026-10-14归档。基线`f27a1d35`，main工作区；最终证据见配套进度记录。
> 用户要求：可兼容的新能力默认启用；数据升级由版本化迁移和后台任务完成，不要求运维改开关或手工重索引。
> 工作流：[规划、实施与验收](../../delivery-workflow-zh-CN.md)。本批不改HTTP路径或已有字段语义。

## 1. 已核对事实与范围

- 第一批`RagDocumentProcessingProperties`及YAML的处理账本、housekeeping均已默认true，V61自动建表；旧Job由既有bridge恢复，不再加一套任务状态机。
- 第二批CitationRegistry、SSE sequence/decoder是无开关的增量能力；引用验证及Chat幂等默认true，旧JSON与无sequence事件继续兼容。不启用与前三批无关的破坏性清理、通知、OpenAI端点或外部同步开关。
- 第三批Java属性、YAML及真实启动脚本仍默认legacy-v2；原方案要求手工重索引。此处违背新交付要求。
- 检索、完整性检查和缓存严格比较chunker_version，直接改默认会隐藏旧数据；不能把旧向量的版本字符串改为v3冒充已经重算。
- `DocumentEmbedService`在provider前写关键词，不能直接用于保持旧结果可用的自动升级。

近距离代码入口：[分块协调器](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentChunkingService.java)、
[派生身份](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentDerivationDescriptorProvider.java)、
[关键词提交](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/KeywordIndexPersistenceService.java)、
[向量提交](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/EmbeddingPersistenceService.java)、
[检索scope](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/EmbeddingProfileSqlScope.java)、
[完整性检查](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/DerivationIntegrityRepository.java)。

## 2. 冻结方案

### 2.1 默认值与真实身份

新文档默认bounded-v3，保留显式legacy-v2作为紧急回退覆盖，不作为发布的必选步骤。
V62给rag_documents增加服务端私有`derivation_chunker_version`，null表示当前配置；非null表示升级过渡期间实际服务的旧身份。
迁移优先从匹配正文hash的本地索引，其次最近的Embedding状态/既有Job，提取真实已存版本；启动登记再核对当前活动Profile。仅支持格式完整的hierarchical-v2或bounded-v3，不猜测自定义参数。
旧文档的有效身份、分块参数、SQL scope、完整性、缓存及任务均使用该私有值；原DTO不暴露字段，JSON_RECORD保持单块。
新写入的默认配置与preview都必须显示有效身份，不能用v3标签包装v2内容。

### 2.2 自动升级状态与触发

V62新增`rag_chunking_upgrade_tasks`，(document_id, embedding_profile_id)主键并级联删除，保存target_version、source_hash、status、attempt_count、lease_owner/expires、available_at、last_error_code及时间，不保存正文/凭据。
任务默认启用；应用启动时仅做本地数据库版本对照/过渡身份登记（无模型调用），后台独立线程每30秒扫描最多2篇；每实例后台升级并发1、同一文档/Profile仅一个租约，不阻塞共享Spring scheduler或服务就绪。
目标是当前配置身份，因而显式回退配置也可以自动重建；目标或正文hash变化允许重置旧失败状态。同一目标/正文的永久失败不反复计费。
多实例以条件upsert/lease抢占，租约心跳与有界重试（默认3次），不使用悲观锁/advisory lock。
正在运行或排队的Embedding Job先按真实旧身份完成，自动升级暂不抢占；新任务/内容变更由现有CAS拒绝陈旧提交。

### 2.3 计算与原子切换

1. 读文档快照和活动Embedding Profile。关闭/删除/无过渡身份/活跃Job的文档不处理。
2. 用当前目标Profile在内存prepare，暂不写任何索引。
3. 只有原文在活动Profile已有COMPLETED且hash一致、版本不同的向量才调用真实Embedding（含曾经停用、现在激活的Profile）；关键词-only文档只升级已有关键词，不偷偷引入模型费用；SKIP/NOT_REQUESTED不新建任何索引。已有关键词版本等于目标而活动向量版本不同也要登记升级，不能因关键词已完成漏掉向量。
4. 校验结果数量、顺序文本、向量维度与有限数值；调用provider期间无DB长事务，失败保留全部旧索引。
5. 一个短事务中先校验任务lease、当前Profile/目标，再以文档version/hash/私有旧身份/enabled及无活跃Job条件CAS移除过渡身份；关键词和向量共用已核对目标chunks，并用既有事务提交组件原子替换；任务置COMPLETED。
6. 任何失败回滚身份、关键词、向量和任务完成状态，正文及documentRevision不变。CAS失效不覆盖用户修改；安全code记录，不落原文或provider异常正文。

超过新预算/原子行限制的历史文档保持旧结果和真实身份，升级任务FAILED可诊断；不是把它伪装成v3成功，也不要求运维先改全局开关。正文修正或预算目标变化后自动重试。新内容使用v3既有校验契约，不降低其严格保证。
inactive Embedding Profile不自动产生费用；日后激活时按当前目标自动升级。不提供混合新旧二进制滚动发布的保证，升级涉及的新schema由Flyway在应用启动前完成。

## 3. 文件级切片

1. 双语delivery-workflow及AGENTS短链接记录默认启用/自动迁移长青规则；新增计划/进度。
2. V62、JPA私有字段、有效身份解析；分块默认及参数化prepare；scope/完整性/JPA查询/缓存写入统一身份。
3. 自动升级协调器/任务仓库（可以一个包内聚实现），独立执行器/lease/心跳、短事务切换与安全失败。
4. 默认值、参数/有效身份、协调器、V61→V62真实迁移、旧检索连续可用、失败保留、幂等/重启/并发/CAS、JSON_RECORD/关键词-only回归。
5. 双语configuration、REST/architecture/project-context/testing/developer-reference同步新默认及自动化边界；脚本默认同步；已完成旧规划不改正文，仅归档本批新材料。

## 4. 固定验收矩阵与门槛

- 后端聚焦：新升级单测、chunk/core/API兼容、第一批processing、第二批citation/replay；逐步到`mvn clean compile test-compile`、`mvn test`。
- PostgreSQL：新增升级suite纳入`verify-gated-it.sh`；验证V61旧数据实际升级、原身份可检索、原子成功、失败不删旧向量、正文CAS/禁用/活跃Job、restart/lease幂等；全量PG门禁不得跳过。
- 不改WebUI代码，第二批SSE decoder跑现有聚焦单测；新HTTP不加路径，不需新增浏览器页面。
- 隔离数据库和空闲端口启动默认Profile；先造v2正文/关键词/向量，正常升级启动，无RAG_CHUNK_PROFILE启用覆盖，观察自动完成及Chat来源；真实BGE-M3/M3调用有界，凭据只来自.env，最后清理自建资源。
- 实际用 `scripts/dev.sh` 启动后端18082与WebUI15173，检查readiness、前端/HMR及代理鉴权/管理写入；不得只用语法检查替代启动验收，不停止其他工作区的服务。
- `verify-project-docs.sh`、`verify-no-pessimistic-locks.sh`、`git diff --check`；回看全部diff及密钥检查。
- 基本门槛之后实现只读3/3；记录检查时间/范围/问题及处理，修改则重置；提交、fetch/必要merge/复验、push main并确认干净。

## 5. 风险、可逆边界与完成定义

- 迁移脚本只做schema/真实身份和持久任务准备，不在Flyway内调用外部模型。
- 默认自动重嵌入存在真实费用：只迁移已经有向量的活动Profile，用有界批次、单并发、重试上限控制；保留显式关闭自动迁移的应急开关，但不要求正常运维配置。
- 原文超限等数据级问题不全库阻断，不重试无限计费；旧可用结果保留，日志仅ID/code。
- 基本模型或网络不可用时服务照常就绪，旧索引不消失；已失败任务有记录，正文/目标变更可恢复。
- 私有有效身份只允许后端写入，外部请求不可指定。新API字段保持增量、HTTP鉴权和Collection ACL不变。
- 完成=新功能默认启用且新/旧数据库自动收敛、失败路径安全、测试/真实依赖证据齐备、正式文档同步、规划和实现各3/3、Git交付。
