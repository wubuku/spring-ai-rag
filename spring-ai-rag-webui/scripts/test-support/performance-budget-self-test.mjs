#!/usr/bin/env node
/**
 * Self-test for `scripts/check-performance-budget.mjs`.
 *
 * A gate that cannot fail is worse than no gate. The original FCP figure was a
 * human-written number; these cases prove each budget dimension rejects one
 * byte / one resource / one millisecond over the line, and that exactly-at-budget
 * is accepted.
 */
import assert from 'node:assert/strict';
import { budgetFailures } from '../check-performance-budget.mjs';

const budget = {
  initialGzipBytes: 1000,
  initialUncompressedBytes: 2000,
  initialResourceCount: 4,
  localFcpMs: 300,
  slow4gFcpMs: 3000,
};

const atBudget = {
  gzipBytes: 1000,
  rawBytes: 2000,
  count: 4,
  localFcp: 300,
  slowFcp: 3000,
};

assert.deepEqual(budgetFailures(atBudget, budget), [], '恰好等于预算不应失败');

const cases = [
  [{ ...atBudget, gzipBytes: 1001 }, 'gzip'],
  [{ ...atBudget, rawBytes: 2001 }, '未压缩'],
  [{ ...atBudget, count: 5 }, '关键资源'],
  [{ ...atBudget, localFcp: 301 }, '本地 FCP'],
  [{ ...atBudget, slowFcp: 3001 }, 'Slow 4G FCP'],
];

for (const [measured, label] of cases) {
  const failures = budgetFailures(measured, budget);
  assert.ok(
    failures.some(item => item.includes(label)),
    `超预算 1 单位必须失败：${label}`,
  );
}

console.log('性能预算门禁 self-test 通过。');
