# 接口兼容门禁

本仓库对 HTTP 契约做**破坏性变更检测**：以提交在仓库里的 OpenAPI 基线为参照，
把当前运行实例的 `/v3/api-docs` 归一化后逐 operation、逐字段比对。

- 基线：`testdata/api-contract/openapi-baseline.json`（springdoc 原始输出）
- 比对器：`scripts/lib/openapi-breaking-change.mjs`
- 入口：`scripts/verify-openapi-breaking-change.mjs`
- 接入点：CI 后端 job 的 `OpenAPI breaking-change gate`，以及 `scripts/verify-api-compatibility.sh`

## 判定规则

| 变更 | 是否破坏 |
|------|----------|
| 删除 operation / 参数 / 响应码 / 响应字段 / 请求字段 | 是 |
| 字段类型或 `$ref` 目标变化 | 是 |
| 请求字段从可选变必填，或新增必填请求字段 | 是 |
| 参数从可选变必填 | 是 |
| 枚举值被删除 | 是 |
| 新增可选请求字段 | 否 |
| 新增响应字段或放宽必填 | 否 |
| 字段放宽为可空（`T → T|null`） | 否 |
| 新增 operation / 参数 / 响应码 | 否 |

判定面向 `/api/v1` 的既有消费者；真正需要不兼容升级时，应新增 `/api/v2`，
而不是改写 v1 的形状。

## 已显式接受的语义变更

### 2026-11：springdoc 2.8.17 渲染变化与流式导出响应的基线刷新

- **触发**：`springdoc-openapi` 2.8.4 → 2.8.17（连带 swagger-core 2.2.34 → 2.2.47，
  用于清除 swagger-ui webjar 的 DOMPurify 告警）。Java 类型与控制器签名未改。
- **呈现变化（不是运行时变更）**：
  - `JsonNode` 组件从 `{"type":"object"}` 变成 `{}`（无类型约束）。无类型 schema
    接受任意 JSON，属于**放宽**：原先合法的请求仍然合法。涉及的 8 处请求字段
    （`answerRequirements`、`jsonbPayload`、`metadataContains`、`payloadContains`、
    `definition`）在运行时仍是 Jackson `JsonNode`。
  - 带 `@Schema(nullable = true)` 的包装类型（如
    `ApiKeyRotationPrepareRequest.overlapSeconds`）从 `type: integer` 变成
    `type: ["integer","null"]`。比对器已把 `T → T|null` 归一为可空化（放宽），
    并新增自检用例「类型可空化不算破坏」。
  - `Map<String, JsonNode>` 集合的 `additionalProperties` 同样从
    `{"type":"object"}` 变成 `{}`（同一类放宽）。
  - 少数参数/响应头把注解表达的约束补全为文档：`exclusiveMinimum`、
    参数 `default`、`Retry-After` 响应头的 `type: integer`。运行时校验与默认值未改。
- **同时纳入基线的既有变更**：集合导出端点的响应由 JSON
  `CollectionExportResponse` 改为 `StreamingResponseBody`（流式导出，
  见 `1163acd5`）。比对器只比对 operation/字段/响应码，不比较媒体类型顶层
  `$ref`，因此这项漂移在改动落地时未被报出；本次刷新把基线对齐到实际输出。
- **消费者影响**：没有 operation、请求/响应字段、响应码或必填项的删除；
  JsonNode 字段仍按自由 JSON 收发。仅 OpenAPI 文档的类型标注放宽。
- **回归证据**：`node scripts/verify-openapi-breaking-change.mjs --self-test`
  全部 PASS；本地 demo 重新生成 `/v3/api-docs` 与刷新后的基线比对为
  `PASS OpenAPI 无破坏性变更`。

### 2026-11：详情端点不可见 ID 由 `403` 改为 `404`

- **端点**：`GET /api/v1/rag/documents/{id}`、`GET /api/v1/rag/collections/{id}`
  （deprecated 数字路由；`GET /api/v1/rag/collections/{key}` 推荐路由行为不变）。
- **变更**：受限 Key 读取 allow-list 之外的 ID 时，不再返回 `403`，而是与不存在 ID
  相同的 `404`，用于阻断数字 ID 枚举。
- **影响面**：只有依赖 `403` 区分“存在但无权”与“不存在”的受限调用方受影响；
  它们必须把 `404` 统一视为“不存在或不可见”。不受限 Key、root/`ADMIN`、
  未启用认证的部署行为不变。
- **门禁结论**：这两个 operation 的声明响应一直是 `200/400/404/500`，`403` 从未
  出现在 OpenAPI 基线里，所以形状门禁对该变更返回 PASS。比对器本身能识别这类
  变更：若基线声明了 `403` 而实际只有 `404`，会报告“响应码被删除”（破坏）。
  本次属于运行时语义收紧，按“显式接受”记录，不更新基线。
- **回归证据**：`DocumentAclControllerTest.restrictedKeyGets404ForDocumentInAnotherCollection`、
  `CollectionAclControllerTest.restrictedKeyGets404ForAnotherCollection`；本地 demo 实例的
  `/v3/api-docs` 与基线比对无差异。

## 本地使用

```bash
# 比对运行中实例
node scripts/verify-openapi-breaking-change.mjs \
  --url http://127.0.0.1:18081/v3/api-docs

# 比对已保存的规范文件
node scripts/verify-openapi-breaking-change.mjs --current /tmp/openapi.json

# 规则自检（不依赖服务）
node scripts/verify-openapi-breaking-change.mjs --self-test

# 有意的契约变更：确认后更新基线并随 PR 提交
node scripts/verify-openapi-breaking-change.mjs \
  --update --current /tmp/openapi.json
```

退出码 0 表示无破坏；1 表示存在破坏或基线缺失。

## 基线维护约定

1. 只有**确认消费者可接受**的变更才能 `--update`，更新后的基线必须和实现同 PR 提交。
2. 纯新增（可选字段、新端点）可以直接更新基线；删除、改类型、改必填必须先加 v2 或回退。
3. 基线是生成物，不要手工编辑；比对器只看 OpenAPI 形状，不比较 `info.version`、`servers` 等运行期元数据。
4. CI 会用 demo 实例重新生成 `/v3/api-docs` 再比对，基线过期会直接失败。

## 边界

- 该门禁证明**HTTP 形状**兼容，不证明语义兼容（例如同名字段含义变化、默认值变化、
  校验规则收紧）。
- 运行期行为与数据迁移仍由 nightly、E2E 和 Flyway 双路径验收覆盖。
- 比对器不启动真实模型，接口门禁只依赖 PostgreSQL 与 demo 启动。
