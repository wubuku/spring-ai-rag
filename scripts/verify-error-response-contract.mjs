#!/usr/bin/env node
/**
 * 错误响应契约门禁：错误体只有一套 schema，每个出口都必须遵守。
 *
 * ## 为什么需要这道门禁
 *
 * `verify-error-code-catalog.mjs` 管的是「码」：码在不在 `ErrorCode` 里、
 * 状态码对不对、builder 体有没有 error/status/一句话。它不看三个东西：
 *
 *   1. **traceId** —— 同一个请求的错误能否被关联到日志。控制器层由
 *      `GlobalExceptionHandler` 统一 `setTraceId`；过滤器在 DispatcherServlet
 *      之前短路，只能自己写体，历史上一个都没有写。
 *   2. **失败分类头** —— `X-RAG-Failure-Class` 由两个机制补：控制器层的
 *      `FailureClassResponseBodyAdvice`，以及 order 1000 的
 *      `FailureClassResponseHeaderFilter`。后者**只覆盖它内层的过滤器**；
 *      order 更靠前的 auth/rate-limit/capability 短路返回时，它还没进入链。
 *      漏写头的过滤器，客户端拿不到与体一致的分类。
 *   3. **容器级错误** —— 过滤器抛出的异常不经过 `@RestControllerAdvice`，
 *      会落到 Spring Boot 的 `BasicErrorController`，返回
 *      `{timestamp,status,error,path}`：没有 `error` 码、没有 `failureClass`、
 *      没有 traceId、没有分类头。本批新增 `RagErrorController` 补齐。
 *
 * ## 规则
 *
 *   R1 `@ExceptionHandler` 必须返回统一的错误信封（`ErrorResponse` 或
 *      `OpenAiErrorResponse`），不能返回 `String`/`Object`/`void`。
 *   R2 所有手工 `ErrorResponse.builder()` 链必须带 `traceId`。
 *   R3 任何写错误体到 `HttpServletResponse` 的代码，必须在写出前调用
 *      `FailureClassHttpSupport.apply(...)` 或 `NativeErrorResponseWriter.write(...)`
 *      （或显式写 `FailureClassHttpSupport.HEADER`）。
 *   R4 产出错误体的文件不得把堆栈/异常类名塞进响应（`printStackTrace`、
 *      `.getStackTrace()`、`.detail(e.toString())`）；且 `@ExceptionHandler(Exception.class)`
 *      必须使用 `ErrorMessageSanitizer.sanitize` 脱敏。
 *   R5 必须存在一个 `implements ErrorController` 且返回 `ErrorResponse` 的出口，
 *      让容器级失败也走同一套体与头。
 *
 * ## 已知边界
 *
 * `ErrorResponse.of(...)` 工厂路径不强制 traceId：18 个调用点所在方法都没有
 * `HttpServletRequest` 在作用域（`verify-error-code-catalog.mjs` 已经量过）。
 * 这条是有记录的例外，且方向是往宽松开——手工 builder 才是被检查的那条路。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { stripJavaComments } from './lib/java-source.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

const ROOT = new URL('..', import.meta.url).pathname;
const SRC_ROOTS = [
  join(ROOT, 'spring-ai-rag-api/src/main/java'),
  join(ROOT, 'spring-ai-rag-core/src/main/java'),
];

const lineOf = (src, index) => src.slice(0, index).split('\n').length;

/** 配对括号，返回右括号下标；找不到返回 -1。 */
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

const UNIFIED_ENVELOPES = ['ErrorResponse', 'OpenAiErrorResponse'];

/**
 * 分析单个源码文件。导出给自测使用：自测用 fixture 证明每条规则都能拒绝。
 *
 * @param {string} rel 仓库相对路径，只用于报错
 * @param {string} raw 源码
 * @returns {Array<{rule: string, line: number, message: string}>}
 */
