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
 *   3. 传进那个 HttpServletRequest 位置的实参必须是"可能为 null"的：
 *      要么是字面量 `null`（Batch 816 的判据），要么是**一个被声明为
 *      HttpServletRequest 且初始化可能产出 null 的局部变量**（Batch 819 补的）。
 *
 * 第 3 条是关键。`uploadAndEmbed(files, collectionId, null, force, null, null)`
 * 也在传 null，但那些位置是 collectionKey / idempotencyKey，**不是**请求上下文——
 * 那是合法的便捷重载，不该被报。第 810/812 批次的经验是：判据一旦被误报淹没，
 * 就会被豁免成装饰品。
 *
 * ── 为什么 Batch 819 要加"变量"这一支 ──────────────────────────────────
 * 816 的判据只认字面量，于是漏掉了本仓库真实存在的一种形状：
 *
 *   HttpServletRequest currentRequest =
 *           RequestContextHolder.getRequestAttributes()
 *                   instanceof ServletRequestAttributes attributes
 *                   ? attributes.getRequest()
 *                   : null;                                   // ← 可能为 null
 *   return create(request, currentRequest);                    // ← 门禁看不见
 *
 * 这个形状比字面量更危险：读代码时 `: null` 那一支藏在三行之外，
 * 而调用点看上去只是一个普通的变量传递。Batch 819 删掉它时，
 * 816 的门禁对这份源码是**零报告**的。
 *
 * 收紧成"`HttpServletRequest` 声明 + 初始化整体为 null 或以 `: null` 收尾"，
 * 是为了让误报为零：`HttpServletRequest r = a == null ? b : c;` 会产出非 null，
 * 因此被放行——判据宁可漏报，也不该把正常代码叫成授权旁路。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { stripJavaComments } from './lib/java-source.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

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

/**
 * 去掉注释，**保留每一行**。判据要落在代码上而不是散文上：javadoc 里写一个
 * `search(request, null)` 的示例，不等于源码里有这次调用；而报错的行号必须
 * 还能对回真实文件。
 *
 * ── Batch 883：原来的实现是两条正则，它在真实树上吃掉了代码 ──────────────
 *
 * 旧实现是 `src.replace(块注释正则, '').replace(行注释正则, '')`。真正的破坏
 * 不在"字符串里的 `//`"，而在**块注释那条规则会从一条 `//` 注释内部起跳**：
 *
 *     // Serve /webui/assets/** from classpath:/static/webui/assets/
 *
 * 这行里有斜杠加星号（glob 的 `assets/**`）。块注释正则于是从那里开始找它的
 * 结束符，一路找到下面某个真实的 javadoc 结尾，把中间**整段真实代码**当成注释
 * 删掉。
 * `WebUiConfig.java` 里量到的四个"块注释"有两个是假的：
 *
 *     26..35 行  吞掉 registry.addResourceHandler(...) / .addResourceLocations(...)
 *     57..71 行  吞掉整个 webuiCatchAll controller 方法（@GetMapping、签名、
 *                if (path.startsWith(...))、两处 return、两个右花括号）
 *
 * 也就是说这道门禁在 main 上是对这个 controller 方法**完全失明**的，而门禁的
 * 全部价值就在于"不许漏"。方向是纯漏报——实测没有凭空造出任何一条发现——但
 * 漏掉的是一整个方法。
 *
 * 顺带：旧实现用空串替换，于是多行块注释会塌行，之后所有行号整体错位。改判据
 * 时若按行比对两版输出，会得到几千条"差异"，那全是这个塌行造成的假象（Batch 877
 * 已经在 `check-page-shell.mjs` 上栽过一次）。所以下面用空格替换，并且本批所有
 * 对账都按**扫描结果**比，不按行文本比。
 *
 * ── 为什么是本地实现，而不是复用前端那份 ────────────────────────────────
 *
 * `spring-ai-rag-webui/scripts/check-design-system.mjs` 导出的同名函数更完善，
 * 而且六个前端门禁都在用它，看起来应该统一。实测下来不能：`scripts/` 下十三道
 * 门禁的 import 图只有 `node:*` 内置和同目录兄弟脚本，而那份模块顶层
 * `import postcss from 'postcss'` 并 `import { buildOutputs } from
 * './build-design-tokens.mjs'`。引它等于把 WebUI 的 CSS 工具链拖进一道 Java
 * 门禁的启动路径——没在 `spring-ai-rag-webui` 跑过 `npm ci` 的检出连启动都做不到。
 * 宁可留一份各树自洽的实现，也不让后端门禁依赖前端依赖树。
 *
 * 已知局限：Java 文本块（三个双引号）内部按普通字符串处理，不做单独建模。方向
 * 仍然是只漏不误报（最多把载荷当注释抹掉，不会造出发现）。真实树里只有
 * `RetrievalEvaluationServiceImpl.java` 一个文本块含类注释序列，实测对本门禁
 * 无影响；真要收紧应该先在文本块里出现真实的声明。
 */


