# 第三批候选建议：分块正确性、质量校验与诊断

> 日期：2026-10-14。代码核对基线：`3b227ff0`，`main`。
>
> 状态：候选建议，**不是冻结的实施规划，未开始实施第三批**。
> 若启动本批，须按[交付工作流](../../delivery-workflow-zh-CN.md)补齐自包含规划、验收矩阵及
> 连续三轮无修改检查，再修改代码；本文不将候选能力表述为已实现。

## 1. 推荐结论与优先级

前两批分别解决了**处理是否可恢复**和**答案引用/流式交付是否可信**。第三批最值得投入的是
**输入证据本身是否完整、可定位、尺寸可控**，优先修正确性，而不是直接上多种分块引擎。

推荐顺序：

1. **P0：不丢内容、有界分块及 Validator。** 修复格式误判导致内容消失、超长句未切分等问题。
2. **P0：真实原文位置与标题归属。** 修复 table 移除后 offset 偏移、同级标题错误继承，
   在同一有界算法中保护代码/公式和长表表头。
3. **P0 发布配套：版本化 Profile 与同链路只读预览/诊断。** 让新行为可追踪、可灰度、可回滚；
   不先做复杂 UI 或可插拔解析大架构。

前两项已有当前源码实测的缺陷证据，因此不只是体验增强。第三项之所以一起列入，是为了
避免分块修复使本地索引、旧向量、缓存和引用定位混用不同派生身份。

## 2. 已有能力与代码入口

| 入口 | 现状及本批边界 |
|---|---|
| [DocumentChunkingService](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentChunkingService.java) `prepare` | 本地关键词与 Embedding 共用的唯一协调入口；JSON_RECORD 保持 single chunk；没有 provider 调用 |
| [HierarchicalTextChunker](../../../spring-ai-rag-documents/src/main/java/com/springairag/documents/chunk/HierarchicalTextChunker.java) `split` | 已有标题、表格、句子、固定长度分支和 overlap；应增强它，不平行再造一条处理链 |
| [TextChunk](../../../spring-ai-rag-documents/src/main/java/com/springairag/documents/chunk/TextChunk.java) | 当前记录 text/startPos/endPos，位置契约为原文字符区间，endPos exclusive |
| [RagChunkProperties](../../../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagChunkProperties.java) | 默认 size=1000、min=100、overlap=100；目前不是完整的策略 Profile |
| [DocumentDerivationDescriptorProvider](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentDerivationDescriptorProvider.java) | 当前文本派生版本 `hierarchical-v2:size:min:overlap`；JSON_RECORD 为 `json-record-v1:single` |
| [现有 chunker 测试](../../../spring-ai-rag-documents/src/test/java/com/springairag/documents/chunk/HierarchicalTextChunkerTest.java) | 已覆盖基础场景，但“长句回退”只验证非空与局部位置范围，没有证明尺寸上限及完整覆盖 |

