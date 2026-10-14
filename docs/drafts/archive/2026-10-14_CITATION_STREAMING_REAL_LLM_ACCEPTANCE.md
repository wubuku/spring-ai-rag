# 第二批引用与流式安全：真实 LLM 补充验收

> 日期：2026-10-14（Asia/Shanghai）。测试代码基线：`3b227ff0`，分支 `main`。
>
> 状态：真实 provider smoke 与补充协议检查均通过；本次自建服务和临时数据库已清理。
> 本文是新增的历史验收证据，不修改此前账本中当时准确的 `NOT RUN` 记录。

## 1. 前两批交付及本次范围

- **第一批：文档处理可靠性。** 统一 Processing Run/Stage、既有 Job 镜像、CAS/lease、
  heartbeat、取消、重试、死信、stuck 恢复、housekeeping 和受权限控制的 HTTP actions；
  新增 V61。功能提交 `5378364f`。详见
  [第一批实施账本](2026-10-14_WEKNORA_FIRST_BATCH_DOCUMENT_PROCESSING_PROGRESS.md)。
- **第二批：引用与流式安全。** 请求/attempt 级 Citation Registry、`c1` handle 与 `S1` 兼容
  别名、三个内置检索工具对齐、引用校验和授权 fail-closed；原生 SSE sequence、WebUI EOF/
  终态/重试去重、keyed snapshot 引用保存与回放；保持 OpenAI 兼容协议隔离。无需新增迁移。
  功能提交 `3b227ff0`。详见
  [第二批实施账本](2026-10-14_WEKNORA_SECOND_BATCH_CITATION_STREAMING_PROGRESS.md)。
- 两批此前已完成规划与实现检查、自动化门禁及 Git 交付。本次在用户明确授权调用 `.env`
  配置模型后，补齐第二批的真实 LLM/Embedding 和协议证据，未修改生产代码或历史账本。

## 2. 隔离环境与实际模型

| 项目 | 本次实际设置 |
|---|---|
| 服务 | `postgresql` profile，端口 `18083`，使用 `scripts/start-real-e2e-server.sh` |
| 数据库 | 一次性 PostgreSQL 数据库 `rag_citation_real_20261014_1346`，迁移至 V61 |
| Chat 路由 | `minimax/MiniMax-M3`，`apiType=openai-chat`，streaming/toolCalling 均开启 |
| Embedding | SiliconFlow `BAAI/bge-m3`，1024 维；Profile `siliconflow-bge-m3-1024-v1` |
| 模型注册表 | `/tmp/rag-citation-real-models-20261014.json`，只配置本次目标模型；API Key 使用环境变量引用 |
| 凭据 | 仅由本地 `.env` 提供；没有改写 `.env`，没有将凭据或模型答复写入本文 |
| 其他服务 | 已有 `18081` 服务未停止、未重启、未修改其数据库 |

`LLM_PROVIDER`、provider bean 默认值和路由最终模型并非总是一致。验收以 `/models` 能力
及回答的 `resolvedModel` 为准，不能仅凭 `.env` 的 provider 名称判定实际执行模型。
配置格式见[多模型外部配置](../../multi-model-external-config-zh-CN.md)。

## 3. 正式真实依赖 smoke

2026-10-14 13:52 完成的正式命令：

```bash
BASE_URL=http://127.0.0.1:18083 REAL_LLM_CHAT_PROVIDER=minimax \
  ./scripts/real-llm-e2e-smoke.sh
```

结果：**`PASS=11 FAIL=0`，退出码 0**。覆盖：

1. 真实 Chat provider 连通性探测。
2. 隔离 Collection、合成文档、真实 Embedding 写入及限定范围检索。
3. KNOWLEDGE 回答和原生流式回答包含预期验证值。
4. 带明确检索主题的 AGENT 工具调用及工具结果、sources、Citation Registry 对齐。

AGENT 的安全摘要：`resolvedModel=minimax/MiniMax-M3`；一个 `searchKnowledge` 工具调用，
一个来源，标识为 `citationId=S1` / `sourceHandle=c1`，registry handles 为 `['c1']`；
`citationValidation.status=VALID`。这里只记录模型和布尔/计数证据，不保存 prompt、answer
或工具参数/结果正文。

## 4. 补充 SSE、幂等回放与来源失效检查

2026-10-14 14:00 完成补充检查。使用另一组隔离的合成文档，在 KNOWLEDGE 模式明确指定
Collection 和 documentIds，并通过独立 session 查询 `rag_llm_usage_event` 计数。

