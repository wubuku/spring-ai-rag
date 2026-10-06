#!/usr/bin/env node
/**
 * `.env.example` 里每一个**未注释**的变量，都必须有代码或脚本真的读它。
 *
 * 背景（Batch 935）。`ad026765` 以「补齐所有现存配置项」的名义往模板里写了 6 个
 * 变量：`TRANSCRIPTION_BASE_URL/API_KEY/MODEL` 和 `VISION_BASE_URL/API_KEY/MODEL`，
 * 块头分别写着「用于音频转写」和「用于多模态对话（图像理解）」。实测它们**生下来
 * 就是死的**：
 *
 *   - 没有任何 Java / TypeScript / shell 引用过它们（`git log -S transcription`
 *     在代码树上从未命中）；
 *   - Spring AI 依赖树里没有 transcription / vision 的 autoconfigure 模块；
 *   - `docs/openai-compatibility-readiness.md` 明写 "multimodal inputs are
 *     unsupported"、"Text-only and n=1"。
 *
 * 同一批里 `TEST_IMAGE_PATH` / `TEST_AUDIO_PATH` 也是同一形状：模板承诺了一个
 * 「多模态 / 语音流式测试」的挂载点，而项目没有这类测试。模板承诺一个没人读的
 * 旋钮，等于承诺一个改了不会有任何变化的开关——而读者无从知道它是空的。
 *
 * **为什么规则是「未注释的」而不是「所有行」。** 注释掉的行在这份模板里是另一
 * 种东西：它们标的是"可用的开关"，而其中 40 条有 37 条确实有消费者。这不是
 * 「注释了就豁免」的类别豁免——是量出来的：**删掉那 6 条之后，未注释的 38 条
 * 全部有消费者，一条 allowlist 都不需要**，而注释掉的那几条里有一条恰恰是
 * 删不得的：
 *
 *   `SPRING_DATASOURCE_DRIVER_CLASS_NAME`（注释）没有任何本项目文件按这个名字
 *   引用，但 `spring.datasource.driver-class-name` 在四个 `application.yml` /
 *   `configmap.yaml` 里配着。它是 Spring Boot 的内建属性，按名字在文本里找不到
 *   它并不说明它无效——**"没搜到"和"没有"是两件事**，第一版探针正是把它当成后者
 *   报了出来。所以它留在模板里，理由记在这里。
 *
 * **反方向不查**：代码读了但模板没写的变量不算违规。模板从不承诺穷举——它有 40 行
 * 注释掉的开关，就是"这里还有更多"的自白。这与 `DocumentedRouteContract`
 * （Batch 934）同一个理由：一份从不承诺完整的参考文档，完整性检查只剩豁免表，
 * 而豁免表等于"基线不是检查"。
 *
 * 已登记的已知上限（由自测钉住）：
 *   - 只查**未注释**的行。注释掉的开关只做人工判断。
 *   - 消费者 = 被跟踪文件的**代码**里出现该名字（词边界，先剥注释）。三类东西
 *     明知不构成消费者，各有各的理由：
 *       1. **文档**提到一个变量，不代表有代码读它。
 *       2. **本地 dot-env 文件**（`.env`、`.env.deepspeed`）只是把模板抄了一遍。
 *          第一版普查把 `.env.deepspeed` 里抄来的占位符当成引用，于是 44 个变量
 *          全部"有消费者"，与同一份模板上直接 grep 的结果自相矛盾。
 *       3. **门禁自己的夹具**（住在 `test-support/` 或 `__tests__/` 路径段里）。
 *          自测必须写出它要检验的变量名，而自测是被跟踪的——第一版因此在
 *          `VISION_API_KEY` 上失效：它躺在模板里没人读，自测 11/11 全绿。
 *          排除它们不损失覆盖：38 条声明变量里没有一条只靠自测才成立。
 *   - **注释不算消费者。** 这条是被自己的头注释教的：那段解释"注释会把变量藏起来"
 *    的散文里写着 `VISION_API_KEY=your-ark-vision-api-key`，而门禁脚本本身是被跟踪的
 *    非文档文件。**一道解释自己规则的注释，成了它自己要抓的那个缺陷的藏身处。**
 *   - 剥离器来自 `scripts/lib/java-source.mjs` 的 `stripConfigComments`，不是本文件
 *     里的一份：`java-source-self-test.mjs` 的最后一条规则就专门盯着这件事，本门禁
 *     第一版正是在那条规则上变红的。它在 Java 词法走查上补了 `#`，并规定 `//` 只有
 *     紧跟在空白或 `([{,;` 之后才算注释标记——因为 `spring.datasource.url=jdbc:postgresql://localhost:5432/db`
 *     就在这个仓库里，无条件把 `//` 当注释会吃掉半行，把真消费者抹掉，门禁于是报一条
 *     并不存在的违规。代价是紧贴标识符的注释标记会被当成代码：Java / JS 里这种写法
 *     罕见，而误吃的后果是**误报**，比反过来危险得多。
 *
 * Run:
 *   node scripts/verify-env-example-consumers.mjs
 */

