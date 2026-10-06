#!/usr/bin/env node
/**
 * 每个 `{{- define "…" }}` 都必须被某个模板 `include` 过。
 *
 * 背景（Batch 937）。`k8s/templates/_helpers.tpl` 里定义过三个从来没人 include 的
 * helper，而它们描述的是**一套与实际生效的机制不同**的策略：
 *
 *   - `spring-ai-rag.jvm-heap` 按 `jvm.heapPercent` 把容器内存换算成堆上限，
 *     `spring-ai-rag.jvm-raw` 只被它调用。真正设置堆上限的是 `deployment.yaml`
 *     里直接读 `.Values.jvm.maxHeap` 的那一行。
 *   - `spring-ai-rag.spring-profile` 读顶层的 `.Values.springProfile`，生成
 *     `--spring.profiles.active` 参数。而 `values.yaml` 里只有
 *     `secrets.springProfile`，profile 实际上是由 `secret.yaml` 注入的
 *     `SPRING_PROFILES_ACTIVE` 环境变量设置的。
 *
 * 留着的代价不止于死代码：`jvm.heapPercent` 成了一个**没有任何 values 声明、只被死
 * 代码读取**的旋钮，运维可以 `--set jvm.heapPercent=50` 而它什么都不会发生。
 *
 * **为什么这条规则没有豁免表。** "一个没人 include 的 `define` 就是死代码"不需要逐例
 * 判断——同仓库里 Batch 935 那道"模板声明的变量必须有人读"之所以要三类排除，是因为
 * "有人提到"和"有人在用"本来就不是一回事；而 `define` 的可见性是 Helm 模板语言自己
 * 决定的，没有中间状态。这是本仓库里少见的零误报形状，所以它自己撑得起一道门禁。
 *
 * 已登记的已知上限（由自测钉住）：
 *   - 只认 `{{- define "名字" }}` 与 `include "名字" .` 两种拼写。用变量间接引用的
 *     helper 查不到，那也是 Helm 里不常见的写法。
 *   - **不查 `.Values.*` 路径是否存在。** 量过：10 条模板引用的路径不在 values.yaml
 *     里，其中 `annotations`、`podLabels`、`service.nodePort`、`autoscaling.behavior`、
 *     `podDisruptionBudget.maxUnavailable`、`nameOverride`、`fullnameOverride` 都是
 *     `with` / `if` 守卫下的可选键，Helm 约定如此；剩下两条（`global`、
 *     `postgresql`）是 Helm 保留名和子图表的 values 块。**查它会误报八条**，所以不查。
 *
 * Run:
 *   node scripts/verify-helm-helper-liveness.mjs
 */

import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isMainModule } from './lib/is-main-module.mjs';
import { stripConfigComments } from './lib/java-source.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const TEMPLATE_DIR = 'k8s/templates';

/** 地板断言：图表定义了 9 个 helper；删掉三个死的后剩 6 个。 */
export const MIN_HELPERS = 5;

/**
 * 注释在读之前先剥掉，用的是共享的 `scripts/lib/java-source.mjs`。
 *
 * 不自造：`java-source-self-test.mjs` 的最后一条规则就是盯着这件事，而且那里的教训
 * 是量出来的——Java 树上 351 个含 `//` 或 `/*` 的字面量有 331 个会被天真的实现抹掉。
 *
 * 为什么这里非剥不可：`_helpers.tpl` 里那段"这三个 helper 被删掉了，因为
 * `jvm.heapPercent` 只会读死代码……"的说明，按原文匹配就会把
 * `spring-ai-rag.jvm-heap` 这个名字从散文里捞出来，而门禁报的恰恰是"这个 helper
 * 定义了却没人 include"。**一份解释规则的说明不是规则在起作用。**
 * 两种注释形状都覆盖：Helm 的块注释（`{{/*` 起到闭合标记）和 `#` 开头的 YAML
 * 行注释——后者正是第一版自测里抓住的那处：夹具写的是 `# {{ include … }}`，
 * 只认 Helm 块注释的门禁会把注释掉的 include 当成真的，于是报"这个 helper 没人
 * include"的时候其实有人 include 了。
 *
 * 共享剥离器在图表上量过：6 个 define / 31 个 include，与不剥时一致，且模板里
 * `{{ … # … }}` 零出现，所以按行剥 `#` 不会误伤。
 */

/** `{{- define "name" -}}` 与 `{{ define "name" }}`。 */
export function parseDefines(text) {
  const out = [];
  for (const m of text.matchAll(/\{\{-?\s*define\s+"([^"]+)"/gu)) {
    out.push({ name: m[1], index: m.index });
  }
  return out;
}

/** `include "name" .`——带上下文参数的那种。 */
export function parseIncludes(text) {
  const out = [];
  for (const m of text.matchAll(/\binclude\s+"([^"]+)"\s+\./gu)) out.push(m[1]);
  return out;
}

/**
 * 纯函数：`[{path, text}]` → 没人 include 的 define 名字。
 */
export function findUnreachableHelpers(files) {
  const defines = [];
  const included = new Set();
  for (const f of files) {
    const text = stripConfigComments(f.text);
    for (const d of parseDefines(text)) defines.push({ ...d, path: f.path });
    for (const name of parseIncludes(text)) included.add(name);
  }
  return defines.filter((d) => !included.has(d.name));
}

function templateFiles() {
  return readdirSync(join(projectRoot, TEMPLATE_DIR))
    .filter((name) => /\.(tpl|yaml|yml|txt)$/u.test(name))
    .map((name) => ({
      path: `${TEMPLATE_DIR}/${name}`,
      text: readFileSync(join(projectRoot, TEMPLATE_DIR, name), 'utf8'),
    }));
}

function lineOf(text, index) {
  return text.slice(0, index).split('\n').length;
}

function main() {
  const files = templateFiles();
  const stripped = files.map((f) => ({ ...f, text: stripConfigComments(f.text) }));
  const defines = stripped.flatMap((f) => parseDefines(f.text).map((d) => d.name));
  const unreachable = findUnreachableHelpers(files);

  if (new Set(defines).size < MIN_HELPERS) {
    console.error(
      `Found only ${new Set(defines).size} helper definition(s), below the floor of`
      + ` ${MIN_HELPERS}. A parser that stopped reading would call the chart`
      + ' free of dead helpers.',
    );
    process.exitCode = 1;
    return;
  }

  if (unreachable.length > 0) {
    console.error(`${unreachable.length} helper(s) are defined but never included:`);
    for (const d of unreachable) {
      const file = files.find((f) => f.path === d.path);
      console.error(`- ${d.path}:${lineOf(file.text, d.index)} — ${d.name}`);
    }
    console.error(
      '\nA helper nothing includes is dead code, and dead code here describes a'
      + ' strategy that is not the one in force. A value only dead code reads is'
      + ' worse: an operator can set it and nothing will happen.',
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `Helm helper check passed; ${new Set(defines).size} helper(s), every one included.`,
  );
}

if (isMainModule(import.meta.url)) {
  main();
}