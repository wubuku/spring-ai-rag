#!/usr/bin/env node
// 生成 nightly 失败追踪 issue 的正文与状态。
//
// 这个脚本只做“组装 + 判定”，不访问 GitHub：
//   - 输入：各 job 的 needs.<job>.result、run 元数据、artifact 目录；
//   - 输出：issue body 文件 + `status` / `failed_jobs`（写入 $GITHUB_OUTPUT）。
// 创建/更新/关闭 issue 由 workflow 里的 actions/github-script 完成。
//
// 固定标题（[Nightly] Long-running layer failing）是“只有一个追踪 issue”的关键：
// 每天失败只更新同一个 issue 并追加一条评论，不会每天开新 issue。

import {
  appendFileSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync,
} from 'node:fs';
import { dirname, join, relative } from 'node:path';

const args = new Set(process.argv.slice(2));

const ISSUE_TITLE = '[Nightly] Long-running layer failing';
// workflow_dispatch 的 debug_report_failure 用独立标题，绝不能碰真实追踪 issue。
const DEBUG_ISSUE_TITLE = '[TEST] Nightly issue automation debug';

const JOBS = [
  { key: 'gated', name: 'gated-postgres', env: 'RESULT_GATED', artifact: 'nightly-gated-it' },
  { key: 'failure-modes', name: 'failure-modes', env: 'RESULT_FAILURE_MODES', artifact: 'nightly-failure-modes' },
  { key: 'resilience', name: 'resilience', env: 'RESULT_RESILIENCE', artifact: 'nightly-resilience' },
  { key: 'real-llm', name: 'real-llm', env: 'RESULT_REAL_LLM', artifact: 'nightly-real-llm' },
  { key: 'real-llm-longcat', name: 'real-llm-longcat', env: 'RESULT_REAL_LLM_LONGCAT', artifact: 'nightly-real-llm-longcat' },
  { key: 'soak', name: 'soak', env: 'RESULT_SOAK', artifact: 'nightly-soak' },
  { key: 'webui', name: 'webui-real', env: 'RESULT_WEBUI', artifact: 'nightly-webui-real' },
  {
    key: 'webui-packaged',
    name: 'webui-real-packaged',
    env: 'RESULT_WEBUI_PACKAGED',
    artifact: 'nightly-webui-real-packaged',
  },
];

// artifact 里值得贴进 issue 的文件名。其余（HTML 报告、截图、XML）只放在 artifact。
const INTERESTING_FILES = new Set([
  'summary.md',
  'webui-summary.md',
  'webui-packaged-summary.md',
  'model-usage.md',
  'gated-it.log',
  'crash-consistency.log',
  'endpoint-behavior.log',
  'real-llm-smoke.log',
  'goldenset.log',
  'retrieval-regression.log',
  'soak.log',
  'start-server.log',
]);

const TAIL_CHARS = 700;
const MAX_FILES_PER_JOB = 4;

function argValue(name, fallback = '') {
  const index = process.argv.indexOf(name);
  if (index === -1) return fallback;
  return process.argv[index + 1] ?? fallback;
}

function walk(directory) {
  if (!existsSync(directory)) return [];
  const files = [];
  for (const entry of readdirSync(directory)) {
    const path = join(directory, entry);
    const stat = statSync(path);
    if (stat.isDirectory()) {
      files.push(...walk(path));
    } else if (stat.isFile()) {
      files.push(path);
    }
  }
  return files;
}

function tail(text, limit = TAIL_CHARS) {
  const trimmed = text.trimEnd();
  if (trimmed.length <= limit) return trimmed;
  return `…(truncated)…\n${trimmed.slice(-limit)}`;
}

/** 找到某个 job artifact 目录下值得展示的诊断文件。 */
function collectEvidence(artifactDir, artifactName) {
  const root = join(artifactDir, artifactName);
  if (!existsSync(root)) return [];
  const files = walk(root)
    .filter((path) => INTERESTING_FILES.has(path.split('/').pop()))
    .sort();
  return files.slice(0, MAX_FILES_PER_JOB).map((path) => ({
    name: relative(root, path),
    content: tail(readFileSync(path, 'utf8')),
  }));
}

function jobResult(env) {
  return (process.env[env] ?? 'unknown').trim() || 'unknown';
}

function isFailure(result) {
  return result !== 'success' && result !== 'skipped';
}

export function issueTitle(debug) {
  return debug ? DEBUG_ISSUE_TITLE : ISSUE_TITLE;
}

export function buildBody({
  results, runId, runNumber, serverUrl, repository, eventName, refName, artifactDir, debug = false,
}) {
  const failed = JOBS.filter((job) => isFailure(results[job.key]));
  const passed = JOBS.filter((job) => !isFailure(results[job.key]));
  const runUrl = `${serverUrl}/${repository}/actions/runs/${runId}`;

  const lines = [
    '<!-- nightly-tracking -->',
    failed.length === 0 ? '## Nightly run recovered' : '## Nightly run failed',
    '',
  ];
  if (debug) {
    lines.push(
      '> Debug run: created by `workflow_dispatch` with `debug_report_failure=true`.',
      '> All check jobs were skipped and the failure below is simulated. This is a',
      '> `[TEST]` issue and never the real nightly tracker; close it when done.',
      '',
    );
  }
  lines.push(
    `- Run: [#${runNumber}](${runUrl})`,
    `- Event: \`${eventName || 'unknown'}\` · Ref: \`${refName || 'unknown'}\``,
    `- Failed jobs: ${failed.map((job) => `\`${job.name}\``).join(', ') || '(none reported)'}`,
    `- Passed jobs: ${passed.map((job) => `\`${job.name}\``).join(', ') || '(none)'}`,
    '',
    '### Triage',
    '',
    '1. Open the run page above and read the failing job step.',
    `2. Download all evidence: \`gh run download ${runId}\`.`,
    '3. Reproduce locally with the commands in `docs/testing-guide-zh-CN.md` → 「CI 分层」.',
    '',
    'This issue is the single nightly tracker: a new failure updates this body and',
    'adds one comment, and a green run closes it. It does not open a new issue per day.',
    '',
  );

  for (const job of failed) {
    lines.push(`### ${job.name}`, '');
    lines.push(`Result: \`${results[job.key]}\` · artifact: \`${job.artifact}\``, '');
    const evidence = collectEvidence(artifactDir, job.artifact);
    if (evidence.length === 0) {
      lines.push('_No diagnostic artifact was uploaded for this job._', '');
      continue;
    }
    for (const item of evidence) {
      lines.push(`<details><summary><code>${item.name}</code></summary>`, '');
      lines.push('```text');
      lines.push(item.content);
      lines.push('```', '', '</details>', '');
    }
  }

  lines.push('---', '', 'Generated by `.github/scripts/nightly-issue.mjs`.');
  return `${lines.join('\n')}\n`;
}

