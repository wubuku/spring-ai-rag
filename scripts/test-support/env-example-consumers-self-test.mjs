#!/usr/bin/env node
// Self-test for scripts/verify-env-example-consumers.mjs.
//
// The gate exists because `ad026765` added six variables to `.env.example` under
// the message "update .env.example with all current configuration variables", and
// no code has ever read them. Two thirds of what follows pins *non*-findings,
// because both halves of the census that found them were wrong in opposite
// directions before they were right:
//
//   - the first probe counted a local `.env.deepspeed` — a copy of the template —
//     as a consumer, so all 44 variables looked used and the gate would have
//     reported a clean template;
//   - the second probe decided `SPRING_DATASOURCE_DRIVER_CLASS_NAME` was dead
//     because nothing spells that name, which is not the same as nothing reading
//     it: `spring.datasource.driver-class-name` is set in four YAML files and is
//     a Spring Boot built-in. That variable has to survive the gate.

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import {
  findUnconsumedVariables,
  isDocumentation,
  isGateFixture,
  isLocalEnvFile,
  MIN_DECLARED,
  parseTemplate,
  referencesVariable,
  TEMPLATE,
} from '../verify-env-example-consumers.mjs';

// `import.meta.url` is a *file*, so `join(import.meta.url, '..', '..')` resolves to
// `scripts/`, not the repository root. The first draft of this file got that wrong
// and looked for the template one directory too low — which is how a self-test for a
// gate about tracked files ends up reporting ENOENT instead of a finding.
const REPO = fileURLToPath(new URL('../..', import.meta.url));
const GATE = join(REPO, 'scripts', 'verify-env-example-consumers.mjs');
const TEMPLATE_PATH = join(REPO, TEMPLATE);

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

/** Runs the pure check over a fixture template and a fixture corpus. */
const check = (templateText, corpus) => findUnconsumedVariables(templateText, corpus);

test('the six variables that actually happened are reported', () => {
  // `ad026765` wrote these under "all current configuration variables". The block
  // headers claimed audio transcription and multimodal chat; neither exists.
  const templateText = [
    'POSTGRES_PASSWORD=123456',
    'TRANSCRIPTION_BASE_URL=https://open.bigmodel.cn/api/paas',
    'TRANSCRIPTION_API_KEY=your-transcription-api-key',
    'TRANSCRIPTION_MODEL=glm-asr-2512',
    'VISION_BASE_URL=https://ark.cn-beijing.volces.com/api/v3',
    'VISION_API_KEY=your-ark-vision-api-key',
    'VISION_MODEL=doubao-1-5-vision-pro-32k-250115',
    '',
  ].join('\n');
  const names = check(templateText, [
    { path: 'core/src/main/resources/application.yml', text: 'password: ${POSTGRES_PASSWORD}' },
  ]).map((v) => v.name);
  assert.deepEqual(names, [
    'TRANSCRIPTION_BASE_URL',
    'TRANSCRIPTION_API_KEY',
    'TRANSCRIPTION_MODEL',
    'VISION_BASE_URL',
    'VISION_API_KEY',
    'VISION_MODEL',
  ]);
});

test('a document that mentions a variable is not a consumer', () => {
  // The negative assertion is the load-bearing half. A template variable named in
  // `docs/configuration.md` and in nothing else is still unread, and the gate that
  // accepts documentation as evidence reports the exact defect it was written for
  // as clean.
  const templateText = 'DEAD_KNOB=1\nLIVE_KNOB=1\n';
  const corpus = [
    { path: 'docs/configuration.md', text: 'Set DEAD_KNOB to tune it.' },
    { path: 'docs/configuration-zh-CN.md', text: 'DEAD_KNOB 的说明' },
    { path: 'README.md', text: 'DEAD_KNOB appears in the readme too' },
    { path: 'core/src/main/java/App.java', text: 'System.getenv("LIVE_KNOB");' },
  ];
  assert.deepEqual(check(templateText, corpus).map((v) => v.name), ['DEAD_KNOB']);
});

