#!/usr/bin/env node
// Self-test for scripts/verify-tracked-env-files.mjs.
//
// The first version of this gate looked for credential-shaped values anywhere in
// a tracked file and reported over a hundred findings, almost all of them normal
// code: SQL columns called `lease_token`, a frontend variable called
// `confirmationToken`, a testcontainer password in CI, fake keys in the tests
// that exist to prove masking works, and example values in documentation. A gate
// that cries wolf like that gets switched off, which is worse than never having
// written it — the same lesson the `?? 0` census taught, in the opposite
// direction.
//
// So most of what follows pins *non*-findings. The end-to-end cases run the real
// script as a child process, because the exit code is the only thing a runner
// observes, and this repository has already had to delete four gates that could
// not fail.

import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import {
  findTrackedEnvProblems,
  isDotEnvFile,
  isPlaceholder,
  renderProblem,
  TEMPLATE,
  VIOLATION_KINDS,
} from '../verify-tracked-env-files.mjs';

// `import.meta.url` is a *file*: `join(here, '..', '..')` resolves to `scripts/`,
// not the repository root. The first draft used it that way and the probe below was
// created and staged inside `scripts/` — the test still passed, because `git ls-files`
// lists any path, which is exactly why a wrong-but-passing test can sit here a long
// time without anyone noticing. The sibling self-test hit the same mistake as a hard
// ENOENT.
const REPO = fileURLToPath(new URL('../..', import.meta.url));
const GATE = join(REPO, 'scripts', 'verify-tracked-env-files.mjs');

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

/** Runs the pure check over a fixture tree. */
const check = (files) => findTrackedEnvProblems(
  Object.keys(files),
  (path) => files[path],
);

test('the file that actually happened is reported', () => {
  // `.env.deepspeed` was tracked, held real provider keys, and reached a public
  // main. This is the case the gate exists for, so it is the first one.
  const problems = check({ '.env.deepspeed': 'RAG_EMBEDDING_API_KEY=sk-abc123\n' });
  assert.ok(problems.length > 0, 'a tracked .env.deepspeed must be reported');
  assert.equal(problems[0].kind, VIOLATION_KINDS.TRACKED_ENV_FILE);
  assert.equal(problems[0].file, '.env.deepspeed');
});

test('the template is tracked on purpose and is never reported', () => {
  assert.deepEqual(check({ [TEMPLATE]: 'RAG_EMBEDDING_API_KEY=your-key\n' }), []);
});

test('a bare .env is reported too', () => {
  const problems = check({ '.env': 'RAG_ROOT_API_KEY=rk_live_abc\n' });
  assert.equal(problems[0].kind, VIOLATION_KINDS.TRACKED_ENV_FILE);
});

test('a nested dot-env path is recognised by its file name', () => {
  assert.equal(isDotEnvFile('services/api/.env'), true);
  assert.equal(isDotEnvFile('src/.env.local'), true);
  assert.equal(isDotEnvFile('.env.example'), true);
  // And the things that are not dot-env files, however env-shaped they look.
  assert.equal(isDotEnvFile('src/api/client.ts'), false);
  assert.equal(isDotEnvFile('docs/configuration.md'), false);
  assert.equal(isDotEnvFile('docker/docker-compose.yml'), false);
});

test('the finding names the variable and never the value', () => {
  // Deliberately *not* shaped like a provider key. What is being asserted is that
  // the gate withholds a value, and that claim does not need a realistic secret to
  // test it — while a realistic-looking literal in a committed file is a hazard the
  // repository's own added-line secret scan is right to stop on, and then the test
  // is unrunnable and the hazard is still in the file.
  const secret = 'a-real-value-that-must-never-be-printed';
  const problems = check({ '.env.production': `OPENAI_API_KEY=${secret}\n` });
  // Assert on what `renderProblem` emits, because that is the text a CI log keeps.
  // Asserting on the problem object instead would pass while the rendered line went
  // on printing the file without its line number — which is how "and the place to
  // go" quietly stopped being true.
  const rendered = problems.map(renderProblem).join('\n');
  assert.ok(!rendered.includes(secret), 'a gate must not copy the secret it found');
  assert.ok(!rendered.includes('real-value-that-must'), 'nor any prefix of it');
  assert.ok(rendered.includes('OPENAI_API_KEY'), 'but it must name the variable');
  assert.ok(rendered.includes('.env.production:1'), 'and the place to go');
});

test('a placeholder in a tracked env file is named but not called a credential', () => {
  for (const value of ['', '${OPENAI_API_KEY}', 'your-api-key', 'changeme', '<token>', 'xxx']) {
    assert.equal(isPlaceholder(value), true, `${JSON.stringify(value)} should read as a placeholder`);
  }
  assert.equal(isPlaceholder('a-real-looking-value'), false);

  // A tracked env file still fails on the file rule even when every value in it
  // is a placeholder — the file itself is the finding.
  const problems = check({ '.env.staging': 'OPENAI_API_KEY=your-api-key\n' });
  assert.equal(problems[0].kind, VIOLATION_KINDS.TRACKED_ENV_FILE);
  assert.equal(
    problems.filter(p => p.kind === VIOLATION_KINDS.CREDENTIAL_IN_TRACKED_ENV_FILE).length,
    0,
  );
});