| 断言 | 实测结果 |
|---|---|
| 无 key 原生流 | 所有业务事件 sequence 从 1 连续递增；sources 在 done 前；无 error |
| keyed 首次流 | `content → sources → done`，sequence 为 `[1,2,3]` |
| 来源别名与 registry | `S1/c1` 一致，registry handles 为 `['c1']`，validation 为 `VALID` |
| 同一 key 回放两次 | turnId 稳定；content/sources 与首次相同；sequence 稳定；replay header 为 true |
| 回放调用成本 | 该 session 的模型 invocation 数在回放前后均为 2；回放增量为 0 |
| turn 查询 | `GET /api/v1/rag/chat/turns/{turnId}?includeResponse=true` 成功 |
| 来源失效 | 按 `expectedDocumentRevision` CAS 禁用来源后，同 key 回放返回 HTTP 403；调用计数不增加 |

首请求的两次 invocation 包含检索扩展与最终回答，不应错误地要求 KNOWLEDGE 一定只有一次
provider 调用。重点是**回放不再调用模型**。

keyed 流验证的是已完成结果的幂等 snapshot 回放，不代表持久化逐 token 断点续传。
HTTP 403 覆盖来源禁用后的生命周期校验，不替代不同 principal 的全面 ACL/越权矩阵。

## 5. 已区分的配置与测试边界

1. 初次禁用外部 registry 后，YAML 路由回到 `MiniMax-M2.7` 且 toolCalling=false；该次
   smoke 为 10 pass / 1 fail，AGENT 返回 `MODEL_CAPABILITY_UNSUPPORTED`，没有执行 AGENT
   provider 调用。随后仅为隔离服务加载 M3 临时 registry，正式重跑获得 11/11。
   这说明真实验收必须检查有效路由；不能把该次能力预检拒绝冒充真实 AGENT 成功或 provider 故障。
2. 一次泛化 AGENT 提示没有明确检索主题，模型选择澄清而不是工具调用。正式 AGENT smoke
   使用明确主题通过；补充回放使用确定性的 KNOWLEDGE。**不能声称模型对任意提示都必定调用工具。**
3. 临时协议脚本曾因 turn 路径漏 `/chat`、disable 请求漏 revision 停止，修正测试请求后
   全部通过；未因此修改生产实现。
4. 本次不是新的 WebUI 真实浏览器验收，不包含真实 provider 的故障注入、生产负载、完整成本
   压测或第一批 worker 故障恢复压测；原自动化门禁与本次真实模型证据各自承担不同职责。

## 6. 重复验证入口与证据保存

重新验证时，新建隔离 PostgreSQL 数据库，按
[真实依赖启动与测试说明](../../developer-reference-zh-CN.md)启动独立端口的服务；加载
只含目标模型的临时 registry，确认 `/models` 能力及 `resolvedModel`，再运行第 3 节命令。
临时 registry 的 `apiKey` 必须继续使用 `${SPRING_AI_MINIMAX_API_KEY}` 一类占位引用。

协议重复验证按第 4 节顺序执行：首请求 → 两次同 key 回放 → turn 查询 → CAS disable →
再次回放，并在每个阶段用该 session 的只读 invocation count 排除重复调用。
调用契约查[REST API](../../rest-api-zh-CN.md)和[SSE 协议](../../SSE-PROTOCOL.md)。

本机原始证据位置（不是 Git 交付依赖，可能含合成请求/答复，应仅本地保留）：

- `/tmp/rag-citation-real-20261014-m3-server.log`
- `/tmp/rag-citation-real-20261014-m3-smoke.log`
- `/tmp/rag-citation-real-20261014-protocol-summary.json`
- `/tmp/rag-citation-real-protocol-20261014.py`（仅本次补充协议验证的临时脚本）

本文件保存可跨会话理解的安全摘要；`/tmp` 文件丢失后，仍可依上述入口重新验证。

## 7. 清理与结论

- 已通过本次启动 wrapper 的退出 trap 停止自建服务，核对 `18083` 无监听。
- 已执行并确认 `DROP DATABASE rag_citation_real_20261014_1346`，清理本次文档、向量与账本。
- smoke 中的旧 DELETE 清理请求未携带 revision，因此不把它作为文档清理成功的证据；
  最终清理由整个一次性数据库删除保证。
- `18081` 原服务仍在运行；`.env`、参考代码库和生产代码均未修改。

**结论：第二批新增引用链路、原生 SSE 和完成态回放获得真实 M3/BGE-M3 证据；
第三批尚未实施。**
