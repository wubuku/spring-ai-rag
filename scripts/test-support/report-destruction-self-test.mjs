#!/usr/bin/env node
// Negative tests for scripts/lib/report-destruction-check.mjs.
//
// The rule has two exits, and a rule with two exits has twice the chance to
// accept the wrong thing, so most of the cases below are about the *second*
// shape: a script that restores the reports, or declares the destruction, must
// not be reported — and in particular must not be excused by writing a word
// that merely looks like an admission. Case 6 is the one that existed because
// three scripts in this repository carry a note about
// `scripts/lib/surefire-report.sh`, which is them talking about sharing a
// report *reader*; one of them could have moved that line into its header and
// been declared innocent without changing anything.

import assert from 'node:assert/strict';
import {
  classifyReportDestruction,
  declaresReportDestruction,
  shellHeader,
  stripShellComment,
  unannouncedReportDestructions,
  collectReportDestruction,
} from '../lib/report-destruction-check.mjs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));
const scriptsDir = join(projectRoot, 'scripts');

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

const script = (...body) => `#!/usr/bin/env bash\n${body.join('\n')}\n`;

/** @returns {boolean} whether the gate would report this script. */
const reported = (name, source) => {
  const found = classifyReportDestruction(name, source);
  return found !== null && unannouncedReportDestructions([found]).length === 1;
};

test('reports a script that cleans and never puts a report back', () => {
  assert.equal(
    reported('a.sh', script('run_step "compile" mvn clean compile test-compile', 'echo done')),
    true,
  );
});

test('accepts a script that runs an unscoped test after the clean', () => {
  // The honest fix, and five scripts in this repository already do it. The gate
  // cannot tell anyone to prefer it over declaring the destruction, so it is
  // not the gate's business which one a script chooses.
  assert.equal(
    reported(
      'a.sh',
      script('mvn clean compile test-compile', 'mvn test'),
    ),
    false,
  );
});

test('accepts `mvn clean test` as one command that both destroys and restores', () => {
  // Both goal tests are independent on purpose. An `else if` here would drop
  // the restore side of a single command and report `verify-release.sh`.
  assert.equal(reported('a.sh', script('run_gate "full test" mvn clean test')), false);
});

test('a test pinned to named classes does not restore the reports', () => {
  // `-Dtest=DtoTest` leaves a report directory holding one class, which the
  // reconciler reads as a suite that mostly vanished. That is the loud kind of
  // wrong, but it is still not a restored directory.
  assert.equal(
    reported(
      'a.sh',
      script(
        'mvn clean compile test-compile',
        'mvn -pl spring-ai-rag-api -Dtest=DtoTest -Dsurefire.failIfNoSpecifiedTests=false test',
      ),
    ),
    true,
  );
});

test('a comment that lists mvn clean is not an invocation', () => {
  // `verify-chat-capability.sh` prints exactly this line in its own usage
  // block. Counting it would make the script that documents its steps into a
  // violation for documenting them.
  assert.equal(
    reported(
      'a.sh',
      script(
        '# Runs:',
        '#   mvn clean compile test-compile and full mvn test',
        'echo nothing',
      ),
    ),
    false,
  );
});

test('a mention of the shared report reader is not a declaration', () => {
  // The real case. Three scripts carry this note about sharing one reader; if
  // it counted as an admission they would pass without saying anything about
  // their reports going away.
  const source = script(
    '# One-click acceptance for something.',
    '#',
    '# Batch 894: one reader for the surefire report, so the `tests` count comes from',
    '# the <testcase> elements. See scripts/lib/surefire-report.sh for the rule.',
    'mvn clean compile test-compile',
  );
  assert.equal(declaresReportDestruction(shellHeader(source.split('\n'))), false);
  assert.equal(reported('a.sh', source), true);
});

test('a header that says the reports are deleted is a declaration', () => {
  const source = script(
    '# One-click acceptance for something.',
    '# This script runs `mvn clean compile test-compile`, which deletes',
    '# target/surefire-reports. Run `mvn test` before the gate chain.',
    'mvn clean compile test-compile',
  );
  assert.equal(declaresReportDestruction(shellHeader(source.split('\n'))), true);
  assert.equal(reported('a.sh', source), false);
});

test('the English spelling of the declaration counts too', () => {
  const source = script(
    '# One-click acceptance.',
    '# Its compile gate is `mvn clean`, which removes the surefire reports.',
    'mvn clean compile test-compile',
  );
  assert.equal(reported('a.sh', source), false);
});

test('a `#` inside quotes is data, not a comment', () => {
  assert.equal(stripShellComment(`echo "#1 ranked"`), `echo "#1 ranked"`);
  assert.equal(stripShellComment(`mvn clean  # and more`), 'mvn clean  ');
  assert.equal(reported('a.sh', script(`echo "#1" && mvn clean compile test-compile`)), true);
});

test('a goal terminated by ; or ) is still a goal', () => {
  // `{ mvn test; }` is how a wrapper function spells it, and that shape is
  // exactly what makes a script self-healing. A rule that only recognises
  // whitespace-terminated goals reports those scripts as violators.
  assert.equal(
    reported(
      'a.sh',
      script('maven_compile() { mvn clean compile test-compile; }', 'maven_test() { mvn test; }'),
    ),
    false,
  );
});

test('the header stops at the first line of code', () => {
  // A note halfway down the file is not a header, and the three scripts with a
  // mid-file `surefire-report.sh` note depend on this.
  const source = script(
    '# One-click acceptance.',
    'set -euo pipefail',
    '# later: we source scripts/lib/surefire-report.sh',
  );
  assert.equal(shellHeader(source.split('\n')).includes('surefire-report.sh'), false);
});

test('the real scripts directory reports nothing', () => {
  const classified = collectReportDestruction(scriptsDir);
  assert.ok(classified.length >= 10, `expected the real inventory, found ${classified.length}`);
  // The measurement the rule was calibrated on. If a future script stops
  // restoring, this number moves and the assertion has to move with it.
  assert.equal(
    classified.filter(entry => entry.restores).length,
    5,
    'scripts that restore the reports by re-running an unscoped test',
  );
  assert.deepEqual(unannouncedReportDestructions(classified), []);
});

let failures = 0;
for (const { title, fn } of cases) {
  try {
    fn();
    console.log(`ok   ${title}`);
  } catch (error) {
    failures += 1;
    console.error(`FAIL ${title}`);
    console.error(`     ${error.message}`);
  }
}

if (failures > 0) {
  console.error(`\n${failures}/${cases.length} report-destruction self-test case(s) failed.`);
  process.exit(1);
}
console.log(`\nAll ${cases.length} report-destruction self-test cases passed.`);
