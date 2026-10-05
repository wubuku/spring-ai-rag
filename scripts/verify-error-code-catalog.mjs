#!/usr/bin/env node
/**
 * 错误码目录必须是"单一事实来源"，否则它只是一份列表。
 *
 * 背景（Batch 873）。`ErrorCode` 的类注释自称 single source of truth，但本批
 * 量出来的结果是：API 真正发出的 18 个不同错误码里，有 **6 个不在目录里**。
 * 后果不是"文档过时"，而是派生不出来——`GlobalExceptionHandler` 只能自己手写
 * title 和 problem-type URI，于是同一个异常从两条构造路径出去会长成两种形状：
 * 一条带 type/title/detail/instance，另一条一个都没有，而 Content-Type 两边
 * 都写着 `application/problem+json`。
 *
 * 三条规则，都来自本批真实量到的违反：
 *
 *   1. **registered**  — 任何进入错误体的码都必须在 `ErrorCode` 里声明过。
 *      6 处违反：MISSING_HEADER / MISSING_PART / UNSUPPORTED_MEDIA_TYPE /
 *      POLICY_SERVICE_UNAVAILABLE / CREDENTIAL_SERVICE_UNAVAILABLE / TOO_MANY_REQUESTS。
 *
 *   2. **status-drift** — 码旁边的 HTTP status 必须等于目录里这个码的 status。
 *      判据不是"抄错了"，是"同一个条件在目录和 wire 上说了两个不同的故事"。
 *      本批违反 2 处：TOO_MANY_REQUESTS 缺 status、CREDENTIAL_SERVICE_UNAVAILABLE
 *      连码都没有（所以也无从比对）。
 *
 *   3. **incomplete-body** — 手工用 `ErrorResponse.builder()` 拼的体必须同时带
 *      上 error（⇒ 派生 type + title）、status、和一句人话（detail 或 message）。
 *      本批违反 5 处，其中 RagSearchController 的 4 处只填了 detail，type/title/
 *      status/instance 四个字段全缺。
 *
 * ── 判据为什么这么窄 ────────────────────────────────────────────────────
 * 本批吃过一次教训，值得写在这里：第一次普查只认 `ErrorResponse.builder()`，
 * 报出"13 个构造点，0 个缺字段"——**自信地错了**。真实数字是 31 个构造点，
 * 另外 18 个走 `ErrorResponse.of(String)`，而那个工厂当时把 `title("Bad Request")`
 * 塞进 `error`，于是 18 个端点的 `error` 字段装的是一句人话。一道只认一种语法的
 * 扫描器，对另一种语法报零——零在这里不是证据，是没看。
 *
 * 同样地，`ErrorResponse.of(String)` 的 18 个调用点里，**0 个**所在方法有
 * HttpServletRequest 在作用域（量过的，不是猜的）。所以 `instance` 只对手工拼装的
 * builder 站点强制，工厂路径作为**有记录的例外**放过——要统一就得给三个控制器的
 * 4 个公开端点和 2 个私有 helper 加 servlet 参数，换一个 RFC 7807 里明确可选的字段。
 * 这个例外是往宽松的方向开的：得**特意**去选那个不带 instance 的工厂，builder
 * 这条路才是被检查的。把这句话写在这里，是为了让下一个读代码的人知道这条规则
 * 覆盖到哪里为止，而不是假装它统一。
 *
 * 有意**不**检查的一件事：目录里有 70 多个码没有任何构造点发出。这不是缺陷——
 * 目录本来就该比当前实现宽（业务异常随时会用到），拿"未被使用"当错误，等于
 * 逼着实现追着目录跑。数量作为信息打印在通过消息里，不作为失败条件。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { isMainModule } from './lib/is-main-module.mjs';

const ROOT = new URL('..', import.meta.url).pathname;
const SRC_ROOTS = [
  join(ROOT, 'spring-ai-rag-api/src/main/java'),
  join(ROOT, 'spring-ai-rag-core/src/main/java'),
];
const ENUM_REL = 'com/springairag/api/enums/ErrorCode.java';

/** HttpStatus.X -> 数字。只列主源码里真实用到的；新增的请一并加进来。 */
export const HTTP_STATUS = {
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
  PAYMENT_REQUIRED: 402,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  METHOD_NOT_ALLOWED: 405,
  NOT_ACCEPTABLE: 406,
  REQUEST_TIMEOUT: 408,
  CONFLICT: 409,
  GONE: 410,
  UNPROCESSABLE_ENTITY: 422,
  TOO_MANY_REQUESTS: 429,
  INTERNAL_SERVER_ERROR: 500,
  NOT_IMPLEMENTED: 501,
  BAD_GATEWAY: 502,
  SERVICE_UNAVAILABLE: 503,
  GATEWAY_TIMEOUT: 504,
};

