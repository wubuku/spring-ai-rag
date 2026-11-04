#!/usr/bin/env node
/**
 * 写端点幂等语义门禁。
 *
 * 背景：仓库里只有部分写端点支持 `Idempotency-Key`，其余靠业务唯一约束、CAS、
 * 状态机或"调用方责任"兜底。此前没有任何机制保证新增写端点时想过幂等：
 * 一个 POST 可以悄悄变成"重复提交两次建两行"。
 *
 * 本脚本从 controller 源码提取所有 POST/PUT/PATCH/DELETE 端点，要求每个端点在
 * `scripts/testdata/write-endpoints.json` 登记：
 *
 *   - 分类：header-key / unique-key / cas / state-guard / read-only / caller-responsibility
 *   - 冲突语义（重复或并发提交时返回什么，例如 409 错误码、重放同一结果）
 *   - 重复提交是否可能多建一行（prevented / possible）
 *   - 是否可能重复调用模型或 embedding Provider（deduped / possible / none）
 *   - 证据：文件 + 必须存在的 token（唯一约束名、ON CONFLICT、expectedRevision、
 *     Idempotency-Key 等），文件缺失或 token 消失都会失败
 *
 * 未登记的写端点、登记已过期（方法不存在/路径不符/HTTP 方法不符）、分类与证据
 * 自相矛盾、`possible` 却没有写明调用方责任与证据，都会失败。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CONTROLLER_ROOTS = [
  path.join(ROOT, 'spring-ai-rag-core/src/main/java/com/springairag/core/controller'),
  // 演示应用也暴露写端点（聊天/对比），同样是重复提交会重复调用模型的入口，
  // 不能因为"只是 demo"就落在普查之外。
  path.join(ROOT, 'demos'),
];
const MANIFEST_FILE = path.join(ROOT, 'scripts/testdata/write-endpoints.json');

const CLASSIFICATIONS = new Set([
  'header-key',
  'unique-key',
  'cas',
  'state-guard',
  'read-only',
  'caller-responsibility',
]);
const RECORD_RISKS = new Set(['prevented', 'possible']);
const PROVIDER_CALLS = new Set(['deduped', 'possible', 'none']);
const HTTP_METHODS = ['POST', 'PUT', 'PATCH', 'DELETE'];

function fail(message) {
  throw new Error(message);
}

function readControllerSources(dir) {
  const files = [];
  if (!fs.existsSync(dir)) return files;
  const walk = current => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('Controller.java')) files.push(full);
    }
  };
  walk(dir);
  return files;
}

/** 把注解参数按顶层逗号切开（括号/引号内的逗号不切）。 */
function splitTopLevel(text) {
  const parts = [];
  let depth = 0;
  let quote = null;
  let current = '';
  for (const ch of text) {
    if (quote) {
      current += ch;
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === '\'') {
      quote = ch;
      current += ch;
      continue;
    }
    if ('(<['.includes(ch)) depth++;
    if (')>]'.includes(ch)) depth--;
    if (ch === ',' && depth === 0) {
      parts.push(current.trim());
      current = '';
      continue;
    }
    current += ch;
  }
  if (current.trim()) parts.push(current.trim());
  return parts;
}

/** 从 `@XxxMapping(...)` 注解文本里取第一个字符串字面量作为路径。 */
function annotationPath(annotationText) {
  const open = annotationText.indexOf('(');
  if (open === -1) return '';
  const value = annotationText.slice(open + 1);
  const match = value.match(/["']([^"']*)["']/);
  return match ? match[1] : '';
}

/** 读到一个注解的完整括号内容（可能跨行）。 */
function readAnnotation(lines, startIndex) {
  let text = lines[startIndex];
  let depth = (text.match(/\(/g) || []).length - (text.match(/\)/g) || []).length;
  let cursor = startIndex;
  while (depth > 0 && cursor + 1 < lines.length) {
    cursor++;
    text += '\n' + lines[cursor];
    depth += (lines[cursor].match(/\(/g) || []).length;
    depth -= (lines[cursor].match(/\)/g) || []).length;
  }
  return { text, endIndex: cursor };
}

