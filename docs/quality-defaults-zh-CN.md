# 生产质量默认值

> 📖 [English](quality-defaults.md) · 📖 [中文](quality-defaults-zh-CN.md)

> 当 `SPRING_PROFILES_ACTIVE` 包含 **`prod`** 时，以下默认值生效
>（见 `spring-ai-rag-core/src/main/resources/application-prod.yml`）。

| 配置 | 生产默认值 | 原因 |
|------|------------|------|
| `rag.security.enabled` | `true` | 强制 API Key 认证 |
| `rag.circuit-breaker.enabled` | `true` | LLM 不可用时快速失败 |
| `rag.rerank.enabled` | `true`（heuristic） | 本地质量增强，无额外重排 API 成本 |
| `rag.rerank.provider` | `heuristic` | 可改为 `http`，接入 cross-encoder |
| `rag.query-rewrite.enabled` | `true` | 提升召回 |
| 检索权重 | vector 0.55 / fulltext 0.45 | 略偏向向量，可通过 goldenset 调优 |

## 使用 goldenset 证明增益

```bash
# 服务运行后执行；真实 Embedding Key 才能反映实际检索质量
./scripts/run-retrieval-goldenset.sh
# 或：
BASE_URL=http://127.0.0.1:18081 ./scripts/run-retrieval-goldenset.sh
```

样例集：`testdata/goldenset/retrieval-goldenset.json`

脚本会对同一批用例执行两轮 `POST /api/v1/rag/search`：

- `baseline`：`useRerank=false`
- `quality`：`useRerank=true`

两轮结果均通过 `POST /api/v1/rag/evaluation/evaluate` 持久化。脚本输出平均
Precision@K、MRR、nDCG 及其差值；MRR 或 nDCG 回退时返回失败。

## 版本化真实检索回归

Goldenset 用于比较重排开关；版本化回归用于阻止真实检索能力在后续提交中退化：

```bash
BASE_URL=http://127.0.0.1:18081 ./scripts/verify-quality-regression.sh
```

- 数据集：`testdata/regression/retrieval-core-v1.json`
- baseline：`testdata/regression/retrieval-core-v1-baseline.json`
- 稳定 relevant identity：`collectionKey + externalId`
- 指标：Hit Rate、MRR、Recall@K、nDCG
- 安全断言：selected Collection 不泄漏 decoy；明确 JSONB 空结果保持零命中

脚本会对 absolute minimum 和 baseline 允许回退量同时判定，provider、数据库、embedding
或 HTTP 失败均返回非零。`verify-release.sh --with-quality-regression` 可对已启动服务追加
该门禁，`--with-local-runtime` 默认包含它。

### 对比基线与生产质量组合

```bash
SPRING_PROFILES_ACTIVE=postgresql,prod bash scripts/start-server.sh
BASE_URL=http://127.0.0.1:8081 API_KEY=rag_sk_... \
  ./scripts/run-retrieval-goldenset.sh
```

请求级 `useRerank` 只能关闭已启用的全局重排；当
`rag.rerank.enabled=false` 时，不能通过单次请求启用重排。

### 可选 HTTP 重排

```yaml
rag:
  rerank:
    enabled: true
    provider: http
    api-key: ${SILICONFLOW_API_KEY}
    model: BAAI/bge-reranker-v2-m3
    base-url: https://api.siliconflow.cn
```
