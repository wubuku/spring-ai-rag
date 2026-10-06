#!/usr/bin/env node
/**
 * 禁止把 `InterruptedException` 当成普通异常吞掉：捕获它的块必须重抛，或恢复中断标志。
 *
 * 规则本身
 * --------
 * `catch (... InterruptedException ...)` 的块体里必须出现 `throw`，或
 * `Thread.currentThread().interrupt()`。JDK 对中断只有两种正当反应，没有第三种。
 *
 * 为什么"吞掉"不是无害的风格选择（两个互相独立的机制）
 * ------------------------------------------------------
 * 1. **阻塞操作没做完，但调用方以为做完了。** `Thread.sleep` / `Process.waitFor`
 *    被中断时抛出，本方法接下来要断言的东西根本没发生，而失败会以**别的东西的面貌**
 *    出现。本批实测到的那处测试正是这样：sleep(1100) 没睡完 → 断路器还是 OPEN →
 *    `assertEquals(HALF_OPEN)` 红，而报错指向断路器，不指向中断。
 * 2. **这条线程从此看不见取消信号。** 上面两个方法都会**清掉**中断标志。标志一旦被
 *    清掉，本线程后续每一次阻塞调用（sleep / wait / join / lock）都不会再抛
 *    InterruptedException——已经发出的取消信号就此静默丢失，运行可以越过本该
 *    中止它的边界继续跑完。测试线程和 worker 线程都受这条约束。
 *
 * 语料（四个模块的 src/main + src/test，实测数字）
 * --------------------------------------------
 *   本批修改前：主代码 20 处捕获 / **1 处违规**，测试 11 处捕获 / **1 处违规**。
 *   本批修改后：主代码 20 处捕获 / 0 处违规，测试 10 处捕获 / 0 处违规。
 *
 * 两处违规是**同一种缺陷的两个面**，而且**不是同一次普查找出来的**——这件事本身
 * 就是下面"已知上限"第 1 条的证据：
 *
 *   - `CircuitBreakerHealthIndicatorTest:103`：`try { Thread.sleep(1100); }
 *     catch (InterruptedException ignored) {}` —— 单类型 catch。
 *   - `MarkerPdfConverter.isAvailable()`：`catch (IOException | InterruptedException e)`
 *     里只记了 debug 日志就 return false —— 多 catch。第一版探针的正则只认单类型，
 *     于是报了"主代码 0 违规"，而这一处**就是**违规。同一文件上方 23 行的
 *     `catch (InterruptedException e)` 反而写对了（先恢复标志再 return false），
 *     所以正确的读法不是"这里风格不统一"，而是"同一个人 23 行之内写了两遍，其中
 *     一遍把中断当成了 CLI 不可用"。修法就是照抄同文件里的正确那一遍。
 *
 * 两个普查方向，实测否掉，因此**没有**建门禁
 * -----------------------------------------
 * 留在这里是因为它们看起来都像该建的检查，而否掉的理由只有量过才知道。
 *
 * 1. **"`@Test` 方法有语句但无断言"。** 探测器一路修到 28（240 → 92 → 28：先修
 *    断言动词表，再修括号定位——`@ParameterizedTest(name = "{0}")` 里的 `{0}`
 *    被当成了方法体）。逐个读完 28 处，**最大一簇 7 处**
 *    （`DocumentMutationReconciliationRecoveryGuardTest`）末尾都调
 *    `syncItemConflict()`，断言写在这个类的私有 helper 里；另有多处是
 *    `properties.validate()` 这种"靠抛异常断言"的合法写法。
 *    **两类合法代码在语法上分不开** → 只能上豁免表 → 豁免表等于"基线不是检查"。
 *    不建门禁。
 * 2. **"测试里的空 catch"。** 162 个 catch 块里**只有 5 个**块体为空，逐个读：
 *    其中 3 处是 Mockito `thenThrow` 的**编译器强制要求**的受检异常 catch，
 *    1 处是并发基准里故意吞掉单请求失败（断言在 `successCount` 上）。
 *    也就是说 5 处里至少 4 处合法，规则把"编译器逼你写的"和"你该删的"判成同一类。
 *    不建门禁。
 *
 * 已登记的已知上限（由 `scripts/test-support/interrupt-handling-self-test.mjs` 钉住）
 * ----------------------------------------------------------------------------------
 *   1. **只认写得出 `InterruptedException` 的 catch 子句。** `catch (Throwable t)`
 *      或 `catch (Exception e)` 里吞掉一次中断，文本上与吞掉别的异常**完全一样**，
 *      正则分不开——这不是实现偷懒，是语法位置相同。本仓库今天 0 处这种写法，
 *      所以它现在是覆盖漏洞而不是活跃缺陷。
 *   2. **"块体里有 throw / interrupt()" 是文本判定，不做控制流判定。** 一个被内层
 *      catch 截住的 `throw` 仍然读起来合规，而中断可能并没有传出去。本仓库今天
 *      0 处这种形状。（嵌套写法的实际暴露面比这句话窄：内层那个吞掉的 catch 本身
 *      会被单独判一次，所以门禁不是静默放过；见自测里那条把失败写在用例里的说明。）
 *   3. 声明 `throws InterruptedException` 让异常自然冒出去，天然合规，不查。
 *   4. 语料是 `src/main` + `src/test`。`demos/` 下另有 `marker` 相关代码，本批未纳入。
 *
 * 块体提取为什么不能直接数花括号：`MarkerPdfConverter` 的 catch 里就有
 * `log.debug("Marker CLI not available: {}", ...)`——字符串里的 `{}` 是配平的，
 * 纯属侥幸；不配平的更常见。所以这里在剥完注释之后**再把字符串/字符字面量整体
 * 遮成空格**（保留换行，行号才对得上），然后在遮过的文本上配平花括号。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripJavaComments } from './lib/java-source.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

/** 四个模块的源码根：主代码与测试都查，因为两种违规各占一个。 */
export const SCAN_ROOTS = [
  'spring-ai-rag-api',
  'spring-ai-rag-core',
  'spring-ai-rag-documents',
  'spring-ai-rag-starter',
].flatMap((module) => [
  join(ROOT, module, 'src/main/java'),
  join(ROOT, module, 'src/test/java'),
]);