test('ordinary code with token-shaped names is not reported', () => {
  // The hundred false alarms, in one fixture: SQL columns, a frontend variable,
  // a testcontainer password, a masking fixture, a documented example.
  const problems = check({
    'core/src/main/java/Repo.java': 'String lease_token = rs.getString(1);\n',
    'webui/src/api/collections.ts': 'const confirmationToken = window.prompt("x");\n',
    '.github/workflows/ci.yml': 'POSTGRES_PASSWORD: testcontainer\n',
    'core/src/test/java/MaskingTest.java': 'String key = "sk-fake-000000000000";\n',
    'docs/getting-started.md': 'RAG_API_KEY=rk_test_example\n',
  });
  assert.deepEqual(problems, [], 'none of those is a leaked credential');
});

test('the declared limits are limits, pinned', () => {
  // The header says this gate governs *tracked dot-env files* and nothing else.
  // Two ways it does not help, asserted here so that nobody later widens the rule
  // by accident and nobody later believes it covers these:
  //
  //   1. Credentials in a file that is not dot-env — a PEM key, a `credentials.yml`.
  //   2. Anything already in history. `git ls-files` is a statement about the index,
  //      and Batch 935 is the proof: the values in `.env.deepspeed` were already on
  //      a public main before this gate existed, and it still reported a clean tree.
  //
  // Untracking is not removing, and removing is not rotating. Only the key's owner
  // can do that part.
  const problems = check({
    'config/credentials.yml': 'password: hunter2\n',
    'deploy/id_rsa': '-----BEGIN OPENSSH PRIVATE KEY-----\n',
    'src/main/resources/application-local.yml': 'password: hunter2\n',
  });
  assert.deepEqual(problems, [], 'out of scope by the header, and must stay out of scope');
});

test('the gate runs and reports the real repository clean', () => {
  const run = spawnSync(process.execPath, [GATE], { cwd: REPO, encoding: 'utf8' });
  assert.equal(run.status, 0, `gate exited ${run.status}:\n${run.stdout}${run.stderr}`);
  assert.match(run.stdout, /Tracked-env check passed/);
});

test('the gate exits non-zero when a dot-env file is tracked', () => {
  // The real script, driven through the actual `git ls-files` path; everything
  // above exercises the pure function, and only the exit code is what a runner
  // observes.
  //
  // `git add -f` is not incidental: `.env*` is in `.gitignore` since Batch 935, so
  // a plain `git add` of a dot-env file is *supposed* to fail. This case therefore
  // pins the bypass — `-f` is exactly how someone re-introduces the defect after
  // the ignore rule was added, and it is the case a rule on `.gitignore` contents
  // could not see and a rule on tracked files can.
  const probe = '.env.gate-self-test-probe';
  const path = join(REPO, probe);
  let staged = false;
  try {
    writeFileSync(path, 'OPENAI_API_KEY=probe-value-not-a-placeholder\n');
    const added = spawnSync('git', ['add', '--', probe], { cwd: REPO, encoding: 'utf8' });
    assert.notEqual(added.status, 0, 'a plain `git add` of a dot-env file must be ignored');

    execFileSync('git', ['add', '-f', '--', probe], { cwd: REPO, encoding: 'utf8' });
    staged = true;
    const run = spawnSync(process.execPath, [GATE], { cwd: REPO, encoding: 'utf8' });
    assert.equal(run.status, 1, 'the gate must fail while a dot-env file is tracked');
    assert.ok(!run.stderr.includes('probe-value-not-a-placeholder'), 'and still must not print the value');
    assert.match(run.stderr, /tracked-env-file/);
  } finally {
    if (staged) {
      execFileSync('git', ['rm', '--cached', '--quiet', '--', probe], { cwd: REPO, encoding: 'utf8' });
    }
    rmSync(path, { force: true });
  }

  const after = spawnSync(process.execPath, [GATE], { cwd: REPO, encoding: 'utf8' });
  assert.equal(after.status, 0, 'and pass again once it is untracked');
});

let failed = 0;
for (const { title, fn } of cases) {
  try {
    fn();
    process.stdout.write(`  ok  ${title}\n`);
  } catch (error) {
    failed += 1;
    process.stdout.write(`FAIL  ${title}\n`);
    process.stdout.write(`      ${error.message.split('\n').join('\n      ')}\n`);
  }
}
process.stdout.write(
  `Tracked-env self-test: ${cases.length - failed}/${cases.length} case(s) passed.\n`,
);
process.exit(failed === 0 ? 0 : 1);