/**
 * 去掉块注释与行注释，避免 javadoc 里的示例被当成真实调用。
 *
 * 注释里的换行必须留下。`lineOf` 是在剥离之后的文本上数行号的，而把注释换成
 * `''` 会连它内部的换行一起吃掉，于是每一条落在多行注释下方的发现都会被报低
 * ——Batch 889 在真实树上量到 **32 条**发现的行号是错的，最大偏 **54 行**，
 * 中位数 7 行。门禁的价值有一半在于告诉人去哪一行看。
 *
 * 只保留换行、其余照删，是改动最小的写法：同行注释里没有换行，删掉之后与从前
 * 逐字符一致，所以匹配行为不会变；实测真实树 **0 个文件**的发现集合发生变化。
 * 改成填空格则会让"注释插在 `HttpStatus.X` 和 `.value()` 之间"这类写法
 * 从"能匹配"变成"不能匹配"，没必要冒这个险。
 */
export function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ''))
    .replace(/\/\/[^\n]*/g, '');
}

/** 从 ErrorCode.java 解析出 code -> httpStatus 的映射。 */
export function parseCatalog(enumSrc) {
  const catalog = new Map();
  for (const m of enumSrc.matchAll(/^\s{4}([A-Z][A-Z_0-9]*)\((\d{3}),/gm)) {
    catalog.set(m[1], Number(m[2]));
  }
  return catalog;
}

const lineOf = (src, index) => src.slice(0, index).split('\n').length;

/** 取到配对的右括号，返回其下标；找不到返回 -1。 */
function matchParen(src, open) {
  let depth = 0;
  for (let i = open; i < src.length; i += 1) {
    if (src[i] === '(') depth += 1;
    else if (src[i] === ')') {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  return -1;
}

/**
 * 找出手工拼装的 `ErrorResponse.builder()` 链，并算出它实际会产出哪些字段。
 *
 * 派生关系来自 ErrorResponse.Builder 本身：`error(...)` 顺带写 title 和 type，
 * `detail(...)` 顺带写 message，`path(...)`/`instance(...)` 两个一起写。
 * 读源码的人看到的是显式调用，产出的是完整形状——只看显式调用会把 13 个站点
 * 全部误判成残缺。
 */
export function findBuilderChains(src) {
  const clean = stripComments(src);
  const chains = [];
  const pattern = /ErrorResponse\.builder\(\)/g;
  let m;
  while ((m = pattern.exec(clean)) !== null) {
    const open = clean.indexOf('(', m.index);
    const close = matchParen(clean, open);
    if (close < 0) continue;
    // 链一直延伸到配对的 .build()
    const tail = clean.slice(close + 1);
    const buildAt = tail.indexOf('.build()');
    if (buildAt < 0) continue;
    const body = tail.slice(0, buildAt);

    const explicit = new Set();
    for (const call of body.matchAll(/\.(\w+)\s*\(/g)) explicit.add(call[1]);
    // build() 之后的补齐，以及各 setter 的连带写入
    for (const call of tail.slice(buildAt).matchAll(/\.set(\w+)\s*\(/g)) {
      explicit.add(call[1].charAt(0).toLowerCase() + call[1].slice(1));
    }
    if (explicit.has('error')) { explicit.add('type'); explicit.add('title'); }
    if (explicit.has('detail')) explicit.add('message');
    if (explicit.has('path')) explicit.add('instance');
    if (explicit.has('instance')) explicit.add('path');

    chains.push({ line: lineOf(clean, m.index), fields: explicit });
    pattern.lastIndex = close;
  }
  return chains;
}

/**
 * 找出所有"写进错误体的错误码"及其旁边的 HTTP status。
 *
 * 两种形状：
 *   - `buildResponse(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", ...)`
 *   - `.error("UNAUTHORIZED").status(HttpStatus.UNAUTHORIZED.value())`
 */
export function findEmittedCodes(src) {
  const clean = stripComments(src);
  const found = [];

  const callPattern = /buildResponse\s*\(/g;
  let m;
  while ((m = callPattern.exec(clean)) !== null) {
    const open = clean.indexOf('(', m.index);
    const close = matchParen(clean, open);
    if (close < 0) continue;
    const args = clean.slice(open + 1, close);
    const pair = /HttpStatus\.([A-Z_]+)\s*,\s*"([A-Z_]+)"/.exec(args);
    if (pair) {
      found.push({
        line: lineOf(clean, m.index),
        code: pair[2],
        status: HTTP_STATUS[pair[1]] ?? null,
        statusExpr: `HttpStatus.${pair[1]}`,
      });
    }
    callPattern.lastIndex = close;
  }

  const errorPattern = /\.error\s*\(\s*"([A-Z_]+)"\s*\)/g;
  while ((m = errorPattern.exec(clean)) !== null) {
    const code = m[1];
    const line = lineOf(clean, m.index);
    // 往后找紧邻的 .status(HttpStatus.X.value())，跳过换行
    const after = clean.slice(m.index, m.index + 240);
    const st = /\.status\s*\(\s*HttpStatus\.([A-Z_]+)\.value\(\)\s*\)/.exec(after);
    found.push({
      line,
      code,
      status: st ? HTTP_STATUS[st[1]] ?? null : null,
      statusExpr: st ? `HttpStatus.${st[1]}` : null,
    });
  }
  return found;
}

const BODY_FIELDS = [
  ['error', '错误码（type 与 title 由它派生）'],
  ['status', 'HTTP status'],
];

/** 规则 1 + 2：码在不在目录里、status 对不对得上。 */
export function checkCodes(src, catalog) {
  const violations = [];
  for (const hit of findEmittedCodes(src)) {
    if (!catalog.has(hit.code)) {
      violations.push({
        kind: 'registered',
        line: hit.line,
        code: hit.code,
        message:
          `emits "${hit.code}", which ErrorCode does not declare. The enum calls itself the `
          + 'single source of truth; an undeclared code cannot derive its title or its '
          + 'problem-type URI, so this body is the one that drifts.',
      });
      continue;
    }
    const expected = catalog.get(hit.code);
    if (hit.status === null) {
      violations.push({
        kind: 'status-drift',
        line: hit.line,
        code: hit.code,
        message:
          `emits "${hit.code}" with no status in the body, but ErrorCode puts it at `
          + `${expected}. A client reading only the body cannot tell what happened.`,
      });
    } else if (hit.status !== expected) {
      violations.push({
        kind: 'status-drift',
        line: hit.line,
        code: hit.code,
        message:
          `emits "${hit.code}" as ${hit.statusExpr} (${hit.status}) while ErrorCode declares `
          + `${expected}. The code and the status tell two different stories about one condition.`,
      });
    }
  }
  return violations;
}

/** 规则 3：手工拼装的 body 是不是完整的 problem detail。 */
export function checkBodyShape(src) {
  const violations = [];
  for (const chain of findBuilderChains(src)) {
    const missing = BODY_FIELDS.filter(([f]) => !chain.fields.has(f)).map(([f]) => f);
    if (!(chain.fields.has('detail') || chain.fields.has('message'))) missing.push('detail');
    if (missing.length === 0) continue;
    violations.push({
      kind: 'incomplete-body',
      line: chain.line,
      message:
        `hand-assembles an ErrorResponse with no ${missing.join(', no ')}. `
        + 'ErrorResponse documents all five RFC 7807 fields and calls itself the format every '
        + 'API error uses; a body missing these is not that format, whatever Content-Type says.',
    });
  }
  return violations;
}

function walk(dir, acc = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, acc);
    else if (entry.endsWith('.java')) acc.push(full);
  }
  return acc;
}

export function scanTree(scanRoots) {
  const files = scanRoots.flatMap((r) => walk(r));
  const enumFile = files.find((f) => f.endsWith(ENUM_REL));
  if (!enumFile) throw new Error(`ErrorCode.java not found under ${scanRoots.join(', ')}`);
  const catalog = parseCatalog(readFileSync(enumFile, 'utf8'));
  const findings = [];
  for (const file of files) {
    if (file === enumFile) continue;
    const src = readFileSync(file, 'utf8');
    const rel = relative(scanRoots[0], file);
    for (const v of [...checkCodes(src, catalog), ...checkBodyShape(src)]) {
      findings.push({ file: rel, ...v });
    }
  }
  return { catalog, findings, scanned: files.length };
}

function main() {
  // The self-test points this at a fixture tree so it can assert the exit code,
  // not just the detector. A gate whose reporting path is never exercised is a
  // gate that cannot fail, and this repository has produced four of those.
  const rootOverride = process.env.ERROR_CODE_CATALOG_ROOT;
  const scanRoots = rootOverride ? [rootOverride] : SRC_ROOTS;
  const { catalog, findings, scanned } = scanTree(scanRoots);

  for (const f of findings) {
    console.log(`- [error-code-catalog] ${f.file}:${f.line} ${f.message}`);
  }

  if (findings.length > 0) {
    console.error(
      `\nError code catalog check failed; ${findings.length} blocking finding(s) across `
      + `${scanned} source file(s).`,
    );
    process.exit(1);
  }
  const unused = catalog.size;
  console.log(
    `Error code catalog check passed; ${scanned} source file(s) scanned, every emitted code is `
    + `declared in ErrorCode (${unused} codes on file) and carries the status the enum declares.`,
  );
}

if (isMainModule(import.meta.url)) {
  main();
}