import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isMainModule } from './lib/is-main-module.mjs';
import { stripConfigComments } from './lib/java-source.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));

export const TEMPLATE = '.env.example';

/** 地板断言：模板曾经声明 44 条、删掉死线后剩 38 条。低于这个数说明解析器坏了。 */
export const MIN_DECLARED = 30;

/** 本地 dot-env 文件不算消费者——它们是模板的副本，不是读者。 */
export function isLocalEnvFile(path) {
  return /(^|\/)\.env(\..+)?$/u.test(path) && path !== TEMPLATE;
}

/** 文档提到一个变量，不构成有代码在读它。 */
export function isDocumentation(path) {
  return /^(docs|drafts)\//u.test(path)
    || /^(README|CLAUDE|AGENTS)(\.[a-z]+)?\.md$/u.test(path)
    || /\/README\.md$/u.test(path);
}

/**
 * 一道门禁自己的夹具不是任何东西的消费者。
 *
 * 这一条不是设想的，是量出来的：**自测文件是被跟踪的**，而自测里必须写出它要检验
 * 的那些变量名，于是 `scripts/test-support/env-example-consumers-self-test.mjs`
 * 里那行 `VISION_API_KEY=your-ark-vision-api-key` 把一个没人读的变量变成了
 * 「有人读」。第一次跑 11/11 全绿正是这个原因——门禁在自己的夹具面前失效，
 * 而唯一暴露它的是那条反向探针：把变量塞回真实模板，看门禁会不会红。
 *
 * 按**路径段**表达而不是列文件名：凡是住在 `test-support/` 或 `__tests__/` 里的
 * 都是门禁夹具。实测排除它们不损失任何覆盖——38 条声明变量里**没有一条**是只靠
 * 自测文件才成立的，所以这不是为了变绿而缩小范围，是为了让"有消费者"这句话
 * 指的仍然是代码和能跑的脚本。
 */
export function isGateFixture(path) {
  return /(^|\/)(test-support|__tests__)\//u.test(path);
}

/**
 * 模板里的变量行。注释掉的那些一并读出来，因为注释头里那条
 * `SPRING_DATASOURCE_DRIVER_CLASS_NAME` 就是注释行。
 *
 * @returns {{name: string, line: number, active: boolean}[]}
 */
export function parseTemplate(text) {
  const vars = [];
  text.split('\n').forEach((line, index) => {
    const active = /^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=/u.exec(line);
    if (active !== null) {
      vars.push({ name: active[1], line: index + 1, active: true });
      return;
    }
    const commented = /^\s*#\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=/u.exec(line);
    if (commented !== null) {
      vars.push({ name: commented[1], line: index + 1, active: false });
    }
  });
  return vars;
}

