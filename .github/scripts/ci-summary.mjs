#!/usr/bin/env node
// CI 失败诊断汇总：从 surefire/failsafe 报告、Playwright JSON 报告与 JaCoCo XML
// 中提取"哪个模块、哪个套件、哪个用例失败"，写入 GitHub Step Summary 并回显。
//
// 这是诊断工具，不是门禁：报告缺失时只写一行提示，绝不改变已失败的作业状态，
// 也不会把原本绿色的作业变红。

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const args = new Set(process.argv.slice(2));
const lines = [];
const write = (line = '') => lines.push(line);

function attributeMap(tag) {
  const values = {};
  for (const match of tag.matchAll(/(\w+)="([^"]*)"/g)) values[match[1]] = match[2];
  return values;
}

function numeric(value) {
  return Number.parseInt(value ?? '0', 10) || 0;
}

function surefireRows() {
  const rows = [];
  for (const moduleName of readdirSync(repoRoot)) {
    if (!moduleName.startsWith('spring-ai-rag-')) continue;
    for (const reportDir of ['surefire-reports', 'failsafe-reports']) {
      const directory = join(repoRoot, moduleName, 'target', reportDir);
      if (!existsSync(directory)) continue;
      for (const file of readdirSync(directory)) {
        if (!file.startsWith('TEST-') || !file.endsWith('.xml')) continue;
        const text = readFileSync(join(directory, file), 'utf8');
        const suiteTag = text.match(/<testsuite\b[^>]*>/)?.[0] ?? '';
        const suite = attributeMap(suiteTag);
        rows.push({
          module: moduleName,
          report: reportDir === 'surefire-reports' ? 'surefire' : 'failsafe',
          suite: suite.name ?? file,
          tests: numeric(suite.tests),
          failures: numeric(suite.failures),
          errors: numeric(suite.errors),
          skipped: numeric(suite.skipped),
          time: Number.parseFloat(suite.time ?? '0') || 0,
          failuresDetail: failingCases(text, suite.name ?? file),
        });
      }
    }
  }
  return rows.sort((left, right) => left.module.localeCompare(right.module)
    || left.suite.localeCompare(right.suite));
}

function failingCases(text, suiteName) {
  const cases = [];
  // surefire 会把无失败的用例写成自闭合 `<testcase .../>`；如果把这种标签
  // 当成开始标签，后面的失败会被错误地挂到上一个通过的用例名上。
  for (const match of text.matchAll(
    /<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
    const attrs = attributeMap(match[1]);
    const body = match[2] ?? '';
    const failureTag = body.match(/<(?:failure|error)\b[^>]*>/)?.[0];
    if (!failureTag) continue;
    const failureAttrs = attributeMap(failureTag);
    const message = (failureAttrs.message ?? body)
      .replace(/&quot;/g, '"')
      .replace(/&apos;/g, "'")
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&amp;/g, '&')
      .split('\n')[0]
      .slice(0, 160);
    cases.push({
      suite: suiteName,
      name: attrs.name ?? '(unnamed)',
      message,
    });
  }
  return cases;
}

function playwrightFailures() {
  const reportPath = join(repoRoot, 'spring-ai-rag-webui', 'test-results', 'results.json');
  if (!existsSync(reportPath)) return undefined;
  let data;
  try {
    data = JSON.parse(readFileSync(reportPath, 'utf8'));
  } catch {
    return undefined;
  }
  const failures = [];
  const walk = (suite, inheritedFile) => {
    const file = suite.file ?? inheritedFile;
    for (const spec of suite.specs ?? []) {
      for (const test of spec.tests ?? []) {
        const results = test.results ?? [];
        const failed = results.find((result) => result.status === 'failed' || result.status === 'timedOut');
        if (!failed) continue;
        const messages = (failed.errors ?? [])
          .map((error) => (error.message ?? '').split('\n')[0])
          .filter(Boolean);
        failures.push({
          file,
          title: [suite.title, spec.title].filter(Boolean).join(' › '),
          message: (messages[0] ?? failed.status ?? 'failed').slice(0, 200),
        });
      }
    }
    for (const child of suite.suites ?? []) walk(child, file);
  };
  for (const suite of data.suites ?? []) walk(suite, undefined);
  return {
    stats: data.stats ?? {},
    failures,
    reportPath,
  };
}

function jacocoRows() {
  const rows = [];
  for (const moduleName of readdirSync(repoRoot)) {
    if (!moduleName.startsWith('spring-ai-rag-')) continue;
    const report = join(repoRoot, moduleName, 'target', 'site', 'jacoco', 'jacoco.xml');
    if (!existsSync(report) || !statSync(report).isFile()) continue;
    const xml = readFileSync(report, 'utf8');
    // 每个 counter 都会在类、包与 report 级重复出现；report 级的最后一次
    // 出现才是模块总计。
    const lastCounter = (type) => {
      let found;
      for (const match of xml.matchAll(new RegExp(`<counter type="${type}" missed="(\\d+)" covered="(\\d+)"`, 'g'))) {
        found = { missed: numeric(match[1]), covered: numeric(match[2]) };
      }
      return found;
    };
    const line = lastCounter('LINE');
    const branch = lastCounter('BRANCH');
    const percent = (counter) => {
      if (!counter || counter.missed + counter.covered === 0) return 'n/a';
      return `${((counter.covered / (counter.missed + counter.covered)) * 100).toFixed(2)}%`;
    };
    rows.push({
      module: moduleName,
      line: percent(line),
      branch: percent(branch),
    });
  }
  return rows.sort((left, right) => left.module.localeCompare(right.module));
}

