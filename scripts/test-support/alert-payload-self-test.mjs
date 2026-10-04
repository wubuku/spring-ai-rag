#!/usr/bin/env node
// Self-test for scripts/lib/alert-payload.sh.
//
// The two expiry-alert predicates were bare `jq -e` one-liners whose every
// non-zero exit meant "not yet", which is right for a poll and wrong for a
// reader. Measured on Batch 895:
//
//   - `all(.[]; .alertType != "API_PRINCIPAL_EXPIRY" or .metrics.principalId != $p)`
//     returns TRUE when the field is renamed, because a missing field is `null`
//     and `null != $p`. So a gate whose job is to prove an alert is *not*
//     firing reported a firing, attributed alert as correctly absent.
//   - a payload that is no longer an array makes `jq` exit 5, which the poll
//     could not tell from exit 1, so it burned its whole timeout and then named
//     the alert instead of the reader.
//
// These run the real shell functions against payloads written to disk, because
// the thing under test is a shell function: a JavaScript restatement would pass
// while the shell one rotted.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const LIB = join(repoRoot, 'scripts/lib/alert-payload.sh');

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

const dir = mkdtempSync(join(tmpdir(), 'alert-payload-self-test-'));
let n = 0;

/** Write a payload and run one function against it; returns exit status. */
function call(fn, args = []) {
  const file = join(dir, `p${(n += 1)}.json`);
  writeFileSync(file, typeof args[0] === 'string' && args[0].trimStart().startsWith(('[', '{'))
    ? args[0]
    : CURRENT);
  return { status: callOn(file, fn, args.slice(1)) };
}
let CURRENT = '[]';
function callOn(file, fn, args) {
  try {
    execFileSync('bash', ['-c', `source "${LIB}"; ${fn} "${file}" ${args.map((a) => `"${a}"`).join(' ')}`], { encoding: 'utf8' });
    return 0;
  } catch (e) {
    return e.status ?? 1;
  }
}
/** Run a function that prints a reason; returns { status, reason }. */
function judge(payload) {
  const file = join(dir, `j${(n += 1)}.json`);
  writeFileSync(file, payload);
  try {
    const reason = execFileSync('bash', ['-c', `source "${LIB}"; alerts_response_judgable "${file}"`], { encoding: 'utf8' });
    return { status: 0, reason: reason.trim() };
  } catch (e) {
    return { status: e.status ?? 1, reason: String(e.stdout ?? '').trim() };
  }
}
const on = (payload, fn, ...args) => {
  const file = join(dir, `x${(n += 1)}.json`);
  writeFileSync(file, payload);
  return callOn(file, fn, args);
};

const EXPIRY = (over = {}) => JSON.stringify([{
  alertType: 'API_PRINCIPAL_EXPIRY',
  conditionState: 'ACTIVE',
  metrics: { principalId: 'P1' },
  ...over,
}]);

test('the library is sourceable and every function exists', () => {
  // The positive control. A library that defined nothing would make every other
  // case in this file pass for the same wrong reason.
  const out = execFileSync('bash', ['-c',
    `source "${LIB}"; for f in alerts_payload_readable alerts_have_unattributable_expiry alerts_response_judgable alerts_match_expiry alerts_lack_expiry; do declare -F "$f" >/dev/null || exit 1; done; echo ok`,
  ], { encoding: 'utf8' });
  assert.equal(out.trim(), 'ok');
});

test('a well-formed list is readable and has every principal named', () => {
  assert.equal(judge(EXPIRY()).status, 0);
  assert.equal(judge('[]').status, 0);
});

test('a wrapper object is refused, and the reason names the reader', () => {
  const { status, reason } = judge('{"alerts":[]}');
  assert.equal(status, 1);
  assert.match(reason, /not a JSON array/);
  // The reason has to survive into the gate's own output, or the operator is
  // left reading a message about the alert.
  assert.match(reason, /first 160 bytes/);
});

test('a null or non-list body is refused rather than treated as no alerts', () => {
  for (const payload of ['null', '"oops"', '42']) {
    assert.equal(judge(payload).status, 1, `payload ${payload} was accepted`);
  }
});

test('a renamed principal field is refused instead of read as absent', () => {
  // The regression. This alert is present, firing and attributed to P1; the
  // predicate under test used to agree that it was absent.
  const payload = JSON.stringify([{
    alertType: 'API_PRINCIPAL_EXPIRY',
    conditionState: 'ACTIVE',
    metrics: { principal_id: 'P1' },
  }]);
  const { status, reason } = judge(payload);
  assert.equal(status, 1);
  assert.match(reason, /no readable metrics\.principalId/);
});

test('an expiry alert with no metrics at all is refused', () => {
  assert.equal(judge('[{"alertType":"API_PRINCIPAL_EXPIRY","conditionState":"ACTIVE"}]').status, 1);
});

test('a non-expiry alert needs no principal and is not refused for lacking one', () => {
  // The guard is scoped on purpose. Other alert kinds legitimately carry no
  // principal, so demanding it of everything would fail on correct data.
  const { status } = judge('[{"alertType":"DOCUMENT_LIFECYCLE","conditionState":"ACTIVE"}]');
  assert.equal(status, 0);
});

test('the active poll finds a matching alert and rejects a wrong phase', () => {
  assert.equal(on(EXPIRY(), 'alerts_match_expiry', 'P1', 'ACTIVE'), 0);
  assert.notEqual(on(EXPIRY(), 'alerts_match_expiry', 'P1', 'RESOLVED'), 0);
  assert.notEqual(on(EXPIRY(), 'alerts_match_expiry', 'P2', 'ACTIVE'), 0);
});