/**
 * 把字符串与字符字面量的内容遮成空格，换行保留。
 *
 * 只用于块体提取与判定：本规则要看的 `throw` / `Thread.currentThread().interrupt()`
 * 是代码，永远不会出现在字面量里；而字面量里的 `{` / `}` / `//` 会让朴素的括号配平
 * 数错位置。
 */
function maskLiterals(source) {
  let out = '';
  let i = 0;
  let quote = null;

  while (i < source.length) {
    const c = source[i];

    if (quote !== null) {
      if (c === '\\') {
        out += source[i + 1] === '\n' ? '\n' : ' ';
        if (source[i + 1] === '\n') out += '\n';
        i += 2;
        continue;
      }
      if (c === quote) quote = null;
      out += c === '\n' ? '\n' : ' ';
      i += 1;
      continue;
    }

    if (c === '"' || c === "'") {
      quote = c;
      out += ' ';
      i += 1;
      continue;
    }

    out += c;
    i += 1;
  }

  return out;
}

/**
 * 抓 `catch (A | B | InterruptedException e) {`。
 *
 * 类型列表单独捕获而不是只匹配单类型，是本门禁存在的直接原因——第一版探针只认
 * `catch (InterruptedException e)`，因此漏掉了 `MarkerPdfConverter` 里的多 catch，
 * 并据此报出"主代码 0 违规"。
 */
