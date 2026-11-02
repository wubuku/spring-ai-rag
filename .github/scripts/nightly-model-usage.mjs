#!/usr/bin/env node
// nightly 真实模型 job 的成本护栏。
//
// real-llm job 的后端只为本次检查服务（端口 18081，跑完即停），所以
// `/actuator/metrics/http.server.requests` 的 COUNT 就是一个可靠的
// "本次 run 触发了多少后端请求" 的代理指标。脚本在检查步骤之后运行：
//   - 超过硬上限 → exit 1（作业变红，不再"跑完就算"）；
//   - 读不到指标 → exit 1 并说明原因（无法验证上限就不假装通过）；
//   - 正常 → exit 0，并把用量写入 Step Summary 与 artifact。
//
// 这里刻意不统计日志：后端没有请求访问日志，日志行数不能代表请求数。

import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

const args = process.argv.slice(2);

function argValue(name, fallback = '') {
  const index = args.indexOf(name);
  if (index === -1) return fallback;
  return args[index + 1] ?? fallback;
}

function integerArg(name, fallback) {
  const raw = argValue(name, '');
  if (!raw) return fallback;
  const value = Number.parseInt(raw, 10);
  if (!Number.isFinite(value) || value < 0) {
    throw new Error(`${name} must be a non-negative integer, got '${raw}'`);
  }
  return value;
}

/** 从 Actuator 指标 JSON 中取指定 statistic 的值；缺失返回 null。 */
function measurement(json, statistic) {
  const rows = Array.isArray(json?.measurements) ? json.measurements : [];
  const row = rows.find((item) => item?.statistic === statistic);
  const value = Number(row?.value);
  return Number.isFinite(value) ? value : null;
}

/**
 * 读取一个 Actuator 指标。指标不存在（404）时返回 null，其它错误向上抛出，
 * 让调用方区分"这项指标本来就没有"和"后端/端点不可用"。
 */
async function readMetric(baseUrl, name) {
  const url = `${baseUrl.replace(/\/+$/, '')}/actuator/metrics/${name}`;
  let response;
  try {
    response = await fetch(url, {
      headers: { Accept: 'application/json' },
      signal: AbortSignal.timeout(15_000),
    });
  } catch (error) {
    throw new Error(`cannot reach ${url}: ${error.message}`);
  }
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`HTTP ${response.status} from ${url}`);
  return response.json();
}

export function renderReport({ totalRequests, maxRequests, ragRequests, llmTokens, ok, reason }) {
  const lines = ['## Nightly model usage guard', ''];
  lines.push('| Metric | Value |');
  lines.push('|--------|-------|');
  lines.push(`| Backend HTTP requests (\`http.server.requests\` COUNT) | ${totalRequests} |`);
  lines.push(`| Hard cap (per run) | ${maxRequests} |`);
  lines.push(`| RAG pipeline requests (\`rag.requests.total\`) | ${ragRequests ?? 'n/a'} |`);
  lines.push(`| LLM tokens (\`rag.llm.tokens.total\`) | ${llmTokens ?? 'n/a'} |`);
  lines.push(`| Result | ${ok ? 'PASS' : 'FAIL'} |`);
  if (!ok && reason) {
    lines.push('', `**Reason:** ${reason}`);
  }
  lines.push(
    '',
    'The cap counts every HTTP request on the dedicated nightly backend',
    '(including startup health polling). Exceeding it means the run issued',
    'far more requests than the fixed smoke/goldenset/regression sequence,',
    'so the job fails instead of silently overspending model quota.',
    '',
  );
  return `${lines.join('\n')}\n`;
}

async function main() {
  const baseUrl = argValue('--base-url', 'http://127.0.0.1:18081');
  const maxRequests = integerArg('--max-requests', 300);
  const reportFile = argValue('--report-file', '');

  let totalRequests = null;
  let ragRequests = null;
  let llmTokens = null;
  let reason = '';

  try {
    const serverRequests = await readMetric(baseUrl, 'http.server.requests');
    if (serverRequests === null) {
      reason = '`http.server.requests` is not exposed by /actuator/metrics, so the cap cannot be verified';
    } else {
      totalRequests = measurement(serverRequests, 'COUNT');
      if (totalRequests === null) {
        reason = '`http.server.requests` has no COUNT measurement, so the cap cannot be verified';
      }
    }

    // 这两项是可选的：老版本或指标被关闭时没有，不影响护栏判定。
    const rag = await readMetric(baseUrl, 'rag.requests.total');
    ragRequests = rag === null ? null : measurement(rag, 'COUNT');
    const tokens = await readMetric(baseUrl, 'rag.llm.tokens.total');
    llmTokens = tokens === null ? null : measurement(tokens, 'VALUE');
  } catch (error) {
    reason = error.message;
  }

  const verified = reason === '' && totalRequests !== null;
  const ok = verified && totalRequests <= maxRequests;
  if (verified && !ok) {
    reason = `${totalRequests} backend requests > hard cap ${maxRequests}`;
  }
  const report = renderReport({
    totalRequests: totalRequests ?? 'n/a',
    maxRequests,
    ragRequests,
    llmTokens,
    ok,
    reason,
  });

  process.stdout.write(report);
  if (process.env.GITHUB_STEP_SUMMARY) {
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, report);
  }
  if (reportFile) {
    mkdirSync(dirname(reportFile), { recursive: true });
    writeFileSync(reportFile, report, 'utf8');
  }

  if (!ok) {
    if (!verified) {
      console.error(`::error::model usage guard could not verify the cap: ${reason}`);
    } else {
      console.error(
        `::error::model usage guard exceeded the cap: ${totalRequests} backend requests > ${maxRequests}`,
      );
    }
    process.exit(1);
  }
}

function selfTest() {
  const cases = [
    {
      input: { totalRequests: 120, maxRequests: 300, ragRequests: 40, llmTokens: 9000 },
      ok: true,
      reason: '',
    },
    {
      input: { totalRequests: 301, maxRequests: 300, ragRequests: 40, llmTokens: 9000 },
      ok: false,
      reason: '',
    },
  ];
  for (const testCase of cases) {
    const report = renderReport({ ...testCase.input, ok: testCase.ok, reason: testCase.reason });
    if (!report.includes(`| Result | ${testCase.ok ? 'PASS' : 'FAIL'} |`)) {
      throw new Error('report result line is wrong');
    }
    if (!report.includes(`| Backend HTTP requests (\`http.server.requests\` COUNT) | ${testCase.input.totalRequests} |`)) {
      throw new Error('report request line is wrong');
    }
  }
  const exceededReport = renderReport({
    totalRequests: 301, maxRequests: 300, ragRequests: null, llmTokens: null,
    ok: false, reason: '301 backend requests > hard cap 300',
  });
  if (!exceededReport.includes('301 backend requests > hard cap 300')) {
    throw new Error('exceeded-cap reason is not rendered');
  }
  const missingReport = renderReport({
    totalRequests: 'n/a', maxRequests: 300, ragRequests: null, llmTokens: null,
    ok: false, reason: 'endpoint unreachable',
  });
  if (!missingReport.includes('endpoint unreachable')) {
    throw new Error('missing-metric reason is not rendered');
  }
  console.log('nightly-model-usage self-test: PASS');
}

if (args.includes('--self-test')) {
  selfTest();
} else {
  await main();
}