/** 解析单个 controller 的所有写端点。 */
function parseController(source, controllerName) {
  const classPathMatch = source.match(
    /@RequestMapping\s*\(\s*(?:value\s*=\s*)?["']([^"']*)["']\s*\)[\s\S]{0,200}?public\s+class\s+\w+/
  );
  const classPath = classPathMatch ? classPathMatch[1] : '';
  const versionMatch = source.match(/@ApiVersion\s*\(\s*(?:value\s*=\s*)?["']([^"']+)["']/);
  const pathPrefix = versionMatch ? `/api/${versionMatch[1]}` : '';

  const methods = [];
  const lines = source.split('\n');
  const mappingPattern = new RegExp(`@(${HTTP_METHODS.map(m => m[0] + m.slice(1).toLowerCase() + 'Mapping').join('|')})`);

  for (let i = 0; i < lines.length; i++) {
    const annotation = lines[i].match(mappingPattern);
    if (!annotation) continue;

    const { text: annotationText, endIndex } = readAnnotation(lines, i);
    const http = annotation[1].replace('Mapping', '').toUpperCase();
    const methodPath = annotationPath(annotationText);

    let cursor = endIndex + 1;
    while (cursor < lines.length && !/\bpublic\s/.test(lines[cursor])) cursor++;
    if (cursor >= lines.length) continue;

    let signature = '';
    for (; cursor < lines.length; cursor++) {
      signature += lines[cursor] + '\n';
      const brace = signature.indexOf('{');
      if (brace !== -1) {
        signature = signature.slice(0, brace);
        break;
      }
    }
    const head = signature.match(/public\s+(?:static\s+|final\s+)*(.+?)\s+([A-Za-z_$][\w$]*)\s*\(/s);
    if (!head) continue;
    const methodName = head[2];

    const declaresIdempotencyHeader = /@RequestHeader\s*\([\s\S]*?["']Idempotency-Key["']/.test(signature);

    methods.push({
      controller: controllerName,
      method: methodName,
      http,
      path: pathPrefix + classPath + methodPath,
      declaresIdempotencyHeader,
      rawAnnotation: annotationText.trim(),
    });
  }
  return methods;
}

function readEvidence(relativePath) {
  const full = path.join(ROOT, relativePath);
  if (!fs.existsSync(full)) return null;
  return fs.readFileSync(full, 'utf8');
}

function ensureEvidence(entry, errors) {
  const evidence = entry.evidence;
  if (!Array.isArray(evidence) || evidence.length === 0) {
    errors.push(`${entry.controller}#${entry.method}: evidence 不能为空，必须给出可核对的实现锚点`);
    return [];
  }
  const sources = [];
  for (const item of evidence) {
    const source = readEvidence(item.file);
    if (source === null) {
      errors.push(`${entry.controller}#${entry.method}: 证据文件不存在 ${item.file}`);
      continue;
    }
    if (typeof item.token !== 'string' || item.token.length === 0) {
      errors.push(`${entry.controller}#${entry.method}: 证据 ${item.file} 缺少 token`);
      continue;
    }
    if (!source.includes(item.token)) {
      errors.push(`${entry.controller}#${entry.method}: ${item.file} 中找不到证据 "${item.token}"`);
      continue;
    }
    sources.push(source);
  }
  return sources;
}

function validateManifest(manifest, detectedMethods, options = {}) {
  const errors = [];
  const entries = Array.isArray(manifest.endpoints) ? manifest.endpoints : [];
  const entryKeys = new Map();
  for (const entry of entries) {
    const key = `${entry.controller}#${entry.method}`;
    if (entryKeys.has(key)) errors.push(`重复登记写端点：${key}`);
    entryKeys.set(key, entry);
  }

  const actualKeys = new Set(detectedMethods.map(item => `${item.controller}#${item.method}`));

  for (const method of detectedMethods) {
    const key = `${method.controller}#${method.method}`;
    if (!entryKeys.has(key)) {
      errors.push(
        `未登记写端点：${key} (${method.http} ${method.path || '/'})，请在 ${path.relative(ROOT, MANIFEST_FILE)} 声明幂等分类、冲突语义与证据`
      );
    }
  }

  for (const entry of entries) {
    const key = `${entry.controller}#${entry.method}`;
    const method = detectedMethods.find(item => item.controller === entry.controller && item.method === entry.method);
    if (!method || !actualKeys.has(key)) {
      errors.push(`登记条目已过期或方法名写错：${key} 在 controller 源码中不是写端点`);
      continue;
    }
    if (!HTTP_METHODS.includes(entry.http)) {
      errors.push(`${key}: http 必须是 ${HTTP_METHODS.join('/')}，实际为 ${entry.http}`);
    } else if (method.http !== entry.http) {
      errors.push(`${key}: 登记的 HTTP 方法 ${entry.http} 与源码 ${method.http} 不一致`);
    }
    if (typeof entry.path !== 'string' || method.path !== entry.path) {
      errors.push(`${key}: 登记路径 ${entry.path} 与源码 ${method.path || '/'} 不一致`);
    }

    if (!CLASSIFICATIONS.has(entry.classification)) {
      errors.push(`${key}: 未知 classification ${entry.classification}`);
      continue;
    }
    if (typeof entry.conflict !== 'string' || entry.conflict.trim().length < 20) {
      errors.push(`${key}: conflict 必须写清重复/并发提交时的稳定语义（至少 20 字符）`);
    }
    if (!RECORD_RISKS.has(entry.duplicateRecordRisk)) {
      errors.push(`${key}: duplicateRecordRisk 必须是 prevented/possible`);
    }
    if (!PROVIDER_CALLS.has(entry.duplicateProviderCalls)) {
      errors.push(`${key}: duplicateProviderCalls 必须是 deduped/possible/none`);
    }

    const sources = ensureEvidence(entry, errors);
    const joined = sources.join('\n');

    if (entry.duplicateProviderCalls === 'possible'
            && (typeof entry.reason !== 'string' || entry.reason.trim().length < 30)) {
      errors.push(`${key}: 重复提交可能重复调用模型/embedding Provider，必须用 reason 写清调用方责任或后果`);
    }

    switch (entry.classification) {
      case 'header-key': {
        if (!/Idempotency-Key/.test(joined)) {
          errors.push(`${key}: 登记为 header-key，但证据里没有 Idempotency-Key`);
        }
        if (entry.duplicateRecordRisk !== 'prevented') {
          errors.push(`${key}: header-key 必须声明 duplicateRecordRisk=prevented`);
        }
        if (typeof entry.idempotencyKeyRequired !== 'boolean') {
          errors.push(`${key}: header-key 必须声明 idempotencyKeyRequired（key 是必需还是可选）`);
        } else if (entry.idempotencyKeyRequired && entry.duplicateProviderCalls !== 'deduped') {
          errors.push(`${key}: 必需的 Idempotency-Key 必须声明 duplicateProviderCalls=deduped`);
        } else if (!entry.idempotencyKeyRequired && entry.duplicateProviderCalls === 'deduped') {
          errors.push(`${key}: 可选 Idempotency-Key 在未带 key 的重复提交下不可能 deduped`);
        }
        break;
      }
      case 'unique-key': {
        if (!/uk_\w+|UNIQUE|ON CONFLICT/u.test(joined)) {
          errors.push(`${key}: 登记为 unique-key，但证据里没有唯一约束或 ON CONFLICT`);
        }
        if (entry.duplicateRecordRisk !== 'prevented') {
          errors.push(`${key}: unique-key 必须声明 duplicateRecordRisk=prevented`);
        }
        break;
      }
      case 'cas': {
        if (!/expected[A-Z]|expected_|revision|version|If-Match/iu.test(joined)) {
          errors.push(`${key}: 登记为 cas，但证据里没有 expected/revision/version 条件写入锚点`);
        }
        if (entry.duplicateRecordRisk !== 'prevented') {
          errors.push(`${key}: cas 必须声明 duplicateRecordRisk=prevented`);
        }
        break;
      }
      case 'state-guard': {
        if (!/status|state|cancel|retry|terminal|幂等/iu.test(joined)) {
          errors.push(`${key}: 登记为 state-guard，但证据里没有状态/终态/幂等锚点`);
        }
        break;
      }
      case 'read-only': {
        if (entry.duplicateRecordRisk !== 'prevented') {
          errors.push(`${key}: read-only 必须声明 duplicateRecordRisk=prevented`);
        }
        if (typeof entry.reason !== 'string' || entry.reason.trim().length < 30) {
          errors.push(`${key}: read-only 必须写清为什么不会产生记录（reason >= 30 字符）`);
        }
        break;
      }
      case 'caller-responsibility': {
        if (typeof entry.reason !== 'string' || entry.reason.trim().length < 30) {
          errors.push(`${key}: caller-responsibility 必须写明非幂等影响与调用方责任（reason >= 30 字符）`);
        }
        if (entry.duplicateRecordRisk === 'possible' && !/责任|重试|调用方/u.test(entry.conflict)) {
          errors.push(`${key}: 重复可能多建一行时，conflict 必须写明调用方责任或重试语义`);
        }
        break;
      }
      default:
        break;
    }

    if (method.declaresIdempotencyHeader && entry.classification !== 'header-key') {
      errors.push(`${key}: 源码显式声明了 Idempotency-Key 头，登记却是 ${entry.classification}`);
    }
    if (entry.classification === 'header-key' && !method.declaresIdempotencyHeader && !/Idempotency-Key/.test(joined)) {
      errors.push(`${key}: 登记为 header-key，但源码未声明 Idempotency-Key 且证据无法证明`);
    }
  }

  for (const [key, entry] of entryKeys) {
    if (!actualKeys.has(key)) {
      errors.push(`登记条目已过期或方法名写错：${key} 在 controller 源码中不是写端点`);
    }
    if (entry && entry.classification === 'read-only' && !entry.reason) {
      errors.push(`${key}: read-only 缺少 reason`);
    }
  }

  return errors;
}

function collectMethods() {
  const methods = [];
  for (const root of CONTROLLER_ROOTS) {
    for (const file of readControllerSources(root)) {
      const source = fs.readFileSync(file, 'utf8');
      methods.push(...parseController(source, path.basename(file, '.java')));
    }
  }
  return methods;
}

function loadActual() {
  const manifest = JSON.parse(fs.readFileSync(MANIFEST_FILE, 'utf8'));
  return { manifest, methods: collectMethods() };
}

/** 生成待登记的骨架（人工分类用）。 */
function emitSkeleton() {
  const methods = collectMethods();
  methods.sort((a, b) => `${a.controller}#${a.method}`.localeCompare(`${b.controller}#${b.method}`));
  const endpoints = methods.map(method => ({
    controller: method.controller,
    method: method.method,
    http: method.http,
    path: method.path,
    classification: 'TODO',
    conflict: 'TODO',
    duplicateRecordRisk: 'prevented',
    duplicateProviderCalls: 'none',
    evidence: [],
  }));
  console.log(JSON.stringify({ description: 'TODO', endpoints }, null, 2));
}

function runSelfTest() {
  const fixture = `
@RestController
@RequestMapping("/api/v1/fixture")
public class FixtureController {
    @PostMapping
    public ResponseEntity<String> create(
            @RequestBody String body,
            @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.ok("ok");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long id) {
        return ResponseEntity.noContent().build();
    }
}
`;
  const methods = parseController(fixture, 'FixtureController');
  if (methods.length !== 2) fail(`self-test: 应解析出 2 个写端点，实际 ${methods.length}`);
  if (methods[0].path !== '/api/v1/fixture') fail(`self-test: 路径解析错误 ${methods[0].path}`);
  if (!methods[0].declaresIdempotencyHeader) fail('self-test: 未识别 Idempotency-Key 头');

  const evidence = [{ file: 'spring-ai-rag-core/src/main/java/com/springairag/core/chat/IdempotencyKeyValidator.java', token: 'IdempotencyKeyValidator' }];
  const base = {
    controller: 'FixtureController',
    method: 'create',
    http: 'POST',
    path: '/api/v1/fixture',
    classification: 'header-key',
    idempotencyKeyRequired: true,
    conflict: '相同 key 与相同请求重放同一结果；相同 key 不同请求返回 409',
    duplicateRecordRisk: 'prevented',
    duplicateProviderCalls: 'deduped',
    evidence,
  };
  const deleteEntry = {
    controller: 'FixtureController',
    method: 'remove',
    http: 'DELETE',
    path: '/api/v1/fixture/{id}',
    classification: 'state-guard',
    conflict: '重复删除第二次返回 204 或 404，但不会产生第二行删除记录',
    duplicateRecordRisk: 'prevented',
    duplicateProviderCalls: 'none',
    evidence: [{ file: 'spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagDocumentController.java', token: 'hardDeleteLocal' }],
  };

  const empty = validateManifest({ endpoints: [] }, methods);
  if (empty.length === 0) fail('self-test: 未登记写端点应被门禁发现');

  const wrongMethod = validateManifest({ endpoints: [{ ...base, http: 'PUT' }, deleteEntry] }, methods);
  if (!wrongMethod.some(error => error.includes('HTTP 方法'))) fail('self-test: HTTP 方法不一致应失败');

  const wrongPath = validateManifest({ endpoints: [{ ...base, path: '/api/v1/other' }, deleteEntry] }, methods);
  if (!wrongPath.some(error => error.includes('路径'))) fail('self-test: 路径不一致应失败');

  const missingEvidence = validateManifest({ endpoints: [{ ...base, evidence: [{ file: 'nope.java', token: 'x' }] }, deleteEntry] }, methods);
  if (!missingEvidence.some(error => error.includes('证据文件不存在'))) fail('self-test: 证据缺失应失败');

  const badHeaderClaim = validateManifest(
    { endpoints: [{ ...base, classification: 'read-only', reason: '只是一个只读端点，不会写入任何记录，因此无需幂等键' }, deleteEntry] },
    methods,
  );
  if (!badHeaderClaim.some(error => error.includes('Idempotency-Key'))) fail('self-test: 声明了幂等头却登记只读应失败');

  const optionalButDeduped = validateManifest(
    { endpoints: [{ ...base, idempotencyKeyRequired: false }, deleteEntry] },
    methods,
  );
  if (!optionalButDeduped.some(error => error.includes('可选 Idempotency-Key'))) {
    fail('self-test: 可选 key 却声称 deduped 应失败');
  }

  const possibleWithoutReason = validateManifest(
    { endpoints: [{ ...base, idempotencyKeyRequired: false, duplicateProviderCalls: 'possible' }, deleteEntry] },
    methods,
  );
  if (!possibleWithoutReason.some(error => error.includes('reason'))) {
    fail('self-test: possible provider calls 缺少 reason 应失败');
  }

  const stale = validateManifest(
    { endpoints: [{ controller: 'FixtureController', method: 'ghost', http: 'POST', path: '/x', classification: 'read-only', conflict: '不存在的端点不应出现在登记表里', duplicateRecordRisk: 'prevented', duplicateProviderCalls: 'none', reason: '这是一个不存在的端点，用来证明过期条目会被发现', evidence }, deleteEntry] },
    methods,
  );
  if (!stale.some(error => error.includes('登记条目已过期'))) fail('self-test: 过期登记条目应失败');

  const valid = validateManifest({ endpoints: [base, deleteEntry] }, methods);
  if (valid.length !== 0) fail(`self-test: 合法登记不应报错，实际：${valid.join('; ')}`);

  console.log('写端点幂等语义门禁 self-test 通过。');
}

function main() {
  const { manifest, methods } = loadActual();
  const errors = validateManifest(manifest, methods);
  if (errors.length > 0) {
    console.error('写端点幂等语义检查失败：');
    for (const error of errors) console.error(`  - ${error}`);
    process.exit(1);
  }
  console.log(`写端点幂等语义检查通过：${methods.length} 个写端点均已登记分类、冲突语义与证据。`);
}

if (process.argv.includes('--emit')) {
  emitSkeleton();
} else if (process.argv.includes('--self-test')) {
  try {
    runSelfTest();
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
} else {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
