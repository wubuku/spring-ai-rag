#!/usr/bin/env node
/**
 * 禁止 controller 出现第二个构造器（任何可见性）。
 *
 * 背景（Batch 823）。Batch 819 的普查判据是"public 且没有映射注解的方法"，
 * 抓到了 11 个只有测试够得着的重载。Batch 823 顺着同一个思路去查构造器，
 * 立刻发现那道判据的边界在两个方向上都漏：
 *
 *   1. **有映射注解的便捷构造器**。`RagDocumentController` 等 7 个 controller
 *      各自有一个第二公开构造器，它把缺省参数填好再委托给主构造器。
 *      危害不是它能被谁调用（主源码零调用，实测），是它**替调用方决定**：
 *      `new RagChatController(a, b, c, d, null)` 读起来像"传了 5 个参数"，
 *      实际第 5 位是 `retrievalScopeResolver`——真实装配里它是个 bean，
 *      于是每个测试都在无声地把它置空。`RagMetricsController` 的 Javadoc 甚至
 *      写着 "Backward-compatible constructor for existing extensions and unit
 *      fixtures"，而**没有任何 extension**。
 *
 *   2. **包私有构造器**。这是 823 批自己踩到的：普查脚本按 `public ` 关键字
 *      匹配，得出"0 个 controller 有多个构造器"，而
 *      `RagSearchController` 实际有 3 个构造器（1 public + 2 package-private）。
 *      那两个包私有构造器选的是**另一种行为模式**：它们把
 *      `retrievalScopeResolver` 置 null，于是控制器走进
 *      `else` 分支，用 `legacyCollectionDocumentResolver` 先把集合过滤展开成
 *      文档 id。生产装配永远走不到（`CollectionRetrievalScopeResolver` 是
 *      无条件 `@Component`），而那个 `else` 分支里
 *      `legacyCollectionDocumentResolver` 是**无守卫解引用**——
 *      一旦真的走到就是 NPE。它是一条"生产不可达 + 走到就炸"的路，
 *      唯一的调用方是 2 个测试文件。
 *
 * 判据：**一个 controller 至多声明一个构造器，不区分可见性。**
 *
 * 为什么不写成"至多一个 public"：那就是 823 批自己证明有盲区的那条判据。
 * 包私有构造器对同包的测试和任何同包类都同样可达，可见性不改变
 * "只有测试会调"这个事实。
 *
 * 为什么不留"登记理由"的放行阀：Batch 821 实测过，理由这道阀会连
 * "结构上根本看不见的声明"一起放掉，于是门禁在真实树上 exit 0 而普查数
 * 从 14 掉到 12——一次绿色运行不再意味着任何东西。这条规则没有例外：
 * 真的需要第二个构造器时，正确做法是让它不再是 controller 的构造器。
 *
 * ── 怎么数（实现上的坑，写下来是因为我自己先踩了）────────────────────
 *   1. **先按"括号深度 = 类体第一层"定位**，不看修饰符。Batch 823 的普查脚本
 *      要求源码里出现 `public ` 才算，于是漏掉了两个包私有构造器。
 *   2. **先把注释和字符串字面量的内容置空**再匹配。否则
 *      `// 见 Foo(...)` 或 `"https://…/Foo("` 会被当成构造器，
 *      `new Foo(` 和 `@Foo(` 同理。
 *   3. **不扫嵌套/匿名类**。深度过滤天然做到这件事，不需要额外判断。
 *
 * Run: node scripts/verify-controller-constructor-count.mjs
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;
const SRC_ROOT = join(ROOT, 'spring-ai-rag-core/src/main/java');
// 判据允许全限定名的写法：`@RestController` 与
// `@org.springframework.web.bind.annotation.RestController` 都要认。
// Batch 823 的普查探针漏掉过全限定名的 `@Autowired`，同一个错不该犯第二次；
// 下面的自测里有一条就是专门钉这个形状的。

/**
 * 把注释与字符串/字符字面量的**内容**替换成空格，保留换行以维持行号。
 * 这样后面匹配构造器声明时，注释和字面量里的文本都不可见。
 */
export function neutralize(source) {
  const out = source.split('');
  let i = 0;
  const blank = (from, to) => {
    for (let k = from; k < to && k < out.length; k += 1) {
      if (out[k] !== '\n') out[k] = ' ';
    }
  };
  while (i < source.length) {
    const c = source[i];
    const next = source[i + 1];
    if (c === '/' && next === '/') {
      let j = i;
      while (j < source.length && source[j] !== '\n') j += 1;
      blank(i, j);
      i = j;
    } else if (c === '/' && next === '*') {
      let j = i + 2;
      while (j < source.length && !(source[j] === '*' && source[j + 1] === '/')) j += 1;
      blank(i, Math.min(j + 2, source.length));
      i = j + 2;
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < source.length && source[j] !== c) {
        if (source[j] === '\\') j += 1;
        j += 1;
      }
      blank(i, Math.min(j + 1, source.length));
      i = j + 1;
    } else {
      i += 1;
    }
  }
  return out.join('');
}

/**
 * 返回 body 每个字符的括号深度。body 已经**在**类的大括号之内，
 * 所以起始深度是 1：类体第一层的构造器正好落在 1，嵌套类的落在 2 及以下。
 * 从 0 起步会让整道门禁数不出任何构造器——这正是自测第一批用例抓到的。
 */
