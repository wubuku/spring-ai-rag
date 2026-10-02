#!/usr/bin/env node
/**
 * 禁止把 `null` 转发进一个 `HttpServletRequest` 参数位。
 *
 * 背景（Batch 816）。仓储里有过一整族"只有测试够得着"的重载，它们把请求上下文
 * 丢成 `null`：
 *
 *   ResponseEntity<?> searchWithConfig(SearchRequest request) {
 *       return searchWithConfig(request, null);   // ← 这里
 *   }
 *
 * 危险的不是语法，是**丢完之后发生什么**。本仓库两个派生函数都对 `null` **fail-open**：
 *
 *   ChatPrincipal.from(null)          → local()            （本地兜底身份）
 *   ApiKeyCollectionAccess.isUnrestricted(null) → true      （"没有策略" = 不受限）
 *
 * 也就是说"传 null"不会被拒绝，而是被解释成"没有限制"。这类重载今天只有测试在调，
 * 所以还不是活漏洞；但它们是**授权旁路的地雷**：任何未来的同包代码误调其一，
 * 就会安静地拿到全作用域数据，而且编译通过、类型正确、没有任何告警。
 *
 * 判据刻意很窄，因为窄判据才不会被 allowlist 化：
 *   1. 只看**同一个类内**按名字转发（`return this.foo(...)`）的调用；
 *   2. 被转发的方法名必须存在一个**参数表里含 HttpServletRequest 的重载**；
 *   3. `null` 必须**按位置**落在那个 HttpServletRequest 参数上。
 *
 * 第 3 条是关键。`uploadAndEmbed(files, collectionId, null, force, null, null)`
 * 也在传 null，但那些位置是 collectionKey / idempotencyKey，**不是**请求上下文——
 * 那是合法的便捷重载，不该被报。第 810/812 批次的经验是：判据一旦被误报淹没，
 * 就会被豁免成装饰品。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;
const SRC_ROOT = join(ROOT, 'spring-ai-rag-core/src/main/java');
const REQUEST_TYPE = /HttpServletRequest/;

/** 按顶层逗号切分实参，忽略泛型、数组初始化和嵌套调用里的逗号。 */
export function splitTopLevelArgs(text) {
  const out = [];
  let depth = 0;
  let current = '';
  for (const ch of text) {
    if ('<(['.includes(ch)) depth += 1;
    else if ('>)]'.includes(ch)) depth -= 1;
    if (ch === ',' && depth === 0) {
      out.push(current.trim());
      current = '';
    } else {
      current += ch;
    }
  }
  if (current.trim()) out.push(current.trim());
  return out;
}

/** 去掉块注释与行注释，避免 javadoc 里的示例代码被当成真实调用。 */
function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
}

/**
 * 收集一个类里所有方法名 -> 参数类型列表的重载。
 * 只做粗粒度类型判定：是否含 HttpServletRequest，不试图解析完整签名。
 */
export function collectOverloads(src) {
  const byName = new Map();
  const pattern = /\n\s{4}[A-Za-z][\w.<>\[\], ?]*\s+(\w+)\s*\(([^)]*)\)\s*\{/g;
  let m;
  while ((m = pattern.exec(src)) !== null) {
    const name = m[1];
    const params = splitTopLevelArgs(m[2]);
    if (!byName.has(name)) byName.set(name, []);
    byName.get(name).push(params);
  }
  return byName;
}

/** 返回 HttpServletRequest 在某个重载参数表中的下标；没有则返回 -1。 */
export function requestParamIndex(params) {
  return params.findIndex((p) => REQUEST_TYPE.test(p));
}

/** 从源码文本中找出 { line, name, argIndex, args } 形式的违规转发。 */
export function findNullRequestForwarding(src) {
  const clean = stripComments(src);
  const overloads = collectOverloads(clean);
  const findings = [];

  const callPattern = /\b(\w+)\s*\(/g;
  let m;
  while ((m = callPattern.exec(clean)) !== null) {
    const name = m[1];
    const signatures = overloads.get(name);
    if (!signatures) continue;

    // Only unqualified calls, or `this.foo(...)`, count as a same-class forward.
    // Matching on the bare name alone flagged `helper.send(null)` whenever the
    // class happened to declare its own `send(HttpServletRequest)` — a
    // false positive, and false positives are what turn a rule into an
    // allowlist.
    const before = clean.slice(0, m.index);
    const qualifier = /(\w+)\s*[.?]?\s*$/.exec(before);
    const receiver = qualifier ? qualifier[1] : null;
    const isQualified = /[.?]\s*$/.test(before) && receiver !== 'this' && receiver !== 'super';
    if (isQualified) continue;

    // 取到配对的右括号
    let depth = 0;
    let i = m.index + name.length;
    const open = clean.indexOf('(', m.index);
    for (i = open; i < clean.length; i += 1) {
      if (clean[i] === '(') depth += 1;
      else if (clean[i] === ')') {
        depth -= 1;
        if (depth === 0) break;
      }
    }
    if (depth !== 0) continue;
    const args = splitTopLevelArgs(clean.slice(open + 1, i));

    for (const params of signatures) {
      if (params.length !== args.length) continue;
      const idx = requestParamIndex(params);
      if (idx < 0) continue;
      if (args[idx] !== 'null') continue;
      findings.push({
        name,
        argIndex: idx,
        line: clean.slice(0, open).split('\n').length,
        args: args.join(', ').replace(/\s+/g, ' ').slice(0, 90),
      });
      break;
    }
    callPattern.lastIndex = i;
  }
  return findings;
}

function walk(dir, acc = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, acc);
    else if (entry.endsWith('.java')) acc.push(full);
  }
  return acc;
}

function main() {
  // The self-test points this at a fixture tree so it can assert the exit code,
  // not just the detector. A gate whose reporting path is never exercised is a
  // gate that cannot fail, and this repository has produced four of those.
  const rootOverride = process.env.NULL_REQUEST_FORWARDING_ROOT;
  const scanRoot = rootOverride ? rootOverride : SRC_ROOT;
  const allowlist = new Map();
  const findings = [];
  for (const file of walk(scanRoot)) {
    const rel = relative(scanRoot, file);
    for (const hit of findNullRequestForwarding(readFileSync(file, 'utf8'))) {
      findings.push({ file: rel, ...hit });
    }
  }

  for (const f of findings) {
    const exemptions = allowlist.get(f.file) ?? [];
    if (exemptions.includes(f.name)) continue;
    console.log(
      `- [null-request-forwarding] ${f.file}:${f.line} forwards a literal null into the `
      + `HttpServletRequest position of ${f.name}(${f.args}). Both ChatPrincipal.from(null) `
      + 'and ApiKeyCollectionAccess.isUnrestricted(null) fail open, so a caller that reaches '
      + 'this overload gets unscoped data instead of an error. Call the signature that takes '
      + 'the request, or delete the overload.',
    );
  }

  const blocking = findings.filter(
    (f) => !(allowlist.get(f.file) ?? []).includes(f.name),
  );
  const scanned = walk(scanRoot).length;
  if (blocking.length > 0) {
    console.error(
      `\nNull-request forwarding check failed; ${blocking.length} blocking finding(s) across `
      + `${scanned} source file(s).`,
    );
    process.exit(1);
  }
  console.log(
    `Null-request forwarding check passed; ${scanned} source file(s) scanned, no overload `
    + `forwards a literal null into an HttpServletRequest position.`,
  );
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop())) {
  main();
}