function addSurefireSection() {
  const rows = surefireRows();
  write('### JUnit / Surefire');
  if (rows.length === 0) {
    write('_No surefire or failsafe reports were found._');
    write();
    return;
  }
  const totals = rows.reduce((sum, row) => ({
    tests: sum.tests + row.tests,
    failures: sum.failures + row.failures,
    errors: sum.errors + row.errors,
    skipped: sum.skipped + row.skipped,
    time: sum.time + row.time,
  }), { tests: 0, failures: 0, errors: 0, skipped: 0, time: 0 });
  // 逐套件列全表会把摘要淹掉（本仓库有 2000+ 个 surefire 报告）。摘要只给
  // 模块总计，失败时再点名套件；完整清单在 `backend-test-results` artifact 里。
  const byModule = new Map();
  for (const row of rows) {
    const entry = byModule.get(row.module) ?? {
      module: row.module, suites: 0, tests: 0, failures: 0, errors: 0,
      skipped: 0, time: 0,
    };
    entry.suites += 1;
    entry.tests += row.tests;
    entry.failures += row.failures;
    entry.errors += row.errors;
    entry.skipped += row.skipped;
    entry.time += row.time;
    byModule.set(row.module, entry);
  }
  const moduleRows = [...byModule.values()].sort(
    (left, right) => left.module.localeCompare(right.module));
  write(table(
    ['Module', 'Suites', 'Tests', 'Failures', 'Errors', 'Skipped', 'Time (s)'],
    moduleRows.map((row) => [
      row.module,
      String(row.suites),
      String(row.tests),
      String(row.failures),
      String(row.errors),
      String(row.skipped),
      row.time.toFixed(2),
    ]),
  ));
  write(`**Totals:** ${totals.tests} tests in ${rows.length} suites, `
    + `${totals.failures} failures, ${totals.errors} errors, `
    + `${totals.skipped} skipped.`);
  const failingSuites = rows.filter((row) => row.failures + row.errors > 0);
  const failures = failingSuites.flatMap((row) => row.failuresDetail);
  if (failures.length > 0) {
    write();
    write('**Failing cases:**');
    for (const failure of failures.slice(0, 50)) {
      write(`- \`${failure.suite}\` › \`${failure.name}\` — ${failure.message}`);
    }
    if (failures.length > 50) write(`- …and ${failures.length - 50} more.`);
  } else if (totals.failures + totals.errors > 0) {
    write();
    write('A suite reports failures but no failing testcase body was found; '
      + 'open the uploaded surefire report for the stack trace.');
  }
  if (failingSuites.length > 0) {
    write();
    write(`**Suites with failures:** ${failingSuites
      .map((row) => `\`${row.suite}\``)
      .join(', ')}`);
  }
  write();
}

function addPlaywrightSection() {
  const result = playwrightFailures();
  write('### Playwright');
  if (!result) {
    write('_No Playwright JSON report was found at '
      + '`spring-ai-rag-webui/test-results/results.json`._');
    write();
    return;
  }
  const { expected = 0, unexpected = 0, flaky = 0, skipped = 0 } = result.stats;
  write(`**Stats:** ${expected} passed, ${unexpected} failed, ${flaky} flaky, ${skipped} skipped.`);
  if (result.failures.length === 0) {
    write('No failing Playwright tests.');
  } else {
    write('**Failing specs:**');
    for (const failure of result.failures.slice(0, 50)) {
      write(`- \`${failure.file ?? 'unknown file'}\` › \`${failure.title}\` — ${failure.message}`);
    }
    if (result.failures.length > 50) {
      write(`- …and ${result.failures.length - 50} more.`);
    }
  }
  write();
}

function addJacocoSection() {
  const rows = jacocoRows();
  write('### JaCoCo coverage (trend only, non-blocking)');
  if (rows.length === 0) {
    write('_No JaCoCo XML reports were found._');
    write();
    return;
  }
  write(table(
    ['Module', 'Line covered', 'Branch covered'],
    rows.map((row) => [row.module, row.line, row.branch]),
  ));
  write('No threshold is enforced: this repository has no committed coverage baseline, '
    + 'and an invented floor would fail on a refactor rather than on a regression.');
  write();
}

function table(headers, rows) {
  const escape = (value) => String(value).replace(/\|/g, '\\|');
  return [
    `| ${headers.map(escape).join(' | ')} |`,
    `| ${headers.map(() => '---').join(' | ')} |`,
    ...rows.map((row) => `| ${row.map(escape).join(' | ')} |`),
  ].join('\n');
}

async function main() {
  write('## CI diagnostics');
  write();
  try {
    if (args.has('--surefire')) addSurefireSection();
    if (args.has('--playwright')) addPlaywrightSection();
    if (args.has('--jacoco')) addJacocoSection();
  } catch (error) {
    write(`Summary generation failed: ${error.message}`);
  }
  const markdown = `${lines.join('\n')}\n`;
  if (process.env.GITHUB_STEP_SUMMARY) {
    const { appendFileSync } = await import('node:fs');
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, markdown);
  }
  process.stdout.write(markdown);
}

await main();