/**
 * 收集一个类里所有方法名 -> 参数类型列表的重载。
 * 只做粗粒度类型判定：是否含 HttpServletRequest，不试图解析完整签名。
 *
 * ── Batch 883：这个模式曾经看不见整整一层方法 ────────────────────────────
 * 旧模式要求 `换行 + \s{4} + [A-Za-z]… + \) + \s*\{`。两处收紧各自吞掉一类
 * **真实存在**的声明：
 *
 *   - `\)` 之后必须紧跟 `\{`，所以任何带 `throws` 子句的方法不可见。这一条在真实
 *     树上是有代价的：431 个 Java 文件里共 **101** 个带 `HttpServletRequest` 参数的
 *     声明，旧模式只认得 **96**。看不见的 5 处全部因 `throws` 而不可见——
 *     `doFilterInternal`（ApiKeyAuthFilter:110、RateLimitFilter:160）、
 *     `preHandle`（ApiSloHandlerInterceptor:56）、
 *     `afterCompletion`（:70）、`applyPostgresLimit`（RateLimitFilter:203）。
 *
 *     归因是量出来的，不是推的：只放宽 `throws` 恢复 5 处，只放宽缩进恢复 0 处。
 *     （第一版归因写成"缩进"是错的——逐行看签名首行看不到 `throws`，因为这 5 处
 *     的签名都跨了多行，`throws` 在末行。）
 *
 *     命中的恰好是最外层的授权边界层。`Batch 816` 立这条规则就是因为
 *     `ChatPrincipal.from(null)` 会 fail-open；而过滤器/拦截器层恰恰是最不该
 *     有一个"请求上下文可以缺失"的重载的地方。
 *
 *   - `\s{4}` 是**恰好四个**空白字符（定量化后没有更短的备选），所以缩进 8 空格
 *     起步的声明——内部类、匿名类——整条不可见。这一条今天在真实树上**命中 0 次**：
 *     101 个声明的缩进全部是 4。但它是"只要有人把方法写进内部类就永久失明"，
 *     触发条件与代码内容无关。
 *
 * 今天这 5 处都没有被同类转发过 null，所以它是**覆盖漏洞**而不是活跃缺陷——
 * 但漏检的方向是 fail-open，而触发条件只是"有人写一个 `throws`"。
 *
 * 判据放宽到"行首缩进 + 可选的 throws"，而不是把 4 换成别的数字：缩进宽度不是
 * 这条规则关心的东西，写死它等于把"Java 代码怎么排版"当成了契约。
 * 放宽后必须仍然拒绝非声明：实测 `if (x) {`、`for (...) {`、`catch (...) {`、
 * `} else {`、以及带接收者的调用 `client.send(Type) {` 五种形状都不匹配。
 */
