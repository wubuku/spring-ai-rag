#!/usr/bin/env node
// A jq predicate that says "nothing here is wrong" must be able to tell the
// difference between "nothing is wrong" and "I cannot see".
//
// Batch 895 found one such predicate by reading two of them by hand: the gate
// asserting that no expiry alert was firing also reported an alert that was
// firing, because a renamed `metrics.principalId` reads as `null` and `null` is
// not the principal under test. It had to be worked around from outside, by a
// guard that refuses any payload the reader cannot attribute.
//
// The workaround is the wrong shape. Two findings from the census that followed
// show it: `real-collection-purge-e2e-smoke.sh` and this file's own sibling
// assertion both carry the same fail-open shape, and neither has a guard. This
// gate is the one that says the shape is not allowed, so the next occurrence is
// a finding rather than something to be found by reading.
//
// The rule: inside `all(...)` or `any(...)`, a negative assertion — a field
// compared with `!=`, or a `// ""`-guarded containment test negated with `| not`
// — is satisfied by a field that reads as absent. It is allowed when the same
// program also requires that field to be present (`<field> != null`), which
// narrows the assertion to "the field is readable and says something else".
//
// There is deliberately no allowlist, and that is a consequence rather than a
// preference: a fail-open predicate cannot be exempted without one, and an
// allowlist is a list of things this gate does not check. The predicate Batch
// 895 had to guard from outside is closed from inside instead — see
// scripts/lib/alert-payload.sh.
//
// Known limitation, measured by `scripts/test-support/json-assertions-self-test.mjs`:
// shell only. A .mjs file embedding a jq program in a template literal would be
// invisible here.
//
// Batch 898 found out why that limitation is not academic. The same defect — a
// comparison satisfied by a value the reader could not obtain — was sitting in
// `scripts/run-retrieval-regression.sh`, in Python, in the one gate whose whole
// job is catching a quality regression:
//
//     previous = float(baseline_metrics.get(name, 0.0))
//
// A metric the committed baseline happened not to carry was judged against zero
// and so could never regress: measured, an ndcg drop from 1.0 to 0.70 was
// reported with a complete baseline and silently passed with one that had lost
// the key. Those three readers now live in scripts/lib/retrieval_baseline.py and
// are run by scripts/test-support/retrieval-baseline-self-test.mjs — but they
// are covered by a self-test, not by this gate. **A rule that looks at one
// language is a rule about that language**, and teaching this scanner another
// one is still open work.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { scan } from './lib/json-assertion-check.mjs';
import { scanPython } from './lib/python-assertion-check.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

/**
 * Batch 902. This gate used to run at import: every top-level statement was
 * the program. Nothing imported it, so nothing was broken — but a gate that
 * executes when a module is read cannot be read by a test, and Batch 901 named
 * this as the one thing its own gate deliberately does not police. The reason
 * it is not policed is worth keeping: `check-alignment-policy.mjs` is a module
 * other checks import, so "no guard" is sometimes the right shape, and a rule
 * that reported it would report a correct file. That is a judgement, and a
 * list of judgements belongs in the ledger, not in a gate.
 */
function main() {

  const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
  const { findings, quantifierPrograms, files } = scan(path.join(root, 'scripts'));

  const python = scanPython(path.join(root, 'scripts'));

  console.log(`JSON negative assertions: ${files} shell file(s), ${quantifierPrograms} jq program(s) with a quantifier.`);
  console.log(`Python falsy defaults in assertions: ${python.sources} source(s), ${python.lines} line(s).`);
  if (findings.length === 0 && python.findings.length === 0) {
    console.log('Every negative assertion inside a quantifier also requires the field to be present,');
    console.log('and no Python comparison is satisfied by a value its reader never obtained.');
    process.exit(0);
  }

  if (findings.length === 0) {
    for (const f of python.findings) {
      console.error(`  ${f.file}:${f.line}  ${f.reason}`);
      console.error(`      ${f.text}`);
      console.error('');
    }
    console.error('Fix it by keeping the absence distinguishable — return None or raise, and');
    console.error('report the metric by name instead of substituting a falsy default.');
    process.exit(1);
  }

  console.error(`\n${findings.length} negative assertion(s) an absent field would satisfy:\n`);
  for (const f of findings) {
    console.error(`  scripts/${f.file}:${f.line}  .${f.field.replace(/^\./, '')}  [${f.shape}]`);
    console.error(`      ${f.reason}`);
    console.error(`      ${f.program}`);
    console.error('');
  }
  console.error('Fix it by requiring the field in the same program — append `and .'
    + '<field> != null` inside the quantifier — or, when the assertion is about a key '
    + '\'s absence rather than a value, compare the element\'s key set against the '
    + 'fields the contract declares.');
  process.exit(1);
}

if (isMainModule(import.meta.url)) main();