function writeOutputs({ status, failedJobs, issueTitle }) {
  const outputFile = process.env.GITHUB_OUTPUT;
  if (!outputFile) return;
  appendFileSync(outputFile, `status=${status}\n`);
  appendFileSync(outputFile, `failed_jobs=${failedJobs.join(',')}\n`);
  appendFileSync(outputFile, `issue_title=${issueTitle}\n`);
}

function main() {
  const artifactDir = argValue('--artifact-dir', '.verification/artifacts');
  const bodyFile = argValue('--body-file', '.verification/nightly/issue-body.md');
  const debug = (process.env.NIGHTLY_DEBUG ?? '') === 'true';
  const forceFailure = (process.env.NIGHTLY_FORCE_FAILURE ?? '') === 'true';

  const results = Object.fromEntries(
    JOBS.map((job) => [job.key, jobResult(job.env)]),
  );
  if (forceFailure) {
    // debug 模式不跑检查 job，用一次模拟失败走完真实的 issue 创建/更新路径。
    results['real-llm'] = 'failure';
  }
  const failed = JOBS.filter((job) => isFailure(results[job.key]));
  const status = failed.length === 0 ? 'success' : 'failed';
  const issueTitleValue = issueTitle(debug);

  const body = buildBody({
    results,
    runId: process.env.GITHUB_RUN_ID ?? '0',
    runNumber: process.env.GITHUB_RUN_NUMBER ?? '0',
    serverUrl: process.env.GITHUB_SERVER_URL ?? 'https://github.com',
    repository: process.env.GITHUB_REPOSITORY ?? 'unknown/unknown',
    eventName: process.env.GITHUB_EVENT_NAME ?? '',
    refName: process.env.GITHUB_REF_NAME ?? '',
    artifactDir,
    debug,
  });

  mkdirSync(dirname(bodyFile), { recursive: true });
  writeFileSync(bodyFile, body, 'utf8');
  writeOutputs({ status, failedJobs: failed.map((job) => job.name), issueTitle: issueTitleValue });

  console.log(
    `nightly issue: status=${status} title=${issueTitleValue} ` +
    `failed=${failed.map((job) => job.name).join(',') || 'none'}`,
  );
  process.stdout.write(body);
}

function selfTest() {
  const results = { gated: 'success', 'failure-modes': 'success', resilience: 'success', 'real-llm': 'failure', 'real-llm-longcat': 'success', soak: 'success', webui: 'success' };
  const body = buildBody({
    results,
    runId: '12345',
    runNumber: '7',
    serverUrl: 'https://github.com',
    repository: 'wubuku/spring-ai-rag',
    eventName: 'schedule',
    refName: 'main',
    artifactDir: '/nonexistent-artifact-dir',
  });
  const required = [
    'https://github.com/wubuku/spring-ai-rag/actions/runs/12345',
    '`real-llm`',
    'No diagnostic artifact was uploaded',
  ];
  for (const needle of required) {
    if (!body.includes(needle)) {
      throw new Error(`self-test body missing: ${needle}`);
    }
  }

  const allGreen = buildBody({
    results: { gated: 'success', 'failure-modes': 'success', resilience: 'success', 'real-llm': 'success', 'real-llm-longcat': 'success', soak: 'skipped', webui: 'success' },
    runId: '1',
    runNumber: '1',
    serverUrl: 'https://github.com',
    repository: 'wubuku/spring-ai-rag',
    eventName: 'schedule',
    refName: 'main',
    artifactDir: '/nonexistent-artifact-dir',
  });
  if (allGreen.includes('## Nightly run failed')) {
    throw new Error('self-test: green run must not render a failure heading');
  }

  const debugBody = buildBody({
    results: { gated: 'success', 'failure-modes': 'success', resilience: 'success', 'real-llm': 'failure', 'real-llm-longcat': 'success', soak: 'success', webui: 'success' },
    runId: '2', runNumber: '2', serverUrl: 'https://github.com',
    repository: 'wubuku/spring-ai-rag', eventName: 'workflow_dispatch', refName: 'main',
    artifactDir: '/nonexistent-artifact-dir', debug: true,
  });
  if (!debugBody.includes('Debug run') || !debugBody.includes('[TEST]')) {
    throw new Error('self-test: debug body must be marked as TEST');
  }
  if (issueTitle(true) !== DEBUG_ISSUE_TITLE || issueTitle(false) !== ISSUE_TITLE) {
    throw new Error('self-test: issue title selection is wrong');
  }
  console.log('nightly-issue self-test: PASS');
}

if (args.has('--self-test')) {
  selfTest();
} else {
  main();
}

export { isFailure, JOBS, ISSUE_TITLE };