test('another dot-env file is a copy of the template, not a reader of it', () => {
  // This is the probe that got the first census wrong in the dangerous direction:
  // `.env.deepspeed` was on disk holding the template's own placeholder lines, and
  // counting those as consumers made all 44 variables look used. The real gate reads
  // `git ls-files`, so untracked scratch never reaches it — this pins the *rule*,
  // which is what the census actually got wrong.
  const templateText = 'DEAD_KNOB=1\n';
  const corpus = [
    { path: '.env', text: 'DEAD_KNOB=1\n' },
    { path: '.env.deepspeed', text: 'DEAD_KNOB=your-key\n' },
  ];
  assert.deepEqual(check(templateText, corpus).map((v) => v.name), ['DEAD_KNOB']);
});

test('the local-env rule is by name, and names like `dev.env` are outside it', () => {
  // `.verification/…/dev.env` really does exist in this repository and really does
  // hold template values. It never reaches the gate — `git ls-files` does not list
  // it, because `.verification/` is ignored — so widening the rule to cover it would
  // be guessing. Pinned here so that doing it later is a deliberate edit.
  assert.equal(isLocalEnvFile('dev.env'), false);
  assert.equal(isLocalEnvFile('.env.deepspeed'), true);
  assert.equal(isLocalEnvFile('.env'), true);
  assert.equal(isLocalEnvFile(TEMPLATE), false, 'the template is not a local copy of itself');
  assert.equal(isLocalEnvFile('docker/.env.test'), true);
});

test('the Spring Boot built-in that has to survive is not reported', () => {
  // Nothing in this repository spells `SPRING_DATASOURCE_DRIVER_CLASS_NAME`, and it
  // is still correct in the template: `spring.datasource.driver-class-name` is set
  // in four YAML files and Spring Boot binds it if the variable is exported. A gate
  // that treats "I could not find the name" as "nothing reads it" deletes a real
  // knob — and the fix is to keep the knob and refuse the inference.
  const templateText = '# SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver\n# VISION_API_KEY=x\n';
  assert.deepEqual(check(templateText, []), [], 'commented lines are a different promise');
});

test('a commented line is not a declaration, an active one is', () => {
  const parsed = parseTemplate([
    '# COMMENTED_KNOB=1',
    '#COMMENTED_TIGHT=1',
    '#   COMMENTED_INDENTED=1',
    'ACTIVE_KNOB=1',
    'export EXPORTED_KNOB=1',
    '# ═══ a section header with no assignment',
    'not_an_assignment',
    '',
  ].join('\n'));
  assert.deepEqual(
    parsed.map((v) => [v.name, v.active, v.line]),
    [
      ['COMMENTED_KNOB', false, 1],
      ['COMMENTED_TIGHT', false, 2],
      ['COMMENTED_INDENTED', false, 3],
      ['ACTIVE_KNOB', true, 4],
      ['EXPORTED_KNOB', true, 5],
    ],
  );
});

test('a variable is matched on a word boundary, not a substring', () => {
  // `VISION_API_KEY` is a substring of `DOCUMENT_REVISION_API_KEY`, and `APP_PORT`
  // of `XAPP_PORT`. Without the boundary a real consumer is invisible and an
  // unrelated line satisfies the variable it has nothing to do with — which is how
  // a text search measures the wrong thing and reports a clean result.
  assert.equal(referencesVariable('REVISION_API_KEY: value', 'VISION_API_KEY'), false);
  assert.equal(referencesVariable('XAPP_PORT=1', 'APP_PORT'), false);
  assert.equal(referencesVariable('prefix_MY_KNOB=1', 'MY_KNOB'), false);
  assert.equal(referencesVariable('MY_KNOB_SUFFIX=1', 'MY_KNOB'), false);
  assert.equal(referencesVariable('MY_KNOB=1', 'MY_KNOB'), true);
});

test('the classification helpers say what they are for', () => {
  assert.equal(isLocalEnvFile('.env.deepspeed'), true);
  assert.equal(isLocalEnvFile('.env'), true);
  assert.equal(isLocalEnvFile(TEMPLATE), false, 'the template is not a local copy of itself');
  assert.equal(isLocalEnvFile('docker/.env.test'), true);

  assert.equal(isDocumentation('docs/configuration.md'), true);
  assert.equal(isDocumentation('docs/drafts/plan.md'), true);
  assert.equal(isDocumentation('README.md'), true);
  assert.equal(isDocumentation('core/README.md'), true);
  assert.equal(isDocumentation('CLAUDE.md'), true);
  assert.equal(isDocumentation('AGENTS.md'), true);
  assert.equal(isDocumentation('scripts/verify-release.sh'), false);
  assert.equal(isDocumentation('spring-ai-rag-core/src/main/resources/application.yml'), false);
});

