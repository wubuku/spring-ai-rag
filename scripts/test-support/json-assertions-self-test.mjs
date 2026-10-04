#!/usr/bin/env node
// Self-test for scripts/verify-json-assertions.mjs.
//
// The thing under test is a parser, and a parser has one failure mode that is
// indistinguishable from success: it recognises nothing. Two of them showed up
// while this gate was being written, and both are cases below.
//
//   1. The first version located jq programs with /'([^']*)'/g. It reported the
//      known-bad predicate as prose inside a comment, and swallowed the real one
//      into a mis-paired region. It "passed" its positive control.
//   2. The first version of the census read scripts/ non-recursively, so the
//      predicate that Batch 895 had moved into scripts/lib/ was invisible and
//      the positive control aborted — which is the other failure mode: a probe
//      that cannot find anything looks exactly like a clean tree.
//
// So the controls here are shaped around those two: the analyser must find a
// planted defect in a synthetic file, it must not find it when the same program
// is quoted in a comment, and it must say so when it finds nothing.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  programFindings,
  scan,
  shellFiles,
  singleQuotedPrograms,
  stripJqComments,
  stripShellComments,
} from '../lib/json-assertion-check.mjs';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const scriptsDir = path.join(repoRoot, 'scripts');

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

/** Build a throwaway scripts/ tree and scan it. */
function scanFixture(files) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'json-assertions-'));
  try {
    for (const [name, content] of Object.entries(files)) {
      const full = path.join(dir, name);
      fs.mkdirSync(path.dirname(full), { recursive: true });
      fs.writeFileSync(full, content);
    }
    return scan(dir);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

test('the analyser is importable and every function it exports exists', () => {
  // The positive control for the whole file. A broken import that yielded
  // undefined would make every other case here fail for the same wrong reason,
  // or — worse — make them skip.
  for (const fn of [programFindings, scan, shellFiles, singleQuotedPrograms, stripShellComments]) {
    assert.equal(typeof fn, 'function');
  }
  assert.ok(programFindings('all(.[]; .a != 1)').length === 1, 'the analyser found nothing in a known-bad program');
});

test('a field compared with != inside a quantifier is a finding', () => {
  const found = programFindings('all(.[]; .alertType != "API_PRINCIPAL_EXPIRY")');
  assert.equal(found.length, 1);
  assert.equal(found[0].field, '.alertType');
  assert.match(found[0].shape, /field != value/);
});

test('the same comparison outside a quantifier is not this gate\'s business', () => {
  // `(.f != $x)` in a top-level conjunction is over a single object, not a list
  // this gate can reason about. Reporting it would be noise, and a gate whose
  // findings need an allowlist is not a check.
  assert.deepEqual(programFindings('.configuredProviders != ["DINGTALK"]'), []);
});

test('a guarded empty containment negated with `| not` is a finding', () => {
  // The shape this repository reaches for as a *fix*, which is what makes it
  // unsafe: an unreadable field becomes "" and the empty string contains nothing.
  const found = programFindings('all(.[]; ((.chunkText // "") | contains($t) | not))');
  assert.equal(found.length, 1);
  assert.equal(found[0].field, '.chunkText');
  assert.match(found[0].shape, /\|\s*not/);
});

test('requiring the field in the same program closes the finding', () => {
  // Over-rejection runs the other way too: if this were still a finding, the
  // only way to go green would have been an allowlist.
  assert.deepEqual(programFindings('all(.[]; .documentId != null and .documentId != $d)'), []);
  assert.deepEqual(
    programFindings('all(.[]; (.alertType != null) and ((.alertType != "X")'
      + ' or ((.metrics.principalId != null) and (.metrics.principalId != $p))))'),
    [],
  );
});

test('requiring a different field does not close it', () => {
  // The loophole an allowlist would have grown: satisfying the rule with an
  // unrelated presence check.
  const found = programFindings('all(.[]; .score != null and .documentId != $d)');
  assert.equal(found.length, 1);
  assert.equal(found[0].field, '.documentId');
});

test('a predicate quoted in a jq `#` comment produces no finding', () => {
  // jq has its own comment syntax, so the shell lexer cannot help here: that
  // text is inside a single-quoted shell string. A gate shipping an explanation
  // of its own fix inside the jq program it fixed would otherwise report the
  // explanation as the defect — which is exactly what happened, and what made a
  // text assertion in this file claim the fix was unfixed.
  const program = [
    'any(.items[];',
    '  # these three used to be `has("payload") | not` and its siblings,',
    '  # which asks whether three names are absent and nothing more',
    '  .version == $v)',
  ].join('\n');
  assert.deepEqual(programFindings(program), []);
  assert.equal(scanFixture({
    'gate.sh': `jq -e '${program}' "$out"\n`,
  }).findings.length, 0);
});

test('a comment quoting a broken predicate produces no finding', () => {
  // This is the bug the first version had. A `#` comment is removed before the
  // programs are read, so a predicate mentioned in prose is not code.
  const { findings } = scanFixture({
    'gate.sh': [
      '# `all(.[]; .alertType != "X" or .metrics.principalId != $p)` used to be',
      '# the whole predicate. It is quoted here so the next reader knows it.',
      'real_assertion() { jq -e \'.configuredProviders == ["DINGTALK"]\' f; }',
      '',
    ].join('\n'),
  });
  assert.deepEqual(findings, [], 'a predicate quoted in a comment was treated as code');
});

test('a broken predicate in real code is found even when a comment precedes it', () => {
  const { findings, quantifierPrograms } = scanFixture({
    'gate.sh': [
      '# mentions `any(.[]; .a != 1)` in prose, with an apostrophe in it: don\'t',
      'the count.',
      'real_assertion() {',
      "  jq -e 'all(.[]; .documentId != $docId)' \"$out\"",
      '}',
      '',
    ].join('\n'),
  });
  assert.equal(quantifierPrograms, 1, 'the apostrophe in the comment shifted the program boundaries');
  assert.equal(findings.length, 1);
  assert.equal(findings[0].field, '.documentId');
  assert.equal(findings[0].file, 'gate.sh');
  assert.equal(findings[0].line, 4, 'the finding was reported on the wrong line');
});

test('the scanner recurses, so code that moved into scripts/lib/ is still read', () => {
  const { findings } = scanFixture({
    'top.sh': "jq -e 'all(.[]; .a != 1)' x\n",
    'lib/nested/deep.sh': "jq -e 'any(.[]; ((.b // \"\") | contains($t) | not))' x\n",
  });
  assert.deepEqual(findings.map((f) => f.file).sort(), ['lib/nested/deep.sh', 'top.sh']);
});

test('an empty tree produces zero findings and says so', () => {
  // "Found nothing" has to be a number the gate prints, not silence. This is the
  // case that distinguishes a clean tree from a broken parser.
  const { findings, quantifierPrograms, files } = scanFixture({ 'empty.sh': '# nothing here\n' });
  assert.equal(files, 1);
  assert.equal(quantifierPrograms, 0);
  assert.deepEqual(findings, []);
});

test('this repository is clean, and the known-bad shapes are the ones that were fixed', () => {
  // The real tree. If the analyser had lost the ability to find anything, this
  // would pass — so it is paired with the fixture cases above, which is the only
  // reason to believe a clean result means clean and not blind.
  const { findings, quantifierPrograms, files } = scan(scriptsDir);
  assert.ok(files > 50, `only ${files} shell files scanned; the walk stopped early`);
  assert.ok(quantifierPrograms >= 15, `only ${quantifierPrograms} quantifier programs; the extractor stopped matching`);
  assert.deepEqual(
    findings.map((f) => `${f.file}:${f.line}:${f.field}`),
    [],
    'the real tree still carries a negative assertion an absent field satisfies',
  );
});

test('the three fixes are present in the files they were made in', () => {
  // Text-level, deliberately: the point is not to re-derive the predicates but
  // to notice if someone reverts one of them and the analyser silently agrees.
  const lib = fs.readFileSync(path.join(scriptsDir, 'lib/alert-payload.sh'), 'utf8');
  const smoke = fs.readFileSync(path.join(scriptsDir, 'real-collection-purge-e2e-smoke.sh'), 'utf8');
  const gate = fs.readFileSync(path.join(scriptsDir, 'verify-managed-api-principals.sh'), 'utf8');
  // Comments are stripped first, and that is the whole point: the first draft of
  // this case read `has("payload") | not` back out of the gate's own comment
  // describing the fix, and reported the fix as unfixed. A text assertion that
  // cannot tell prose from code is the defect this batch is about.
  // Both layers, because the explanation of this fix lives inside a jq program
  // and the shell lexer cannot see a comment inside its own string.
  const gateCode = stripJqComments(stripShellComments(gate));
  const libCode = stripJqComments(stripShellComments(lib));
  const smokeCode = stripJqComments(stripShellComments(smoke));
  assert.match(libCode, /\.alertType != null/, 'alerts_lack_expiry no longer requires alertType');
  assert.match(libCode, /\.metrics\.principalId != null/, 'alerts_lack_expiry no longer requires principalId');
  assert.match(smokeCode, /\.documentId != null and \.documentId != \$docId/, 'the purge check no longer requires documentId');
  assert.match(smokeCode, /\.chunkText != null/, 'the purge check no longer requires chunkText');
  assert.match(gateCode, /\. \| keys \| sort\) == \[/, 'the receipt leak assertion is no longer a key-set comparison');
  assert.ok(!/has\("payload"\) \| not/.test(gateCode), 'the receipt still asserts absence by name');
});

test('the receipt key list matches the DTO record it is copied from', () => {
  // A hand-typed copy of a machine-generated list rots. It already did once
  // while this batch was being written: a key that the record does not declare
  // was typed into the jq. So the copy is checked against the source.
  const dtoPath = path.join(repoRoot,
    'spring-ai-rag-api/src/main/java/com/springairag/api/dto/AlertNotificationDeliveryResponse.java');
  const dto = fs.readFileSync(dtoPath, 'utf8');
  const body = dto.split('public record AlertNotificationDeliveryResponse(')[1].split(') {')[0];
  const declared = body
    .split('\n')
    .map((l) => l.trim().replace(/[,\s]+$/, '').split(/\s+/).pop())
    .filter((n) => /^\w+$/.test(n))
    .sort();

  const gate = fs.readFileSync(path.join(scriptsDir, 'verify-managed-api-principals.sh'), 'utf8');
  const block = gate.split('. | keys | sort) == [')[1].split(']')[0];
  assert.ok(block, 'the key-set comparison is no longer in the gate');
  const listed = [...block.matchAll(/"(\w+)"/g)].map((m) => m[1]).sort();
  assert.deepEqual(listed, declared,
    'the keys the gate demands do not match the fields AlertNotificationDeliveryResponse declares');
  for (const secret of ['payload', 'leaseToken', 'leaseUntil']) {
    assert.ok(!declared.includes(secret), `${secret} is back in the receipt DTO`);
  }
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
  console.error(`\n${failed}/${cases.length} json-assertions self-test case(s) failed.`);
  process.exitCode = 1;
} else {
  console.log(`\nAll ${cases.length} json-assertions self-test cases passed.`);
}
