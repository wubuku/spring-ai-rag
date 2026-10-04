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
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { scan } from './lib/json-assertion-check.mjs';

const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const { findings, quantifierPrograms, files } = scan(path.join(root, 'scripts'));

console.log(`JSON negative assertions: ${files} shell file(s), ${quantifierPrograms} jq program(s) with a quantifier.`);
if (findings.length === 0) {
  console.log('Every negative assertion inside a quantifier also requires the field to be present.');
  process.exit(0);
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