const CATCH_WITH_TYPES =
  /catch\s*\(\s*(?:final\s+)?([A-Za-z_$][\w$.]*(?:\s*\|\s*[A-Za-z_$][\w$.]*)*)\s+([A-Za-z_$][\w$]*)\s*\)\s*\{/g;

const INTERRUPT_TYPE = /^(?:java\.lang\.)?InterruptedException$/;

/** 块体里的这两种反应算合规；顺序无关，任一命中即可。 */
const COMPLIANT = /\bthrow\b|Thread\s*\.\s*currentThread\s*\(\s*\)\s*\.\s*interrupt\s*\(\s*\)/;

/** 从 `{` 处开始配平取出块体内容；不配平时返回到文件末尾。 */
function blockBody(masked, openIndex) {
  let depth = 0;
  for (let i = openIndex; i < masked.length; i += 1) {
    if (masked[i] === '{') depth += 1;
    else if (masked[i] === '}') {
      depth -= 1;
      if (depth === 0) return masked.slice(openIndex + 1, i);
    }
  }
  return masked.slice(openIndex + 1);
}

/**
 * 扫描一份源码，返回 `{ considered, findings }`。
 *
 * `considered` 是这条规则**真正判定过**的捕获子句数，从与判定同一份遮蔽文本里数出来。
 * 早先一版在 main() 里用原始文本另数了一遍，于是报告出来的子句数和规则实际看过的
 * 可能不是同一个数——一个门禁报出自己量错的数字，比不报这个数字更糟。
 */
export function scanSource(source) {
  const masked = maskLiterals(stripJavaComments(source));
  const findings = [];
  let considered = 0;
  let m;

  CATCH_WITH_TYPES.lastIndex = 0;
  while ((m = CATCH_WITH_TYPES.exec(masked)) !== null) {
    const types = m[1].split('|').map((t) => t.trim());
    if (!types.some((t) => INTERRUPT_TYPE.test(t))) continue;
    considered += 1;

    const brace = m.index + m[0].length - 1;
    const body = blockBody(masked, brace);
    if (COMPLIANT.test(body)) continue;

    findings.push({
      line: masked.slice(0, brace).split('\n').length,
      types: types.join(' | '),
      body: body.trim().replace(/\s+/g, ' ').slice(0, 120),
    });
  }

  return { considered, findings };
}

/** 只要违规不要计数的薄封装，给自测和只想知道"有没有"的地方用。 */
export function findSwallowedInterrupts(source) {
  return scanSource(source).findings;
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
  // 自测把 ROOT 指到夹具树，这样能断言退出码，而不只是探测器函数——
  // 一条从未走过报错路径的门禁是一条不会失败的门禁，本仓库产出过四条。
  const override = process.env.INTERRUPT_HANDLING_ROOT;
  const roots = override ? [override] : SCAN_ROOTS;

  const findings = [];
  let scanned = 0;
  let caughtClauses = 0;

  for (const root of roots) {
    for (const file of walk(root)) {
      scanned += 1;
      const source = readFileSync(file, 'utf8');
      caughtClauses += [...source.matchAll(CATCH_WITH_TYPES)].filter((m) =>
        m[1].split('|').some((t) => INTERRUPT_TYPE.test(t.trim()))).length;
      for (const hit of findSwallowedInterrupts(source)) {
        findings.push({ file: relative(ROOT, file), ...hit });
      }
    }
  }

  for (const f of findings) {
    console.log(
      `- [interrupt-handling] ${f.file}:${f.line} catches ${f.types} without rethrowing or `
      + 'restoring the interrupt flag. Blocked calls clear the flag on the way out, so a thread '
      + 'that swallows this one never sees the cancellation again. Rethrow, or call '
      + `Thread.currentThread().interrupt() first. Body: {${f.body}}`,
    );
  }

  if (findings.length > 0) {
    console.error(
      `\nInterrupt handling check failed; ${findings.length} blocking finding(s) across `
      + `${scanned} source file(s).`,
    );
    process.exit(1);
  }

  console.log(
    `Interrupt handling check passed; ${scanned} source file(s) scanned, ${caughtClauses} `
    + 'InterruptedException catch clause(s) and every one of them rethrows or restores the flag.',
  );
}

if (isMainModule(import.meta.url)) {
  main();
}