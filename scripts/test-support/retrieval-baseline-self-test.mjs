#!/usr/bin/env node
// Self-test for scripts/lib/retrieval_baseline.py.
//
// Batch 896 made "a negative JSON assertion must not be satisfied by a field it
// cannot read" a gate over `scripts/**/*.sh`. This file is the same defect in
// Python, in the one gate whose entire job is catching a quality regression —
// and Batch 896's scanner could not see it, because a rule that looks at one
// language is a rule about one language.
//
// The regression it has to catch, measured before the fix on this repository's
// own dataset (maximumRegression tolerates 0.15 of ndcg, aggregateMinimum asks
// for 0.6, the committed baseline scores 1.0):
//
//   baseline complete, ndcg 1.0 → 0.70  ->  ['aggregate regression: ndcg']
//   baseline missing ndcg, same run    ->  []
//
// So the cases below run the real Python module — a JavaScript restatement of
// the rule would pass while the Python one rotted, which is the same mistake
// 894 and 895 were written against.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const libDir = path.join(repoRoot, 'scripts/lib');
const modulePath = path.join(libDir, 'retrieval_baseline.py');

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

/** Run a snippet against the real module and return its JSON stdout. */
function py(body) {
  const out = execFileSync('python3', ['-c', `
import json, sys
sys.path.insert(0, ${JSON.stringify(libDir)})
import retrieval_baseline as rb
${body}
`], { encoding: 'utf8' });
  return JSON.parse(out);
}

const METRICS = { precisionAtK: 0.36, recallAtK: 1.0, mrr: 1.0, ndcg: 1.0, hitRate: 1.0 };
const TOLERANCES = { hitRate: 0.0, mrr: 0.15, recallAtK: 0.0, ndcg: 0.15 };

test('the module is importable and exposes every function the script calls', () => {
  // The positive control: a module that failed to import, or lost a function,
  // would make every other case here pass or fail for the wrong reason.
  const names = py('print(json.dumps(sorted(n for n in dir(rb) if not n.startswith("_"))))');
  for (const fn of ['check_minimum', 'compare_aggregate', 'format_metric',
    'metric_at_k', 'metrics_unreadable']) {
    assert.ok(names.includes(fn), `${fn} is missing from the module`);
  }
});

test('a metric that regressed past its tolerance is reported', () => {
  const failures = py(`
b = ${JSON.stringify(METRICS)}
a = dict(b, ndcg=0.70)
print(json.dumps(rb.compare_aggregate(b, a, ${JSON.stringify(TOLERANCES)})))
`);
  assert.equal(failures.length, 1, `expected one regression, got ${JSON.stringify(failures)}`);
  assert.match(failures[0], /ndcg=0\.700000.*baseline=1\.000000.*tolerance=0\.150000/);
});

test('a drop inside the tolerance is not reported', () => {
  // The over-rejection direction. If this failed, the only way to go green on
  // the case above would have been to report everything.
  const failures = py(`
b = ${JSON.stringify(METRICS)}
a = dict(b, ndcg=0.90)
print(json.dumps(rb.compare_aggregate(b, a, ${JSON.stringify(TOLERANCES)})))
`);
  assert.deepEqual(failures, []);
});

test('a baseline missing a metric is a failure, not a silent pass', () => {
  // The regression. Before the fix this returned [] with ndcg at 0.70.
  const failures = py(`
b = {k: v for k, v in ${JSON.stringify(METRICS)}.items() if k != "ndcg"}
a = dict(${JSON.stringify(METRICS)}, ndcg=0.70)
print(json.dumps(rb.compare_aggregate(b, a, ${JSON.stringify(TOLERANCES)})))
`);
  assert.ok(
    failures.some((f) => /committed baseline carries no 'ndcg'/.test(f)),
    `a missing baseline metric was not reported: ${JSON.stringify(failures)}`,
  );
});