test('the absent poll is satisfied by an empty list — that is the real answer', () => {
  // Vacuous truth is correct here, and the case exists so a later reader does
  // not "fix" it into a failure. The unreadable case is a separate question,
  // answered by alerts_response_judgable.
  assert.equal(on('[]', 'alerts_lack_expiry', 'P1'), 0);
  assert.equal(on(EXPIRY({ metrics: { principalId: 'P2' } }), 'alerts_lack_expiry', 'P1'), 0);
  assert.notEqual(on(EXPIRY(), 'alerts_lack_expiry', 'P1'), 0);
});

test('the absent predicate alone still fails open, which is why the gate guards first', () => {
  // Pinned deliberately: this asserts the *hazard* still exists at the predicate
  // level, so the guard in the poll loop stays load-bearing. If a future jq or a
  // future payload shape ever made this return non-zero, the guard would have
  // become redundant and this case would be the thing that says so.
  const renamed = JSON.stringify([{
    alertType: 'API_PRINCIPAL_EXPIRY', conditionState: 'ACTIVE', metrics: { principal_id: 'P1' },
  }]);
  assert.equal(on(renamed, 'alerts_lack_expiry', 'P1'), 0);
});

/** Return the body of a shell function, from `name() {` to its closing `}` in column 0. */
function functionBody(src, name) {
  const lines = src.split('\n');
  const start = lines.findIndex((l) => l.startsWith(`${name}() {`));
  assert.notEqual(start, -1, `${name}() is not defined in the gate`);
  const end = lines.indexOf('}', start);
  assert.notEqual(end, -1, `${name}() has no closing brace in column 0`);
  return lines.slice(start, end + 1).join('\n');
}

test('the gate calls the guards, and keeps no bare jq predicate of its own', () => {
  // The wiring half, and the reason this is not a library nobody uses.
  //
  // Scoped to the two poll loops, deliberately. Two earlier versions of this
  // case matched the whole gate — once on `jq -e --arg principal`, once on any
  // `any(.[]`/`all(.[]` — and both failed on the gate's *other* jq readers:
  // the session list at 1480/1606 and the document list at 1904/1905. That is a
  // real observation about the gate and it is in the ledger, not this batch's to
  // fix. Reading the function bodies is what makes the claim exact.
  const src = readFileSync(join(repoRoot, 'scripts/verify-managed-api-principals.sh'), 'utf8');
  assert.ok(src.includes('lib/alert-payload.sh'), 'the gate does not source the library');
  assert.equal(
    (src.match(/alerts_response_judgable/g) ?? []).length,
    2,
    'both poll loops must check the response is judgeable',
  );
  assert.ok(
    src.includes('Cannot judge the active-alert response'),
    'the refusal message is not wired into the gate output',
  );

  for (const [name, predicate] of [
    ['poll_active_expiry_alert', 'alerts_match_expiry'],
    ['poll_absent_expiry_alert', 'alerts_lack_expiry'],
  ]) {
    const body = functionBody(src, name);
    // The claim is "this function does not read the alert list itself". "No jq
    // call at all" already says that; a second scan for field names would also
    // fire on the explanatory comments inside the function, which is how an
    // earlier draft of this case reported a leftover that was not one.
    assert.ok(!/\bjq\b/.test(body), `${name} still reads the response with its own jq call`);
    assert.ok(body.includes(predicate), `${name} does not call ${predicate}`);
    assert.ok(
      body.includes('alerts_response_judgable'),
      `${name} judges the answer before reading it`,
    );
    // Order is the load-bearing part: the guard has to come before the predicate
    // that it is guarding, or the predicate can answer first.
    assert.ok(
      body.indexOf('alerts_response_judgable') < body.indexOf(predicate),
      `${name} reads the response before checking that it is readable`,
    );
  }
});

test('the expiry readers this batch left alone demand exactly one match', () => {
  // Why four of the five expiry readers in this gate were not converted. The
  // other three all require `($alerts | length) == 1`, so a renamed
  // `metrics.principalId` yields zero matches and they fail — they cannot
  // report a firing alert as absent, which is the defect being fixed. Pinned so
  // that a later reader who rewrites one of them into a lenient predicate sees
  // this case go red.
  const src = readFileSync(join(repoRoot, 'scripts/verify-managed-api-principals.sh'), 'utf8');
  let matched = 0;
  for (const block of src.split(/jq -e?\s/).slice(1)) {
    // The program is the text between the single quotes, not the `--arg` flags
    // in front of them — an earlier draft sliced the other way and policed an
    // empty string, which is why it reported "0 readers" on a gate that has three.
    const open = block.indexOf("'");
    if (open === -1) continue;
    const close = block.indexOf("'", open + 1);
    if (close === -1) continue;
    const program = block.slice(open + 1, close);
    if (!/\[\.\[\] \| select\(/.test(program)) continue;
    if (!program.includes('.metrics.principalId')) continue;
    matched += 1;
    assert.match(
      program,
      /\(\$alerts \| length\) == 1/,
      `an expiry reader no longer demands exactly one match:\n${program.trim()}\n`
        + 'That is the shape that fails open; convert it to scripts/lib/alert-payload.sh instead.',
    );
  }
  // Without this the case would pass on an empty scan — the positive control
  // that the split above still finds the readers it is supposed to police.
  assert.ok(matched >= 3, `only ${matched} expiry reader(s) left to police; the scan stopped matching`);
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
rmSync(dir, { recursive: true, force: true });

if (failed > 0) {
  console.error(`\n${failed}/${cases.length} alert-payload self-test case(s) failed.`);
  process.exitCode = 1;
} else {
  console.log(`\nAll ${cases.length} alert-payload self-test cases passed.`);
}
