#!/usr/bin/env node
/**
 * WebUI 首屏性能预算门禁（不需要后端）。
 *
 * 此前 §3.8 的 FCP 是人工跑一次记录下来的数字，没有任何东西阻止它变坏。
 * 本脚本把预算变成可执行检查：
 *
 *   1. 生产构建产物首屏初始资源总 gzip / 未压缩大小上限
 *   2. 首屏关键资源数量上限
 *   3. 本地 FCP 上限
 *   4. Slow 4G（1.6 Mbps / 150 ms）FCP 上限
 *
 * 资源大小直接读 `dist/index.html` 与 `dist/assets`；FCP 用 Playwright
 * Chromium 在本地静态服务器上测 3 次取中位数，全程不请求任何后端。
 * 任一指标超限、dist 缺失或过期（src 比 dist 新）都会失败。
 */
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

const WEBUI_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DIST = path.join(WEBUI_ROOT, 'dist');
const SRC = path.join(WEBUI_ROOT, 'src');
const BUDGET = JSON.parse(
  fs.readFileSync(path.join(WEBUI_ROOT, 'scripts/testdata/performance-budget.json'), 'utf8'),
);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.woff2': 'font/woff2',
  '.json': 'application/json; charset=utf-8',
};

function fail(message) {
  throw new Error(message);
}

function newestMtime(dir) {
  let newest = 0;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    newest = Math.max(newest, entry.isDirectory() ? newestMtime(full) : fs.statSync(full).mtimeMs);
  }
  return newest;
}

function assertFreshBuild() {
  const index = path.join(DIST, 'index.html');
  if (!fs.existsSync(index)) fail('dist/index.html 不存在：请先执行 npm run build');
  const distTime = fs.statSync(index).mtimeMs;
  if (newestMtime(SRC) > distTime) {
    fail('dist 比 src 旧：请先重新执行 npm run build，预算门禁必须针对当前源码的产物');
  }
}

/** 从 index.html 提取首屏关键资源（script / stylesheet / modulepreload）。 */
function initialResources(html) {
  const refs = [...html.matchAll(/(?:src|href)="(\/webui\/assets\/[^"]+)"/g)].map(match => match[1]);
  const unique = [...new Set(refs)];
  return unique.map(ref => {
    const file = path.join(DIST, ref.replace('/webui/', ''));
    if (!fs.existsSync(file)) fail(`index.html 引用的资源不存在：${ref}`);
    const raw = fs.readFileSync(file);
    return { ref, rawBytes: raw.length, gzipBytes: zlib.gzipSync(raw).length };
  });
}

function kb(bytes) {
  return `${(bytes / 1024).toFixed(1)} KB`;
}

/** 极简静态服务器：只服务 dist，不压缩，模拟最坏传输。 */
async function startServer() {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    let pathname = decodeURIComponent(url.pathname);
    if (pathname === '/webui' || pathname === '/webui/') pathname = '/webui/index.html';
    const file = path.join(DIST, pathname.replace('/webui/', ''));
    if (!file.startsWith(DIST) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      res.writeHead(404);
      res.end('not found');
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-store',
    });
    fs.createReadStream(file).pipe(res);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return server;
}

async function measureFcp(browser, url, throttle) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  if (throttle) {
    const session = await context.newCDPSession(page);
    await session.send('Network.emulateNetworkConditions', {
      offline: false,
      latency: BUDGET.slow4g.latencyMs,
      downloadThroughput: BUDGET.slow4g.downloadBytesPerSecond,
      uploadThroughput: BUDGET.slow4g.uploadBytesPerSecond,
    });
  }
  try {
    await page.goto(url, { waitUntil: 'load', timeout: 60_000 });
    await page.waitForFunction(
      () => performance.getEntriesByType('paint').some(item => item.name === 'first-contentful-paint'),
      undefined,
      { timeout: 30_000 },
    );
    const fcp = await page.evaluate(() => {
      const entry = performance.getEntriesByType('paint')
        .find(item => item.name === 'first-contentful-paint');
      return entry ? entry.startTime : null;
    });
    if (fcp === null) fail('页面没有产生 first-contentful-paint 条目');
    return fcp;
  } finally {
    await context.close();
  }
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