test("this gate's own self-test is not a consumer of anything", () => {
  // The one that made this gate work. Its self-test has to *spell out* the variable
  // names it checks, its self-test is tracked, and a tracked file that mentions a
  // name is a consumer — so the first version reported a clean template while
  // `VISION_API_KEY` sat in it unread. The self-test passed 11/11 the whole time.
  // Only the reverse probe on the real template caught it.
  const templateText = 'DEAD_KNOB=1\nLIVE_KNOB=1\n';
  const corpus = [
    { path: 'scripts/test-support/env-example-consumers-self-test.mjs', text: 'DEAD_KNOB=1' },
    { path: 'spring-ai-rag-webui/scripts/__tests__/i18n-keys.test.mjs', text: 'DEAD_KNOB' },
    { path: 'core/src/main/resources/application.yml', text: '${LIVE_KNOB}' },
  ];
  assert.deepEqual(check(templateText, corpus).map((v) => v.name), ['DEAD_KNOB']);

  assert.equal(isGateFixture('scripts/test-support/x-self-test.mjs'), true);
  assert.equal(isGateFixture('spring-ai-rag-webui/scripts/__tests__/y.test.mjs'), true);
  assert.equal(isGateFixture('scripts/verify-release.sh'), false);
  assert.equal(isGateFixture('scripts/start-real-e2e-server.sh'), false,
    'a runnable script that reads a key is a consumer, even though it lives in scripts/');
});

test('the gate runs and reports the real repository clean', () => {
  const run = spawnSync(process.execPath, [GATE], { cwd: REPO, encoding: 'utf8' });
  assert.equal(run.status, 0, `gate exited ${run.status}:\n${run.stdout}${run.stderr}`);
  assert.match(run.stdout, /Env-example consumer check passed/);
});

test('the gate turns red on the real template when a variable stops being read', () => {
  // The reverse direction, on the real file: the six variables above are gone, so
  // the only way to learn whether this gate can still reject is to put one back.
  // Restored by content, not by `git checkout`, so a concurrent change to
  // `.env.example` cannot be discarded by this test.
  const original = readFileSync(TEMPLATE_PATH, 'utf8');
  assert.ok(!original.includes('VISION_API_KEY='), 'the fixture variable must not already exist');
  try {
    writeFileSync(TEMPLATE_PATH, `${original}\nVISION_API_KEY=your-ark-vision-api-key\n`);
    const run = spawnSync(process.execPath, [GATE], { cwd: REPO, encoding: 'utf8' });
    assert.equal(run.status, 1, 'a variable nothing reads must fail the gate');
    assert.match(run.stderr, /VISION_API_KEY=your-ark-vision-api-key|declares 1 variable/);
    assert.ok(run.stderr.includes('VISION_API_KEY'), 'and name it');
    assert.ok(run.stderr.includes('your-ark-vision-api-key') === false, 'template values are not secrets, but the finding names the line');
  } finally {
    writeFileSync(TEMPLATE_PATH, original);
  }
  const after = spawnSync(process.execPath, [GATE], { cwd: REPO, encoding: 'utf8' });
  assert.equal(after.status, 0, 'and pass again once the template is restored');
});

test('the floor assertion trips before the rule gets a chance to be wrong', () => {
  // A parser that quietly stopped reading would find nothing unconsumed and report
  // a clean template. The floor is what turns that into a failure; asserting on it
  // here means a future edit cannot quietly lower it.
  assert.ok(MIN_DECLARED > 0);
  const text = readFileSync(TEMPLATE_PATH, 'utf8');
  const declared = parseTemplate(text).filter((v) => v.active);
  assert.ok(
    declared.length >= MIN_DECLARED,
    `template has ${declared.length} declared variables, floor is ${MIN_DECLARED}`,
  );
  // A template truncated to nothing is exactly the state the floor exists for.
  assert.deepEqual(check('', []), []);
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
  `Env-example consumer self-test: ${cases.length - failed}/${cases.length} case(s) passed.\n`,
);
process.exit(failed === 0 ? 0 : 1);