export function collectOverloads(src) {
  const byName = new Map();
  const pattern =
    /^[ \t]+[A-Za-z][\w.<>\[\], ?]*?[ \t]+(\w+)[ \t]*\(([^)]*)\)[ \t]*(?:throws[ \t]+[\w., ]+)?\{/gm;
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

/**
 * Batch 819。收集本文件里"声明为 HttpServletRequest、且初始化可能产出 null"的
 * 局部变量名。
 *
 * 判据收紧到两种形状：初始化整体就是 `null`，或者是一个以 `: null` 收尾的三元。
 * `HttpServletRequest r = a == null ? b : c;` 产出的是非 null，因此放行。
 */
export function collectNullCarryingRequests(src) {
  const clean = stripJavaComments(src);
  const names = new Set();
  const decl = /HttpServletRequest\s+(\w+)\s*=/g;
  let m;
  while ((m = decl.exec(clean)) !== null) {
    // Walk to the terminating `;` at paren depth 0 so a multi-line initializer
    // is read whole. Stopping at the first `;` would only ever see `? a.getRequest()`
    // and miss the `: null` that follows three lines down — which is the case
    // this function exists for.
    let depth = 0;
    let end = -1;
    for (let i = m.index + m[0].length; i < clean.length; i += 1) {
      const ch = clean[i];
      if (ch === '(' || ch === '[') depth += 1;
      else if (ch === ')' || ch === ']') depth -= 1;
      else if (ch === ';' && depth === 0) { end = i; break; }
    }
    if (end < 0) continue;
    const init = clean.slice(m.index + m[0].length, end).trim();
    if (init === 'null' || /:\s*null\s*$/.test(init)) names.add(m[1]);
  }
  return names;
}

/** 实参是不是"可能为 null"：字面量 null，或上面收集到的变量名。 */
function isNullBearing(arg, nullCarriers) {
  if (arg === 'null') return 'a literal null';
  if (/^[A-Za-z_$][\w$]*$/.test(arg) && nullCarriers.has(arg)) {
    return `${arg}, a local declared as possibly-null HttpServletRequest`;
  }
  return null;
}

/** 从源码文本中找出 { line, name, argIndex, args } 形式的违规转发。 */
export function findNullRequestForwarding(src) {
  const clean = stripJavaComments(src);
  const overloads = collectOverloads(clean);
  const nullCarriers = collectNullCarryingRequests(src);
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
      const carrier = isNullBearing(args[idx], nullCarriers);
      if (!carrier) continue;
      findings.push({
        name,
        argIndex: idx,
        carrier,
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
  const files = walk(scanRoot);
  const findings = [];
  for (const file of files) {
    const rel = relative(scanRoot, file);
    for (const hit of findNullRequestForwarding(readFileSync(file, 'utf8'))) {
      findings.push({ file: rel, ...hit });
    }
  }

  // Batch 883: 原来的 `const allowlist = new Map()` 从来没有被填过任何东西，
  // 而 `allowlist.get(...)` 被读了四处——读起来像是"存在一条豁免通道"，
  // 实际上**没有任何途径**登记豁免。这道门禁本来就是不可豁免的（它的报错信息
  // 也确实没给出任何豁免写法），所以行为一直是对的，错的是代码在暗示一个不存在的能力。
  // 死代码比没有代码更糟：下一个读它的人会以为有一条路可以走。删掉。
  //
  // 连带删掉的还有紧随其后的 `const blocking = findings`——过滤 allowlist 之后它
  // 曾经有意义，现在它是一个指向另一个名字的常量，读代码的人得跳回去才知道两者
  // 是不是同一个东西。
  for (const f of findings) {
    console.log(
      `- [null-request-forwarding] ${f.file}:${f.line} forwards ${f.carrier} into the `
      + `HttpServletRequest position of ${f.name}(${f.args}). Both ChatPrincipal.from(null) `
      + 'and ApiKeyCollectionAccess.isUnrestricted(null) fail open, so a caller that reaches '
      + 'this overload gets unscoped data instead of an error. Call the signature that takes '
      + 'the request, or delete the overload.',
    );
  }

  if (findings.length > 0) {
    console.error(
      `\nNull-request forwarding check failed; ${findings.length} blocking finding(s) across `
      + `${files.length} source file(s).`,
    );
    process.exit(1);
  }
  console.log(
    `Null-request forwarding check passed; ${files.length} source file(s) scanned, no overload `
    + 'forwards a literal null or a possibly-null request local into an HttpServletRequest position.',
  );
}

if (isMainModule(import.meta.url)) {
  main();
}