/** 把实测值对预算求差；独立成纯函数，self-test 才能证明它真的会拒绝。 */
export function budgetFailures(measured, budget = BUDGET) {
  const failures = [];
  if (measured.gzipBytes > budget.initialGzipBytes) {
    failures.push(`首屏 gzip ${kb(measured.gzipBytes)} > 预算 ${kb(budget.initialGzipBytes)}`);
  }
  if (measured.rawBytes > budget.initialUncompressedBytes) {
    failures.push(`首屏未压缩 ${kb(measured.rawBytes)} > 预算 ${kb(budget.initialUncompressedBytes)}`);
  }
  if (measured.count > budget.initialResourceCount) {
    failures.push(`首屏关键资源 ${measured.count} 个 > 预算 ${budget.initialResourceCount} 个`);
  }
  if (measured.localFcp > budget.localFcpMs) {
    failures.push(`本地 FCP ${measured.localFcp.toFixed(0)} ms > 预算 ${budget.localFcpMs} ms`);
  }
  if (measured.slowFcp > budget.slow4gFcpMs) {
    failures.push(`Slow 4G FCP ${measured.slowFcp.toFixed(0)} ms > 预算 ${budget.slow4gFcpMs} ms`);
  }
  return failures;
}

function runSelfTest() {
  const ok = {
    gzipBytes: BUDGET.initialGzipBytes,
    rawBytes: BUDGET.initialUncompressedBytes,
    count: BUDGET.initialResourceCount,
    localFcp: BUDGET.localFcpMs,
    slowFcp: BUDGET.slow4gFcpMs,
  };
  if (budgetFailures(ok).length !== 0) fail('self-test: 恰好等于预算不应失败');
  if (!budgetFailures({ ...ok, gzipBytes: BUDGET.initialGzipBytes + 1 })
    .some(item => item.includes('gzip'))) fail('self-test: gzip 超 1 字节应失败');
  if (!budgetFailures({ ...ok, rawBytes: BUDGET.initialUncompressedBytes + 1 })
    .some(item => item.includes('未压缩'))) fail('self-test: 未压缩超 1 字节应失败');
  if (!budgetFailures({ ...ok, count: BUDGET.initialResourceCount + 1 })
    .some(item => item.includes('关键资源'))) fail('self-test: 资源数超 1 应失败');
  if (!budgetFailures({ ...ok, localFcp: BUDGET.localFcpMs + 1 })
    .some(item => item.includes('本地 FCP'))) fail('self-test: 本地 FCP 超 1ms 应失败');
  if (!budgetFailures({ ...ok, slowFcp: BUDGET.slow4gFcpMs + 1 })
    .some(item => item.includes('Slow 4G FCP'))) fail('self-test: Slow 4G FCP 超 1ms 应失败');
  console.log('性能预算门禁 self-test 通过。');
}

async function main() {
  assertFreshBuild();
  const html = fs.readFileSync(path.join(DIST, 'index.html'), 'utf8');
  const resources = initialResources(html);
  const gzipBytes = resources.reduce((sum, item) => sum + item.gzipBytes, 0);
  const rawBytes = resources.reduce((sum, item) => sum + item.rawBytes, 0);
  const count = resources.length;

  const server = await startServer();
  const url = `http://127.0.0.1:${server.address().port}/webui/`;
  const browser = await chromium.launch({ headless: true });
  let failures = [];
  let localFcp;
  let slowFcp;
  try {
    const localRuns = [];
    const slowRuns = [];
    for (let i = 0; i < BUDGET.runs; i++) {
      localRuns.push(await measureFcp(browser, url, false));
      slowRuns.push(await measureFcp(browser, url, true));
    }
    localFcp = median(localRuns);
    slowFcp = median(slowRuns);
    failures = budgetFailures({ gzipBytes, rawBytes, count, localFcp, slowFcp });

    console.log('WebUI 首屏性能预算：');
    console.log(`  首屏关键资源 ${count} 个（预算 ≤${BUDGET.initialResourceCount}）`);
    console.log(`  首屏 gzip ${kb(gzipBytes)}（预算 ≤${kb(BUDGET.initialGzipBytes)}）`);
    console.log(`  首屏未压缩 ${kb(rawBytes)}（预算 ≤${kb(BUDGET.initialUncompressedBytes)}）`);
    console.log(`  本地 FCP ${localFcp.toFixed(0)} ms（预算 ≤${BUDGET.localFcpMs} ms）`);
    console.log(`  Slow 4G FCP ${slowFcp.toFixed(0)} ms（预算 ≤${BUDGET.slow4gFcpMs} ms）`);
    for (const item of resources) {
      console.log(`    - ${item.ref}  ${kb(item.rawBytes)} raw / ${kb(item.gzipBytes)} gzip`);
    }
  } finally {
    await browser.close();
    server.close();
  }

  if (failures.length > 0) {
    console.error('性能预算超限：');
    for (const item of failures) console.error(`  - ${item}`);
    process.exit(1);
  }
  console.log('性能预算通过。');
}

const IS_ENTRYPOINT = process.argv[1]
  && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (!IS_ENTRYPOINT) {
  // 被 Vitest self-test import 时不执行构建与浏览器测量。
} else if (process.argv.includes('--self-test')) {
  try {
    runSelfTest();
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
} else {
  main().catch(error => {
    console.error(error.message);
    process.exit(1);
  });
}