借鉴的通用方法是“保护区识别 → 分块 → 结果校验 → 有界降级”，不照搬参考项目的语言、
配置或模块。来源细节可选读
[原始调研 §4.3](../WEKNORA_REFERENCE_RESEARCH.md#43-p0自适应分块结果校验与分块诊断)；
以下问题及目标均可直接依据本仓源码理解，不依赖外部项目背景。

## 3. 当前源码的可重复缺陷证据

本次直接把当前 `TextChunk` 与 `HierarchicalTextChunker` 源码编译到 `/tmp`，用 JShell
执行下列最小输入，没有改测试或生产实现。

| 输入与参数 | 实测 | 正确性风险 |
|---|---|---|
| `"x".repeat(1000)`，size=100/min=10/overlap=10 | 一个 text 长度 1000 的 chunk，endPos=1001 | 没有按尺寸切分，且 endPos 超出原文；可能击穿下游预算 |
| `"\| Single \| Row \| Table \|"` | 零 chunk | regex 先移除候选表格，但 `isValidTable` 拒绝它后未放回；内容被删除 |
| `# Alpha`/`# Beta` 两个同级标题 | Beta 的正文带 `Alpha > Beta` | 同级章节被误当嵌套，标题上下文污染 |
| intro、合法表格、TAIL 三段 | TAIL 所在 text chunk 区间为 `[0,22)`，原文长 58；原文切片不含 TAIL | text 与原文坐标不对应，影响定位和后续邻接装配 |

对于全篇只有无效表格候选的文档，core `prepare` 会因空输出抛错；对于混合正文，core 的
“非空”检查无法发现某一段已消失。因此不能只增加 `chunks.isEmpty()` 判定就算修复。

另已确认：表格整体转为一个 chunk，不受正常分块尺寸控制；长段 sentence/fixed 分支使用
局部位置，未统一恢复到 section 原文位置。代码围栏和公式尚无独立保护区识别。

复现入口（只运行本地 Java，不调用 LLM）：

```bash
probe_classes=$(mktemp -d /tmp/rag-chunk-probe.XXXXXX)
javac -d "$probe_classes" \
  spring-ai-rag-documents/src/main/java/com/springairag/documents/chunk/TextChunk.java \
  spring-ai-rag-documents/src/main/java/com/springairag/documents/chunk/HierarchicalTextChunker.java
jshell --class-path "$probe_classes"
```

```java
import com.springairag.documents.chunk.*;
var chunker = new HierarchicalTextChunker(100, 10, 10);
chunker.split("x".repeat(1000));
chunker.split("| Single | Row | Table |");
chunker.split("# Alpha\nalpha body\n# Beta\nbeta body");
var original = "intro\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\nTAIL_MARKER_0123";
new HierarchicalTextChunker(500, 10, 10).split(original);
```

## 4. 推荐的默认决策与可逆边界

### 4.1 P0：内容守恒、尺寸预算与可解释降级

- 在原文 span 上识别保护区；未认定为结构块的候选必须保留为普通文本，不能 regex 删除。
- Validator 先验证非空输入不被吞掉、所有位置合法、正文覆盖不缺失和有效 payload 有界；
  再把碎片化、重复率等作为质量 warning。短文档和尾块不得因小于 min 而丢弃。
- 最小策略链建议只做结构/句子切分 → 安全固定窗口降级；不要第一批就引入五种策略和远程
  parser。降级有次数上限，不通过则显式失败，接入已有处理阶段错误和重试语义。
- 字符预算沿用现有配置起步；标题和表头等补充内容也占最终 payload 预算。字符长度不等于
  provider token 数，不宣称严格 token 上限；额外 token 估算只能作为诊断，需单独验证。
- JSON_RECORD 保持原单块契约；不套用普通文本拆分回退。发现超界也不得偷偷切散记录，
  应通过现有大小约束或明确错误处理。

### 4.2 P0：定位、结构保护与长表

- start/end 以未删改原文的 Java 字符下标（UTF-16 code unit）为基准，区间采用 `[start,end)`；
  避免把 UTF-8 字节、Unicode code point 和现有契约混用，不能切断 surrogate pair。
- 同级标题替换当前级，子级继承祖先；不要把所有遇到过的标题串成祖先链。
- 生成的标题路径、重复表头、标记与原正文 span 分开计量。带重建上下文的 text 不应被要求
  与某一个原文切片逐字相等；完整性统计只对原正文覆盖计数，排除 overlap 和重复表头。
- 代码围栏/公式尽量整体保留；超过硬预算时按安全边界拆分并保留诊断。没有安全边界时
  显式失败，不能同时承诺“永不拆原子块”和“所有 payload 严格有界”却不定义冲突处理。
- 长表按行分段并携带表头；超长单行同样遵守可解释的边界或失败规则，不允许无限长特例。

### 4.3 P0 发布配套：Profile、只读 preview、诊断

- 保留现有 `hierarchical-v2` 的可复现兼容配置；新算法使用独立版本身份并先 opt-in/灰度。
  变更算法或预算必须改变派生身份，不能在相同版本下偷偷换输出。
- 预览必须调用 `DocumentChunkingService` 同一准备链，返回 profile/version、块计数、
  尺寸统计、原文范围、warning 和降级原因；不写数据库、不做 Embedding、不调用 LLM。
- 普通 preview 仍走现有身份与文档读取权限；预算和异常诊断不得把全文写进日志/metrics。
- 不自动批量重嵌入旧文档；新 Profile 的重索引须显式触发。回滚时恢复旧策略配置，并明确
  新旧索引 freshness，不能把已有 v3 派生物直接宣称为 v2 有效。
- REST preview 比复杂调试 UI 更优先。schema/API 细节必须在正式规划中按实际读写链冻结，
  这里不承诺“肯定不用迁移”，也不先建立一套独立 Profile 管理平台。

## 5. 正式规划应冻结的验收矩阵

1. **确定性回归**：第 3 节全部缺陷变为失败可复现的测试，再验证修复；覆盖短文档、超长无
   标点/CJK、非法表格、长表、同级/嵌套标题、code fence、公式、CRLF 和 emoji。
2. **守恒/有界/定位**：没有正文遗漏；范围合法；生成上下文单独统计；安全降级有限；
   极端长行失败有错误码/摘要，不挂起、不无限生成碎片。
3. **派生一致性**：本地关键词、Embedding、preview 对相同输入使用同 Profile/version 和
   块序列；JSON_RECORD 不回归；新旧版本切换、显式重索引和回滚 freshness 均可验证。
4. **安全与恢复**：preview 的权限/配额/大小限制、无副作用；新失败能在已有处理阶段呈现并
   重试，不绕过 CAS/lease，不引入悲观锁。
5. **质量与真实依赖**：固定表格/代码/CJK 样本先做离线 chunk 和检索 goldenset 对比；再用
   真实 BGE-M3 与 `.env` 中一个 Chat 模型验证检索命中和来源定位。不得用单次答案主观
   评价替代守恒断言或 goldenset 回归。

完成定义还需包含相关模块测试、Maven、适用 PostgreSQL 集成门禁、文档与禁锁门禁；
如果改 WebUI，再加入其单测、Mock E2E、构建与设计门禁。具体命令复用
[测试指南](../../testing-guide-zh-CN.md)，正式规划给出本批固定命令而不是“实施时再决定”。

## 6. 暂不列为第三批 P0 的方向

- **Retrieval Context Assembly：P1。** parent/neighbor、连续块合并、去重和 coverage 有价值，
  但应先保证块坐标及版本可信，避免叠加在不可靠分块上。
- **Route Policy Self-check：P1。** 作为后续安全专项；若发现具体 fail-open 漏洞则重新升级，
  不因名字含“安全”就假设当前存在已确认漏洞。
- **Parser SPI / Resource Handle / CLI-MCP：需求驱动。** 不为了参考项目对齐而预先铺架构。
- **WebUI 分块可视化：preview 契约稳定后再做。** 第三批的主收益不依赖新页面。

**建议下一次实施范围：先把“内容不丢、块不超界、位置不骗人”做扎实，再扩展检索上下文。**