test('a run that produced no value for a metric is a failure, not a zero', () => {
  const failures = py(`
b = ${JSON.stringify(METRICS)}
a = dict(${JSON.stringify(METRICS)})
a["ndcg"] = None
print(json.dumps(rb.compare_aggregate(b, a, ${JSON.stringify(TOLERANCES)})))
`);
  assert.ok(
    failures.some((f) => /this run's aggregate carries no 'ndcg'/.test(f)),
    `an unreadable run metric was not reported: ${JSON.stringify(failures)}`,
  );
  assert.ok(!failures.some((f) => /ndcg=0\.000000/.test(f)),
    'the unreadable metric was still compared as if it were 0.0');
});

test('a non-numeric metric is reported as unreadable rather than crashing', () => {
  const failures = py(`
b = ${JSON.stringify(METRICS)}
a = dict(${JSON.stringify(METRICS)}, mrr="not a number")
print(json.dumps(rb.compare_aggregate(b, a, ${JSON.stringify(TOLERANCES)})))
`);
  assert.ok(failures.length >= 1, 'a string metric produced no failure at all');
  assert.match(failures[0], /not a number/);
});

test('a missing metric never satisfies a minimum', () => {
  // The second reader. The shipped `metrics.get(name, 0.0)` made a floor of
  // 0.0 pass for a metric the run never produced.
  const failures = py(`
m = dict(${JSON.stringify(METRICS)}, mrr=None)
print(json.dumps(rb.check_minimum("case-1", m, {"mrr": 0.0})))
`);
  assert.ok(
    failures.some((f) => /carries no 'mrr'/.test(f)),
    `a floor of 0.0 was satisfied by a metric that was never measured: ${JSON.stringify(failures)}`,
  );
});

test('a minimum below the measurement is still reported', () => {
  const failures = py(`
m = dict(${JSON.stringify(METRICS)}, mrr=0.4)
print(json.dumps(rb.check_minimum("case-1", m, {"mrr": 0.5})))
`);
  assert.equal(failures.length, 1);
  assert.match(failures[0], /case-1: mrr=0\.400000 < 0\.500000/);
});

test('metric_at_k distinguishes "absent" from "zero"', () => {
  const result = py(`
print(json.dumps([
    rb.metric_at_k(None, 5),
    rb.metric_at_k(0.0, 5),
    rb.metric_at_k({"5": 0.5}, 5),
    rb.metric_at_k("0.25", 5),
    rb.metric_at_k(True, 5),
    rb.metric_at_k(float("nan"), 5),
]))
`);
  assert.deepEqual(result, [null, 0.0, 0.5, 0.25, null, null]);
  // The whole upstream fix: 0.0 and "not supplied" must be different values,
  // or everything downstream is deciding on a distinction that no longer exists.
  assert.notEqual(result[1], result[0]);
});

test('format_metric prints n/a rather than a fabricated zero', () => {
  const result = py('print(json.dumps([rb.format_metric(None), rb.format_metric(0.5)]))');
  assert.deepEqual(result, ['n/a', '0.5000']);
});

test('the shipped dataset and baseline agree on every metric they judge', () => {
  // A positive control on the real artifacts, not on fixtures: the dataset names
  // four metrics in maximumRegression and the committed baseline carries all
  // four. If someone drops one, this is the case that says so before a run
  // silently stops judging it.
  const dataset = JSON.parse(readFileSync(
    path.join(repoRoot, 'testdata/regression/retrieval-core-v1.json'), 'utf8'));
  const baseline = JSON.parse(readFileSync(
    path.join(repoRoot, 'testdata/regression/retrieval-core-v1-baseline.json'), 'utf8'));
  const judged = Object.keys(dataset.maximumRegression ?? {});
  const missing = judged.filter((name) => !(name in (baseline.aggregate ?? {})));
  assert.deepEqual(missing, [],
    `the committed baseline carries no ${missing.join(', ')}, so ${missing.length} ` +
    'regression check(s) would never run');
  const floored = Object.keys(dataset.aggregateMinimum ?? {});
  assert.deepEqual(floored.filter((name) => !(name in (baseline.aggregate ?? {}))), [],
    'the committed baseline carries no metric the dataset floors');
  // Presence is half of it. A baseline carrying "0.36" as a string reaches
  // float() at run time and fails there, which is a worse place to find out than
  // here; the first draft of this case checked keys only, and a reverse control
  // put "0.36" in as a string and nothing noticed.
  const judgedNames = [...new Set([...judged, ...floored])];
  const nonNumeric = judgedNames.filter(
    (name) => typeof (baseline.aggregate ?? {})[name] !== 'number');
  assert.deepEqual(nonNumeric, [],
    `the committed baseline carries ${nonNumeric.join(', ')} as something other than a number`);
});

test('the regression script reads these functions instead of its own copies', () => {
  // The wiring half. A library nobody calls is a library that rots, and a
  // script that keeps its own copy of the rule is the defect all over again.
  const src = readFileSync(path.join(repoRoot, 'scripts/run-retrieval-regression.sh'), 'utf8');
  assert.match(src, /from retrieval_baseline import/,
    'the script does not import the tested module');
  assert.match(src, /compare_aggregate\(/, 'the baseline comparison is still inline');
  assert.match(src, /check_minimum\(/, 'the minimum check is still inline');
  // The shipped shapes, gone.
  assert.ok(!/baseline_metrics\.get\(name, 0\.0\)/.test(src),
    'the silent 0.0 baseline default is back');
  assert.ok(!/metrics\.get\(name, 0\.0\)/.test(src),
    'the silent 0.0 metrics default is back');
  assert.ok(!/float\(evaluation\.get\("(\w+)"\) or 0\.0\)/.test(src),
    'a metric the run did not supply is being turned into 0.0 again');
  assert.match(src, /metricsReadable/, 'unreadable cases are still counted in the aggregate');
  // Matching the word alone was not enough: a mutation that removed
  // `and item["metricsReadable"]` from the aggregate filter left the key
  // assignment in place, so the case passed while unreadable cases were being
  // averaged in again. The claim is about the filter, so the filter is matched.
  assert.match(
    src,
    /not item\["expectedEmpty"\] and item\["metricsReadable"\]/,
    'unreadable cases are averaged into the aggregate again',
  );
});

let failed = 0;
for (const { title, fn } of cases) {
  try {
    fn();
    console.log(`ok   ${title}`);
  } catch (error) {
    failed += 1;
    console.error(`FAIL ${title}`);
    console.error(`     ${error.message}`);
  }
}

if (failed > 0) {
  console.error(`\n${failed}/${cases.length} retrieval-baseline self-test case(s) failed.`);
  process.exitCode = 1;
} else {
  console.log(`\nAll ${cases.length} retrieval-baseline self-test cases passed.`);
}
