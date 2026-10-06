#!/usr/bin/env node
/**
 * 被跟踪的 dot-env 文件只能有一个，而且必须是不含凭据的模板。
 *
 * 背景（Batch 935）。`.env.deepspeed` 被 Git 跟踪，里面装着真实的 provider key、
 * 数据库口令和两条指向开发机 home 目录的绝对路径，它被推到了**公开**仓库的
 * `main` 上，存在于 `9f772b62` 和 `32fd4495` 两个 commit 里。当时 `.gitignore`
 * 只写了 `.env` 和 `.env.local`，没覆盖它。
 *
 * **写这道门禁之前，先试过宽得多的那一版。** 那一版查的是「被跟踪的文件里有没有
 * 凭据形态的值」，规则是变量名长得像 `*_API_KEY` / `*_TOKEN` / `*_PASSWORD`
 * 加上 `sk-…` 的值形状。第一次跑报了**一百多条**——而其中绝大多数是这个仓库里
 * 完全正常的东西：
 *
 *   - SQL 里的 `lease_token` / `owner_token` / `operation_token` 列；
 *   - 前端与 e2e 夹具里的 `confirmationToken` / `previewToken` 变量；
 *   - CI 里 testcontainer 的库口令；
 *   - `MaskingLogstashEncoderTest` 这类**专门测脱敏**的假 key；
 *   - 文档里写着 `RAG_API_KEY=rk_test_...` 的示例值。
 *
 * 一道会这样叫的门禁会被关掉，而关掉它比没有它更糟——那正是 `?? 0` 那两类
 * （`MetricsCharts` 真的在编造 vs `formatInteger` 真的在兜底）教会我的事。
 * **形状不是问题，逐例判断才是，而静态规则扛不住逐例判断。**
 *
 * 所以规则收窄到缺陷本身，而且它不需要任何豁免表：
 *
 *   **除 `.env.example` 之外，任何 dot-env 文件都不该被 Git 跟踪。**
 *
 * 模板是**按职责**就要被跟踪的：它演示名字长什么样，值一律是占位符。所以第二个
 * 规则只作用在**非模板**的 dot-env 文件上——如果将来又有人提交一个 `.env.foo`，
 * 这条会再抓一次，而且那次是真的。
 *
 * **这道门禁永远不打印值**，只报文件名、变量名和行号。一个会把自己发现的密钥抄进
 * CI 日志的门禁，会把一次泄漏变成两次。
 *
 * 已登记的已知上限：
 *   - 它管的是**被跟踪**。已经在历史里的东西要不要清掉，不是一道门禁能回答的。
 *   - 「历史里躺着什么」由 GitHub 的 secret scanning 负责，不是这里能静态断言的事。
 *
 * Run:
 *   node scripts/verify-tracked-env-files.mjs
 */

import { readFileSync, statSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isMainModule } from './lib/is-main-module.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));

/** The one dot-env file that is meant to be tracked: a template of names. */
export const TEMPLATE = '.env.example';

export const VIOLATION_KINDS = Object.freeze({
  TRACKED_ENV_FILE: 'tracked-env-file',
  CREDENTIAL_IN_TRACKED_ENV_FILE: 'credential-in-tracked-env-file',
});

/** A dot-env file, by name. Deliberately not by path: these live at the root. */
export function isDotEnvFile(name) {
  const base = name.split('/').pop();
  return base === '.env' || base.startsWith('.env.');
}

/**
 * Values that are obviously not secrets, in a template or a committed default.
 * Narrow on purpose — see the header.
 */
export function isPlaceholder(value) {
  const v = value.trim();
  if (v === '') return true;
  if (/^\$\{[^}]*\}$/.test(v)) return true;
  if (/^(your[-_]|your$|changeme|change-me|placeholder|example|dummy|fake|test|local|dev)/i.test(v)) return true;
  if (/^<.*>$/.test(v)) return true;
  if (/^(x{3,}|\*{3,}|\.{3,})$/i.test(v)) return true;
  return false;
}

/** A variable whose name says it holds a credential. */
const CREDENTIAL_NAME = /(API_?KEY|SECRET|TOKEN|PASSWORD|PASSWD|PRIVATE_KEY)\s*=/i;

/**
 * The whole check, over a list of tracked paths, as a pure function so the
 * self-test can hand it fixtures instead of the real repository.
 */
export function findTrackedEnvProblems(trackedPaths, readFile) {
  const problems = [];

  for (const path of trackedPaths) {
    if (!isDotEnvFile(path)) continue;

    if (path === TEMPLATE) continue;

    problems.push({
      kind: VIOLATION_KINDS.TRACKED_ENV_FILE,
      file: path,
      line: 0,
      detail: `${path} is tracked; only ${TEMPLATE} is meant to be.`
        + ' Untrack it with `git rm --cached`, and move any real values into it',
    });

    // Belt and braces: if a non-template env file is ever tracked again, say
    // which of its variables carry something.
    const source = readFile(path);
    source.split('\n').forEach((line, index) => {
      const assignment = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+?)\s*$/u.exec(line);
      if (assignment === null) return;
      const [, name, raw] = assignment;
      const value = raw.replace(/^["']|["']$/gu, '');
      if (CREDENTIAL_NAME.test(`${name}=`) && !isPlaceholder(value)) {
        problems.push({
          kind: VIOLATION_KINDS.CREDENTIAL_IN_TRACKED_ENV_FILE,
          file: path,
          line: index + 1,
          detail: `${name} carries a real value in a tracked file (value withheld;`
            + ' rotate the key — a published repository is a published key)',
        });
      }
    });
  }

  return problems;
}

/**
 * How a problem is rendered. Exported because the rendered line is the part that
 * reaches a CI log: that it names the file, the line and the variable — and that it
 * never contains the value — is a property of this function, not of `main`.
 */
export function renderProblem(problem) {
  return `- [${problem.kind}] ${problem.file}${problem.line ? `:${problem.line}` : ''} — ${problem.detail}`;
}

function trackedFiles() {
  return execFileSync('git', ['ls-files', '-z'], {
    cwd: projectRoot,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  }).split('\0').filter(Boolean);
}

function main() {
  const tracked = trackedFiles();
  const envFiles = tracked.filter(isDotEnvFile);
  const problems = findTrackedEnvProblems(
    tracked,
    (path) => readFileSync(join(projectRoot, path), 'utf8'),
  );

  if (problems.length > 0) {
    console.error('Tracked dot-env files:');
    for (const p of problems) {
      console.error(renderProblem(p));
    }
    console.error(
      `\nOnly ${TEMPLATE} belongs in the repository. Values are deliberately not`
      + '\nprinted here; rotate anything named above.',
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `Tracked-env check passed; ${envFiles.length} tracked dot-env file(s)`
    + ` (${envFiles.join(', ')}), and it is the template.`,
  );
}

if (isMainModule(import.meta.url)) {
  main();
}