export function analyzeFile(rel, raw) {
  if (!rel.endsWith('.java')) return [];
  const src = stripJavaComments(raw);
  const violations = [];
  const push = (rule, index, message) =>
    violations.push({ rule, line: lineOf(src, index), message: `${rel}: ${message}` });

  // ---- R1：异常处理器返回值必须统一 -------------------------------------
  for (const m of src.matchAll(/@ExceptionHandler/g)) {
    const paren = src.indexOf('(', m.index);
    const parenClose = paren < 0 ? -1 : matchParen(src, paren);
    if (parenClose < 0) continue;
    const after = src.slice(parenClose + 1);
    // 方法签名：public <返回类型> name(
    const signature = /^\s*(?:public|protected|private)?[\s\w<>[\], ?]*?\s([\w<>[\], ?]+)\s+\w+\s*\(/
      .exec(after);
    if (!signature) continue;
    const returnType = signature[1].replace(/\s+/g, '');
    const unified = UNIFIED_ENVELOPES.some((type) => returnType.includes(type));
    if (!unified) {
      push('R1-unified-envelope', m.index,
        `@ExceptionHandler returns "${returnType}", which is not one of `
        + `${UNIFIED_ENVELOPES.join('/')}. An error exit that invents its own shape `
        + 'cannot be measured against the contract.');
    }
  }

  // ---- R2：手工 builder 链必须带 traceId -----------------------------
  for (const m of src.matchAll(/ErrorResponse\.builder\(\)/g)) {
    const open = src.indexOf('(', m.index);
    const close = matchParen(src, open);
    if (close < 0) continue;
    const tail = src.slice(close + 1);
    const buildAt = tail.indexOf('.build()');
    if (buildAt < 0) continue;
    const body = tail.slice(0, buildAt);
    const calls = new Set();
    for (const call of body.matchAll(/\.(\w+)\s*\(/g)) calls.add(call[1]);
    // build() 之后的 setTraceId(...) 也算
    for (const call of tail.slice(buildAt).matchAll(/\.set(\w+)\s*\(/g)) {
      calls.add(call[1].charAt(0).toLowerCase() + call[1].slice(1));
    }
    if (!calls.has('traceId')) {
      push('R2-trace-id', m.index,
        'hand-assembled ErrorResponse.builder() chain does not set traceId; '
        + 'the request becomes unjoinable to its logs.');
    }
  }

  // ---- R3：写错误体前必须设置失败分类头 -------------------------------
  const writesErrorEnvelope = /ErrorResponse\.builder\(\)|ErrorResponse\.of\(|OpenAiErrorResponse\.of\(/
    .test(src);
  if (writesErrorEnvelope) {
    for (const m of src.matchAll(/\.getWriter\(\)/g)) {
      const windowStart = Math.max(0, m.index - 4000);
      const window = src.slice(windowStart, m.index);
      const setsHeader = /FailureClassHttpSupport\.apply\s*\(/.test(window)
        || /NativeErrorResponseWriter\.write\s*\(/.test(window)
        || /FailureClassHttpSupport\.HEADER/.test(window);
      if (!setsHeader) {
        push('R3-failure-class-header', m.index,
          'writes an error body without setting X-RAG-Failure-Class in the '
          + 'preceding lines. Filters ordered before FailureClassResponseHeaderFilter '
          + 'never reach it, so nothing downstream adds the header for them.');
      }
    }
  }

  // ---- R4：不把堆栈/内部类名写进响应 ---------------------------------
  if (writesErrorEnvelope) {
    for (const pattern of [/printStackTrace\s*\(/g, /\.getStackTrace\s*\(\s*\)/g, /\.detail\s*\(\s*e\.toString\s*\(\s*\)/g]) {
      for (const m of src.matchAll(pattern)) {
        push('R4-internal-leak', m.index,
          'error-response code references a stack trace or exception.toString(); '
          + 'use ErrorMessageSanitizer.sanitize so internal types do not reach the wire.');
      }
    }
  }
  // 只在"确实把异常 message 放进响应"的文件里要求脱敏：固定文案的 5xx 处理器
  // 不会泄漏，强制它多引用一个方法只会制造无意义的改动。
  if (/@ExceptionHandler\s*\(\s*Exception\.class\s*\)/.test(src)
      && /\.getMessage\s*\(\s*\)/.test(src)
      && !/safeDetail\s*\(|sanitize\s*\(/.test(src)) {
    push('R4-internal-leak', 0,
      'handles Exception.class and forwards getMessage() without sanitize(...); '
      + 'the exit can leak fully-qualified class names or stack frames.');
  }

  return violations;
}

/** R5 是全局断言，不是单文件断言。 */
export function checkContainerErrorExit(files) {
  const hits = [];
  for (const { rel, raw } of files) {
    if (!rel.endsWith('.java')) continue;
    const src = stripJavaComments(raw);
    if (/implements ErrorController/.test(src) && /ErrorResponse/.test(src)) {
      hits.push({ rel, raw: src });
    }
  }
  if (hits.length === 0) {
    return [{
      rule: 'R5-container-error-exit',
      line: 0,
      message: 'no class implements ErrorController and returns ErrorResponse. '
        + 'Container-level failures (exceptions thrown by filters) then fall through '
        + 'to Spring Boot\'s BasicErrorController, whose body has no error code, no '
        + 'failureClass, no traceId, and no X-RAG-Failure-Class header.',
    }];
  }
  const violations = [];
  for (const { rel, raw } of hits) {
    if (!/FailureClassHttpSupport\.HEADER/.test(raw)) {
      violations.push({
        rule: 'R5-container-error-exit',
        line: 0,
        message: `${rel}: the ErrorController does not set X-RAG-Failure-Class.`,
      });
    }
  }
  return violations;
}

function walk(dir, acc = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) walk(full, acc);
    else acc.push(full);
  }
  return acc;
}

export function scanTree(roots = SRC_ROOTS) {
  const files = roots.flatMap((root) => walk(root))
    .map((full) => ({ rel: relative(ROOT, full), raw: readFileSync(full, 'utf8') }));
  const violations = files.flatMap(({ rel, raw }) => analyzeFile(rel, raw));
  violations.push(...checkContainerErrorExit(files));
  return { files, violations };
}

function main() {
  const { files, violations } = scanTree();
  if (violations.length > 0) {
    console.error(`Error-response contract violations: ${violations.length}`);
    for (const violation of violations) {
      console.error(`  [${violation.rule}] ${violation.message}`);
    }
    process.exit(1);
  }
  const handlers = files.filter(({ raw }) => /@ExceptionHandler/.test(raw)).length;
  console.log(`PASS: error-response contract holds (${files.length} main sources, `
    + `${handlers} exception-handler file(s), rules R1-R5)`);
}

if (isMainModule(import.meta.url)) {
  main();
}