function depthMap(body) {
  const depth = new Array(body.length).fill(-1);
  let d = 1;
  for (let i = 0; i < body.length; i += 1) {
    if (body[i] === '{') { depth[i] = d; d += 1; } else if (body[i] === '}') { d -= 1; depth[i] = d; } else { depth[i] = d; }
  }
  return depth;
}

/** 返回类型声明体之后的第一个 `{`，以及与之配对的 `}`。 */
function bodyRange(flat, from) {
  const start = flat.indexOf('{', from);
  if (start < 0) return null;
  let d = 0;
  for (let i = start; i < flat.length; i += 1) {
    if (flat[i] === '{') d += 1;
    else if (flat[i] === '}') {
      d -= 1;
      if (d === 0) return { start, end: i };
    }
  }
  return null;
}

/**
 * 返回文件里的每一个 **controller 类型**：`[{ name, start, end }]`。
 *
 * 按"注解 + 类型声明"找，而不是按文件找。`WebUiConfig` 里嵌了一个
 * `@RestController public static class WebUiController`：按文件判定会把这个
 * 配置类当成 controller，然后去数**外层**类的构造器——数错了对象。
 * 这种盲区是静态门禁最常见的一种，所以这里显式处理。
 */
export function findControllerTypes(source) {
  const flat = neutralize(source);
  const declRe = /@(?:[\w$]+\.)*(?:RestController|Controller)\b[^;{}]*?\b(?:class|record|enum)\s+(\w+)/g;
  const types = [];
  for (const m of flat.matchAll(declRe)) {
    const range = bodyRange(flat, m.index + m[0].length);
    if (!range) continue;
    types.push({ name: m[1], start: range.start, end: range.end });
  }
  return types;
}

/**
 * 数出某个类型体第一层的构造器声明：`[{ line, visibility, annotated }]`。
 * `type` 来自 findControllerTypes；不传时用文件里第一个类型声明。
 */
export function findConstructorDeclarations(source, type) {
  const flat = neutralize(source);
  const cls = type ? type.name : flat.match(/\b(?:class|record|enum)\s+(\w+)/)?.[1];
  if (!cls) return null;
  const range = type ?? bodyRange(flat, flat.search(/\b(?:class|record|enum)\s+/));
  if (!range) return [];

  const body = flat.slice(range.start + 1, range.end);
  const depth = depthMap(body);
  const lineOf = (offsetInBody) => flat.slice(0, range.start + 1 + offsetInBody).split('\n').length;

  // 构造器声明 = 标识符 Foo 后紧跟 "("，前面不是 new / @ / . ，且处于深度 1。
  const pattern = new RegExp(`(^|[^\\w.@$])${cls}\\s*\\(`, 'g');
  const found = [];
  for (const m of body.matchAll(pattern)) {
    const at = m.index + m[1].length;
    if (depth[at] !== 1) continue;
    const before = body.slice(0, at);
    if (/\bnew\s+$/.test(before)) continue;
    // 修饰符与注解：注解常写在**上一行**，所以往前回溯到最近一个语句/块
    // 边界，而不是只看当前行。只看当前行会让每个多行写法的构造器都被
    // 报成"未标注"——自测的第一条用例抓的就是这个。
    const boundary = Math.max(
      before.lastIndexOf(';'),
      before.lastIndexOf('}'),
      before.lastIndexOf('{'),
    );
    const head = before.slice(boundary + 1);
    const visibility = /\b(public|protected|private)\b/.exec(head)?.[1] ?? 'package';
    found.push({
      line: lineOf(at),
      visibility,
      annotated: /@\w/.test(head),
    });
  }
  return found;
}

/** 文件里是否声明了 controller（含嵌套的）。 */
export function isController(source) {
  return findControllerTypes(source).length > 0;
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
  const rootOverride = process.env.CONTROLLER_CTOR_ROOT;
  const scanRoot = rootOverride ? rootOverride : SRC_ROOT;
  const files = walk(scanRoot);

  let blocking = 0;
  let controllers = 0;
  for (const path of files) {
    const source = readFileSync(path, 'utf8');
    for (const type of findControllerTypes(source)) {
      controllers += 1;
      const ctors = findConstructorDeclarations(source, type) ?? [];
      if (ctors.length <= 1) continue;
      const rel = relative(scanRoot, path);
      const detail = ctors
        .map((c) => `line ${c.line} (${c.visibility}${c.annotated ? ', @Autowired' : ''})`)
        .join(' + ');
      blocking += 1;
      console.log(
        `- [controller-constructor-count] ${rel}: ${type.name} declares ${ctors.length} constructors `
        + `— ${detail}. Spring injects through the @Autowired one; every other constructor is `
        + 'reachable only from tests, and it decides on the caller\'s behalf which collaborators end '
        + 'up null. A convenience constructor that fills defaults in also hides which parameter the '
        + 'caller actually supplied. Delete the extra constructor and let the call sites pass every '
        + 'dependency explicitly.',
      );
    }
  }

  if (blocking > 0) {
    console.error(
      `\nController-constructor-count check failed; ${blocking} controller(s) with more than one `
      + `constructor out of ${controllers} examined.`,
    );
    process.exit(1);
  }
  console.log(
    `Controller-constructor-count check passed; ${controllers} controller(s) examined, each declares `
    + 'exactly one constructor, so every dependency a test injects is one the production wiring has too.',
  );
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop())) {
  main();
}