/**
 * 剥掉注释，只留代码与字符串字面量。
 *
 * 为什么必须剥：这道门禁的头注释里写着 "`VISION_API_KEY=your-ark-vision-api-key`
 * 把一个没人读的变量变成了有人读"——而 `scripts/verify-env-example-consumers.mjs`
 * 本身是被跟踪的、非文档的、非夹具的文件。于是**一道解释自己规则的注释，成了它
 * 自己要抓的那个缺陷的藏身之处**。这比自测夹具那一次更深一层，也更荒唐。
 *
 * 所以规则是：门禁匹配的是**代码**，不是关于代码的散文。
 *
 * 字符串字面量必须保留：`System.getenv("POSTGRES_PASSWORD")` 里的那个名字是
 * 真消费者，而 `http://…` 里的 `//` 不是注释——这正是需要逐字符跟踪字符串状态、
 * 而不能简单正则替换的原因。仓库里已有两次同形先例：Java 那批门禁，以及
 * `scripts/lib/tsx-source.mjs`。
 *
 * 换行与长度保持不变，好让一条发现还能落回它所在的行。
 */
/**
 * 门禁匹配的是**代码**，不是关于代码的散文。
 *
 * 剥离器不在本文件里：`scripts/lib/java-source.mjs` 已经住着一个，因为四个门禁各
 * 自造过一份，而那份自测里量到 Java 树上 351 个含 `//` 或 `/*` 的字符串字面量有
 * 331 个（94%）会被天真的实现抹掉。本门禁读的是**混合语料**，所以用那里的
 * `stripConfigComments`——它在同一份词法走查上补了 `#`，并且规定 `//` 只有紧跟在
 * 空白或 `([{,;` 之后才算注释标记。
 *
 * 后半条是被实测逼出来的，不是洁癖：`spring.datasource.url=jdbc:postgresql://localhost:5432/db`
 * 就在这个仓库里，无条件把 `//` 当注释会吃掉半行，把一个真消费者抹掉，门禁于是
 * 报一条并不存在的违规——那是会嚎叫的方向。
 */

export function referencesVariable(text, name) {
  return new RegExp(`(?<![A-Za-z0-9_])${name}(?![A-Za-z0-9_])`, 'u').test(text);
}

/**
 * 整个检查，纯函数：模板文本 + 一份 `{path, text}` 语料。
 */
export function findUnconsumedVariables(templateText, corpus) {
  const consumers = corpus.filter((f) => f.path !== TEMPLATE
    && !isLocalEnvFile(f.path)
    && !isDocumentation(f.path)
    && !isGateFixture(f.path));
  return parseTemplate(templateText)
    .filter((v) => v.active)
    .filter((v) => !consumers.some((f) => referencesVariable(f.text, v.name)));
}

function trackedCorpus() {
  const paths = execFileSync('git', ['ls-files', '-z'], {
    cwd: projectRoot,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  }).split('\0').filter(Boolean);

  return paths
    .filter((p) => !/\.(png|jpg|jpeg|gif|ico|pdf|jar|zip|woff2?|ttf|svg)$/iu.test(p))
    .map((path) => ({ path, text: stripConfigComments(readFileSync(join(projectRoot, path), 'utf8')) }));
}

function main() {
  const templateText = readFileSync(join(projectRoot, TEMPLATE), 'utf8');
  const declared = parseTemplate(templateText).filter((v) => v.active);

  if (declared.length < MIN_DECLARED) {
    console.error(
      `Parsed only ${declared.length} declared variable(s) from ${TEMPLATE},`
      + ` below the floor of ${MIN_DECLARED}. A parser that stopped reading is`
      + ' indistinguishable from a template that lost its variables.',
    );
    process.exitCode = 1;
    return;
  }

  const unconsumed = findUnconsumedVariables(templateText, trackedCorpus());

  if (unconsumed.length > 0) {
    console.error(`${TEMPLATE} declares ${unconsumed.length} variable(s) nothing reads:`);
    for (const v of unconsumed) {
      console.error(`- ${TEMPLATE}:${v.line} — ${v.name}`);
    }
    console.error(
      '\nA required variable that nothing reads is a promise of a switch that'
      + ' cannot change anything. Either wire it up, or drop the line.',
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `Env-example consumer check passed; ${declared.length} declared variable(s),`
    + ' each read by code or a script.',
  );
}

if (isMainModule(import.meta.url)) {
  main();
}