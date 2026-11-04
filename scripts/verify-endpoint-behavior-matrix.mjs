#!/usr/bin/env node
/**
 * 端点行为矩阵静态门禁。
 *
 * 数据来源：
 * - testdata/api-contract/openapi-baseline.json：已实现的 /api/v1/rag/** 端点；
 * - docs/rest-api-zh-CN.md：文档声明的 /api/v1/rag/** 与 /v1/** 端点。
 *
 * 矩阵文件 testdata/api-contract/endpoint-behavior-matrix.json 为每个端点记录：
 * 未认证 401、非法参数 400、不存在 404、成功 2xx + 响应 schema 形状。
 * 文档或 OpenAPI 新增端点而没有矩阵条目时直接失败，不能被绕过。
 *
 * 用法：
 *   node scripts/verify-endpoint-behavior-matrix.mjs           # 校验（CI）
 *   node scripts/verify-endpoint-behavior-matrix.mjs --write   # 重新生成矩阵
 *   node scripts/verify-endpoint-behavior-matrix.mjs --self-test
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { isMainModule } from "./lib/is-main-module.mjs";

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const MATRIX_PATH = path.join(
  ROOT, "testdata/api-contract/endpoint-behavior-matrix.json");
const OPENAPI_PATH = path.join(
  ROOT, "testdata/api-contract/openapi-baseline.json");
const DOC_PATH = path.join(ROOT, "docs/rest-api-zh-CN.md");

const METHODS = ["get", "post", "put", "patch", "delete"];

export function canonicalPath(value) {
  return value
    .replace(/\{[^}]*\}/g, "{}")
    .replace(/\/+$/, "");
}

export function operationKey(method, endpoint) {
  return `${method.toUpperCase()} ${canonicalPath(endpoint)}`;
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function extractDocOperations(text) {
  const found = new Map();
  const pattern =
    /\b(GET|POST|PUT|PATCH|DELETE)\s+`?(\/(?:api\/v1\/rag|v1)\/[A-Za-z0-9_\-{}/.:]+)/g;
  let match;
  while ((match = pattern.exec(text)) !== null) {
    const method = match[1];
    let endpoint = match[2].replace(/[.,;:)]+$/g, "").replace(/\\$/g, "");
    // 文档中的示例具体值归一化为模板参数，避免把示例当成独立路由。
    endpoint = endpoint.replace(/\/rag_k_current\//g, "/{keyId}/");
    const key = operationKey(method, endpoint);
    if (!found.has(key)) {
      found.set(key, { method, path: endpoint.replace(/\/rag_k_current\//g, "/{keyId}/") });
    }
  }
  return found;
}

function resolveSchema(spec, schema, depth = 0) {
  if (!schema || depth > 6) return null;
  if (schema.$ref) {
    const name = schema.$ref.split("/").pop();
    return resolveSchema(spec, spec.components?.schemas?.[name], depth + 1);
  }
  if (schema.type === "array") {
    return { type: "array", items: resolveSchema(spec, schema.items, depth + 1) };
  }
  if (schema.oneOf?.length) {
    return resolveSchema(spec, schema.oneOf[0], depth + 1);
  }
  if (schema.allOf?.length) {
    const merged = { type: "object", required: [], properties: {} };
    for (const part of schema.allOf) {
      const resolved = resolveSchema(spec, part, depth + 1);
      if (resolved?.required) merged.required.push(...resolved.required);
      if (resolved?.properties) Object.assign(merged.properties, resolved.properties);
    }
    merged.required = [...new Set(merged.required)];
    return merged;
  }
  return {
    type: schema.type ?? (schema.properties ? "object" : "unknown"),
    required: schema.required ?? [],
    properties: schema.properties ?? {},
    enum: schema.enum,
  };
}

const CONTENT_OVERRIDES = {
  "POST /api/v1/rag/chat/stream": "text/event-stream",
  "POST /api/v1/rag/documents/embed/stream": "text/event-stream",
  "POST /api/v1/rag/documents/embed/stream/{}": "text/event-stream",
  "POST /api/v1/rag/documents/{}/embed/stream": "text/event-stream",
  "POST /api/v1/rag/documents/upload": "multipart/form-data",
  "GET /api/v1/rag/documents/export": "text/csv",
};

const SUCCESS_PROBES = {
 "collection-create": { method: "POST", path: "/api/v1/rag/collections",
   body: { collectionKey: "matrix-probe", name: "Matrix Probe" },
   expect: { status: 200, requiredKeys: ["id", "collectionKey"] },
   capture: { id: "collectionId", collectionKey: "collectionKey" } },
 "collection-get": { method: "GET", path: "/api/v1/rag/collections/${collectionId}",
   expect: { status: 200, requiredKeys: ["id"] } },
 "collection-list": { method: "GET", path: "/api/v1/rag/collections?page=0&size=5",
   expect: { status: 200 } },
 "collection-by-key": { method: "GET", path: "/api/v1/rag/collections/by-key?collectionKey=${collectionKey}",
   expect: { status: 200, requiredKeys: ["id"] } },
 "collection-update": { method: "PUT", path: "/api/v1/rag/collections/${collectionId}",
   body: { name: "Matrix Probe Updated" }, expect: { status: 200 } },
 "document-create": { method: "POST", path: "/api/v1/rag/documents",
   body: { title: "matrix-probe", content: "matrix probe content", embeddingPolicy: "ASYNC" },
   expect: { status: 200, requiredKeys: ["id"] },
   capture: { id: "documentId", documentRevision: "documentRevision" } },
 "document-get": { method: "GET", path: "/api/v1/rag/documents/${documentId}",
   expect: { status: 200, requiredKeys: ["id"] },
   capture: { documentRevision: "documentRevision" } },
 "document-list": { method: "GET", path: "/api/v1/rag/documents?page=0&size=5",
   expect: { status: 200 } },
 "document-update": { method: "PATCH", path: "/api/v1/rag/documents/${documentId}",
   body: { title: "matrix-probe-updated",
     expectedDocumentRevision: "${documentRevision}" },
   expect: { status: 200 },
   capture: { documentRevision: "documentRevision" } },
 "document-delete": { method: "DELETE", path: "/api/v1/rag/documents/${documentId}",
   query: { expectedDocumentRevision: "${documentRevision}" },
   waitUntil: { path: "/api/v1/rag/documents/${documentId}",
     field: "processingStatus", notIn: ["INDEXING", "PROCESSING"],
     timeoutSeconds: 10 },
   expect: { status: 200 } },
 "document-upload": { method: "POST", path: "/api/v1/rag/documents/upload",
   multipart: { field: "files", filename: "matrix-probe.md",
     content: "# matrix probe\n\ncrash-free endpoint probe" },
   expect: { status: 200 } },
 "search": { method: "POST", path: "/api/v1/rag/search",
   body: { query: "matrix probe" }, expect: { status: 200 } },
 "chat-ask": { method: "POST", path: "/api/v1/rag/chat/ask",
   body: { message: "matrix probe", sessionId: "matrix-probe-session" },
   expect: { status: 200, requiredKeys: ["answer"] } },
 "chat-stream-sse": { method: "POST", path: "/api/v1/rag/chat/stream",
   body: { message: "matrix probe", sessionId: "matrix-probe-sse" },
   expect: { status: 200, contentType: "text/event-stream" } },
 "health": { method: "GET", path: "/api/v1/rag/health",
   expect: { status: 200, requiredKeys: ["status"] } },
 "health-components": { method: "GET", path: "/api/v1/rag/health/components",
   expect: { status: 200 } },
 "cache-stats": { method: "GET", path: "/api/v1/rag/cache/stats",
   expect: { status: 200 } },
 "embedding-jobs-list": { method: "GET", path: "/api/v1/rag/embedding-jobs?page=0&size=5",
   expect: { status: 200 } },
 "openai-models": { method: "GET", path: "/v1/models",
   expect: { status: 200, requiredKeys: ["object", "data"] } },
 "openai-model-get": { method: "GET", path: "/v1/models/rag-default",
   expect: { status: 200, requiredKeys: ["id", "object"] } },
 "openai-chat-completions": { method: "POST", path: "/v1/chat/completions",
   body: { model: "rag-default", stream: false,
     messages: [{ role: "user", content: "matrix probe" }] },
   expect: { status: 200, requiredKeys: ["choices"] } },
};

const SUCCESS_PROBE_BY_ENDPOINT = {
 "POST /api/v1/rag/collections": "collection-create",
 "GET /api/v1/rag/collections": "collection-list",
 "GET /api/v1/rag/collections/by-key": "collection-by-key",
 "GET /api/v1/rag/collections/{}": "collection-get",
 "PUT /api/v1/rag/collections/{}": "collection-update",
 "POST /api/v1/rag/documents": "document-create",
 "GET /api/v1/rag/documents": "document-list",
 "GET /api/v1/rag/documents/{}": "document-get",
 "PATCH /api/v1/rag/documents/{}": "document-update",
 "DELETE /api/v1/rag/documents/{}": "document-delete",
 "POST /api/v1/rag/documents/upload": "document-upload",
 "POST /api/v1/rag/search": "search",
 "POST /api/v1/rag/chat/ask": "chat-ask",
 "POST /api/v1/rag/chat/stream": "chat-stream-sse",
 "GET /api/v1/rag/health": "health",
 "GET /api/v1/rag/health/components": "health-components",
 "GET /api/v1/rag/cache/stats": "cache-stats",
 "GET /api/v1/rag/embedding-jobs": "embedding-jobs-list",
 "GET /v1/models": "openai-models",
 "GET /v1/models/{}": "openai-model-get",
 "POST /v1/chat/completions": "openai-chat-completions",
};

// 少数端点的"不存在"语义不是 404：幂等删除、SSE 错误事件、结果查询返回空集。
// 每条都必须写 reason，矩阵门禁会校验，避免用 override 悄悄放宽。
const MISSING_QUERY_OVERRIDES = {
 // OpenAPI 里只写了 string，未标注 date-time；控制器实际按 ISO-8601 解析。
 "GET /api/v1/rag/alerts/slos/{}": {
   startDate: "2026-01-01T00:00:00Z",
   endDate: "2026-01-02T00:00:00Z",
 },
};

const MISSING_OVERRIDES = {
 "DELETE /api/v1/rag/alerts/silence/{}":
   { status: 200, reason: "告警静默为幂等删除，不存在时仍返回 success=false" },
 "POST /api/v1/rag/files/{}/embed":
   { status: 200, reason: "SSE 在流内以 event:error 表达文件不存在" },
 "GET /api/v1/rag/ab/experiments/{}/results":
   { status: 200, reason: "结果查询对不存在实验返回空数组" },
 "GET /api/v1/rag/evaluation/feedback/type/{}":
   { status: 200, reason: "按反馈类型查询，无数据时返回空数组" },
 "GET /api/v1/rag/alerts/slos/{}":
   { status: 200, reason: "未配置的 SLO 返回零值状态，OpenAPI 也声明 200" },
};

const DOC_ONLY_ENTRIES = [
 { method: "GET", path: "/v1/models", success: { status: 200, requiredFields: ["object", "data"] } },
 { method: "GET", path: "/v1/models/{id}", success: { status: 200, requiredFields: ["id", "object"] } },
 { method: "POST", path: "/v1/chat/completions", success: { status: 200, requiredFields: ["choices"] } },
];

function missingQueryValue(parameter) {
  const schema = parameter.schema ?? {};
  if (schema.enum?.length) return schema.enum[0];
  if (schema.type === "integer" || schema.type === "number") return "999999";
  if (schema.format === "date" || schema.format === "date-time") {
    return "2026-01-01T00:00:00Z";
  }
  if (schema.format === "int64" || schema.format === "int32") return "999999";
  return "probe";
}

function buildEntry(spec, method, endpoint, operation) {
  const key = operationKey(method, endpoint);
  const entry = {
    method,
    path: endpoint,
    canonicalPath: canonicalPath(endpoint),
    auth: "required",
    unauth: { status: 401 },
    success: { status: 200, contentType: "application/json", requiredFields: [] },
    probes: { missing: null, invalid: null },
    successProbe: SUCCESS_PROBE_BY_ENDPOINT[key] ?? null,
  };
  if (!operation) {
    const docOnly = DOC_ONLY_ENTRIES.find((item) =>
      operationKey(item.method, item.path) === key);
    if (docOnly) {
      entry.success = { ...entry.success, ...docOnly.success };
      entry.success.contentType = entry.success.contentType ?? "application/json";
    }
    return entry;
  }
  const pathParams = (operation.parameters ?? []).filter((p) => p.in === "path");
  const requiredQuery = (operation.parameters ?? [])
    .filter((p) => p.in === "query" && p.required);
  const requiredHeaders = (operation.parameters ?? [])
    .filter((p) => p.in === "header" && p.required);
  const requiredBody = operation.requestBody?.required === true;

  entry.pathParams = pathParams.map((p) => ({
    name: p.name,
    schema: p.schema ?? {},
  }));
  if (pathParams.length) {
    const override = MISSING_OVERRIDES[key];
    entry.probes.missing = override
      ? { ...override }
      : requiredBody
        ? { status: 400,
            reason: "请求体校验先于资源存在性，非法/缺省请求体先返回 400" }
        : { status: 404 };
    if (requiredQuery.length) {
      const queryOverride = MISSING_QUERY_OVERRIDES[key] ?? {};
      entry.missingQuery = requiredQuery.map((p) => ({
        name: p.name,
        value: queryOverride[p.name] ?? missingQueryValue(p),
      }));
    }
    if (requiredHeaders.length) {
      entry.missingHeaders = requiredHeaders.map((p) => ({
        name: p.name,
        // 幂等键等头部有最短长度校验，用稳定的长值而不是 5 字符占位。
        value: "probe-idempotency-key-0001",
      }));
    }
  }
  if (requiredBody) {
    entry.probes.invalid = { kind: "json", status: 400 };
  } else if (requiredQuery.length) {
    entry.probes.invalid = {
      kind: "query", status: 400,
      omitted: requiredQuery.map((p) => p.name),
    };
  }

  const responses = operation.responses ?? {};
  const successStatus = Object.keys(responses)
    .filter((status) => /^2\d\d$/.test(status))
    .sort()[0];
  if (successStatus) entry.success.status = Number(successStatus);
  for (const mediaType of ["application/json", "text/event-stream", "text/csv"]) {
    const schema = responses[successStatus]?.content?.[mediaType]?.schema;
    if (schema) {
      const resolved = resolveSchema(spec, schema);
      if (mediaType === "application/json" && resolved?.required) {
        entry.success.requiredFields = resolved.required;
      } else {
        entry.success.contentType = mediaType;
      }
      break;
    }
  }
  const overrideContent = CONTENT_OVERRIDES[key];
  if (overrideContent) entry.success.contentType = overrideContent;
  return entry;
}

export function buildMatrix(specText, docText) {
  const spec = JSON.parse(specText);
  const docOps = extractDocOperations(docText);
  const entries = new Map();
  for (const [endpoint, item] of Object.entries(spec.paths ?? {})) {
    if (!endpoint.startsWith("/api/v1/rag")) continue;
    for (const method of METHODS) {
      const operation = item[method];
      if (!operation) continue;
      const entry = buildEntry(spec, method.toUpperCase(), endpoint, operation);
      entries.set(operationKey(entry.method, entry.path), entry);
    }
  }
  for (const [key, docOp] of docOps) {
    if (entries.has(key)) continue;
    entries.set(key, buildEntry(spec, docOp.method, docOp.path, null));
  }
  for (const item of DOC_ONLY_ENTRIES) {
    const key = operationKey(item.method, item.path);
    if (!entries.has(key)) {
      entries.set(key, buildEntry(spec, item.method, item.path, null));
    }
  }
  return {
    version: 1,
    source: [
      "testdata/api-contract/openapi-baseline.json",
      "docs/rest-api-zh-CN.md",
    ],
    entries: [...entries.values()].sort((a, b) =>
      `${a.method} ${a.path}`.localeCompare(`${b.method} ${b.path}`)),
    successProbes: SUCCESS_PROBES,
  };
}

export function validate(matrix, specText, docText) {
  const errors = [];
  const expected = buildMatrix(specText, docText);
  const actual = new Map(
    (matrix.entries ?? []).map((entry) => [
      operationKey(entry.method, entry.path), entry]));
  for (const key of expected.entries.map((entry) =>
    operationKey(entry.method, entry.path))) {
    if (!actual.has(key)) {
      errors.push(`缺少矩阵条目：${key}。新增端点必须补行为条目。`);
    }
  }
  for (const key of actual.keys()) {
    if (!expected.entries.some((entry) =>
      operationKey(entry.method, entry.path) === key)) {
      errors.push(`矩阵条目已不在文档/OpenAPI 中：${key}`);
    }
  }
  const probeNames = new Set(Object.keys(matrix.successProbes ?? {}));
  for (const entry of matrix.entries ?? []) {
    const key = operationKey(entry.method, entry.path);
    if (entry.unauth?.status !== 401) {
      errors.push(`${key}: 未认证行为必须是 401`);
    }
    if (!(entry.success?.status >= 200 && entry.success.status < 300)) {
      errors.push(`${key}: 缺少 2xx 成功行为`);
    }
    const expectedEntry = expected.entries.find((candidate) =>
      operationKey(candidate.method, candidate.path) === key);
    if (expectedEntry?.probes?.missing && !entry.probes?.missing) {
      errors.push(`${key}: 带路径参数但缺少 404 探针`);
    }
    if (expectedEntry?.probes?.invalid && !entry.probes?.invalid) {
      errors.push(`${key}: 有必填参数但缺少 400 探针`);
    }
    if (entry.probes?.missing && entry.probes.missing.status !== 404
        && !entry.probes.missing.reason) {
      errors.push(`${key}: 不存在资源行为非 404 时必须写明 reason`);
    }
    if (entry.probes?.invalid && entry.probes.invalid.status !== 400) {
      errors.push(`${key}: 非法参数行为必须是 400`);
    }
    if (entry.successProbe && !probeNames.has(entry.successProbe)) {
      errors.push(`${key}: 引用了未定义的成功探针 ${entry.successProbe}`);
    }
  }
  for (const name of probeNames) {
    if (!(matrix.entries ?? []).some((entry) => entry.successProbe === name)) {
      errors.push(`成功探针 ${name} 未被任何端点使用`);
    }
  }
  return errors;
}

function selfTest() {
  const spec = {
    paths: {
      "/api/v1/rag/things/{id}": {
        get: {
          parameters: [{ in: "path", name: "id", schema: { type: "integer" } }],
          responses: { 200: { content: { "application/json": {
            schema: { type: "object", required: ["id"], properties: { id: {} } } } } } },
        },
      },
    },
  };
  const doc = "### `GET /api/v1/rag/things/{id}`";
  const matrix = buildMatrix(JSON.stringify(spec), doc);
  const checks = [];
  checks.push([
    "缺少条目必须失败",
    validate({ ...matrix, entries: [] }, JSON.stringify(spec), doc).length > 0,
  ]);
  checks.push([
    "带路径参数缺少 404 必须失败",
    validate({
      ...matrix,
      entries: matrix.entries.map((entry) => ({
        ...entry, probes: { ...entry.probes, missing: null } })),
    }, JSON.stringify(spec), doc).length > 0,
  ]);
  checks.push([
    "未认证非 401 必须失败",
    validate({
      ...matrix,
      entries: matrix.entries.map((entry) => ({
        ...entry, unauth: { status: 403 } })),
    }, JSON.stringify(spec), doc).length > 0,
  ]);
  checks.push([
    "合法矩阵必须通过",
    validate(matrix, JSON.stringify(spec), doc).length === 0,
  ]);
  for (const [name, ok] of checks) {
    if (!ok) {
      console.error(`[self-test] 失败：${name}`);
      return 1;
    }
    console.log(`[self-test] 通过：${name}`);
  }
  return 0;
}

function main() {
  const args = process.argv.slice(2);
  if (args.includes("--self-test")) return selfTest();
  const specText = fs.readFileSync(OPENAPI_PATH, "utf8");
  const docText = fs.readFileSync(DOC_PATH, "utf8");
  if (args.includes("--write")) {
    const matrix = buildMatrix(specText, docText);
    fs.writeFileSync(MATRIX_PATH, `${JSON.stringify(matrix, null, 2)}\n`);
    console.log(`已生成 ${path.relative(ROOT, MATRIX_PATH)}，`
      + `${matrix.entries.length} 个端点条目。`);
    return 0;
  }
  if (!fs.existsSync(MATRIX_PATH)) {
    console.error(`缺少矩阵文件 ${path.relative(ROOT, MATRIX_PATH)}；`
      + "运行 --write 生成。");
    return 1;
  }
  const matrix = readJson(MATRIX_PATH);
  const errors = validate(matrix, specText, docText);
  if (errors.length) {
    console.error("端点行为矩阵校验失败：");
    for (const error of errors) console.error(`  - ${error}`);
    return 1;
  }
  console.log(`端点行为矩阵校验通过：${matrix.entries.length} 个端点，`
    + `${Object.keys(matrix.successProbes ?? {}).length} 个成功探针。`);
  return 0;
}

if (isMainModule(import.meta.url)) {
  process.exit(main());
}
