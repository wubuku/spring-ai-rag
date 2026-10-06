#!/usr/bin/env node
// Self-test for scripts/verify-helm-helper-liveness.mjs.
//
// `_helpers.tpl` carried three helpers no template ever included, and they described
// a strategy that is not the one in force: `jvm-heap` computed the heap from
// `jvm.heapPercent` while `deployment.yaml` reads `jvm.maxHeap` directly, and
// `spring-profile` read a top-level `.Values.springProfile` that `values.yaml` never
// declares while the profile actually travels as the `SPRING_PROFILES_ACTIVE`
// environment variable from `secret.yaml`.
//
// The rule has no allowlist, and that is the point worth pinning: unlike "a mention is
// a use" — which needed three exclusions in Batch 935 because a mention and a use are
// different things — a Helm helper's visibility is decided by the template language
// itself, with nothing in between.

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { stripConfigComments } from '../lib/java-source.mjs';
import {
  findUnreachableHelpers,
  MIN_HELPERS,
  parseDefines,
  parseIncludes,
} from '../verify-helm-helper-liveness.mjs';

// `import.meta.url` is a file: `join(here, '..', '..')` lands in `scripts/`.
const REPO = fileURLToPath(new URL('../..', import.meta.url));
const GATE = join(REPO, 'scripts', 'verify-helm-helper-liveness.mjs');

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

/** The chart's templates, as the gate reads them. */
const templateFiles = () => readdirSync(join(REPO, 'k8s/templates'))
  .filter((name) => /\.(tpl|yaml|yml|txt)$/u.test(name))
  .map((name) => ({
    path: `k8s/templates/${name}`,
    text: readFileSync(join(REPO, 'k8s/templates', name), 'utf8'),
  }));

test('the two that actually happened are reported', () => {
  // `jvm-raw` is not in this list on purpose: it was included by `jvm-heap`, which was
  // itself dead. Reachability is not the same as liveness, and the gate asks the
  // question it can decide — deleting the three together is what makes the dead pair
  // go, and the reverse probe below proves the gate then has nothing left to hide.
  const files = [
    { path: 'k8s/templates/_helpers.tpl', text: [
      '{{- define "spring-ai-rag.name" -}}x{{- end }}',
      '{{- define "spring-ai-rag.jvm-heap" -}}{{ include "spring-ai-rag.jvm-raw" . }}{{- end }}',
      '{{- define "spring-ai-rag.jvm-raw" -}}y{{- end }}',
      '{{- define "spring-ai-rag.spring-profile" -}}z{{- end }}',
    ].join('\n') },
    { path: 'k8s/templates/deployment.yaml', text: 'name: {{ include "spring-ai-rag.name" . }}' },
  ];
  assert.deepEqual(
    findUnreachableHelpers(files).map((d) => d.name),
    ['spring-ai-rag.jvm-heap', 'spring-ai-rag.spring-profile'],
  );
});

test('only a real include counts, and the context argument is required', () => {
  assert.deepEqual(parseIncludes('{{ include "a" . }}'), ['a']);
  assert.deepEqual(parseIncludes('{{- include "a" . | quote }}'), ['a']);

  // A commented-out include is not a reference, and a name inside a string is not
  // either. Both come through `findUnreachableHelpers`, which strips first.
  const commented = [{ path: 'k8s/templates/deployment.yaml', text: '{{- define "a" -}}x{{- end }}\n# {{ include "a" . }}\n' }];
  assert.deepEqual(findUnreachableHelpers(commented).map((d) => d.name), ['a'],
    'a comment cannot supply the include that makes a helper live');
  assert.deepEqual(
    parseIncludes(stripConfigComments('name: {{ .Values.thing }}\n# {{ include "a" . }}')),
    [],
  );
  assert.deepEqual(parseIncludes('metadata:\n  note: "include \\"a\\" ."'), []);
});

test('Helm comments are stripped before anything is read', () => {
  // The chart's own file carries a comment explaining that three helpers were deleted
  // and why, and it names them. Matched on raw text, `spring-ai-rag.jvm-heap` comes
  // back out of the prose — and the gate's own finding is that this helper is defined
  // and never included. **A note explaining a rule is not the rule acting.**
  const prose = [
    '{{- define "spring-ai-rag.jvm-heap" -}}',
    'real body',
    '{{- end }}',
    '{{/*',
    'Batch 937 删除了 spring-ai-rag.jvm-heap，它按 jvm.heapPercent 换算堆上限。',
    '{{- define "spring-ai-rag.spring-profile" -}} was never called either.',
    '*/}}',
    '{{ include "spring-ai-rag.jvm-heap" . }}',
  ].join('\n');
  const stripped = stripConfigComments(prose);
  assert.ok(!stripped.includes('jvm.heapPercent'));
  assert.ok(!stripped.includes('never called'));
  assert.deepEqual(
    parseDefines(stripped).map((d) => d.name),
    ['spring-ai-rag.jvm-heap'],
  );
  assert.deepEqual(findUnreachableHelpers([{ path: 'k', text: prose }]), []);

  // Length and newlines survive, so a finding still maps to its own line.
  assert.equal(stripConfigComments(prose).length, prose.length);
  assert.equal(stripConfigComments(prose).split('\n').length, prose.split('\n').length);
  // An unterminated comment must not eat the rest of the file silently.
  assert.ok(!stripConfigComments('{{/* open\nstill text').includes('still text'));
});

test('both spellings of `define` are read, and neither is invented', () => {
  assert.deepEqual(
    parseDefines('{{- define "a" -}}x{{- end }}\n{{ define "b" }}y{{ end }}').map((d) => d.name),
    ['a', 'b'],
  );
  assert.deepEqual(parseDefines('{{- define variable "a" }}'), [], 'a dynamic define is not this chart\'s spelling');
  assert.deepEqual(parseDefines(''), []);
});

test('the gate runs and reports the real chart clean', () => {
  const run = spawnSync(process.execPath, [GATE], { cwd: REPO, encoding: 'utf8' });
  assert.equal(run.status, 0, `gate exited ${run.status}:\n${run.stdout}${run.stderr}`);
  assert.match(run.stdout, /Helm helper check passed/);
});

test('the gate turns red on the real chart when a helper comes back', () => {
  // Built from the real files, so the other six helpers keep their real callers —
  // a fixture containing only `_helpers.tpl` would report two of them as unreachable,
  // which is the fixture being wrong rather than the gate.
  const files = templateFiles().map((f) => ({ path: f.path, text: f.text }));
  assert.deepEqual(findUnreachableHelpers(files), [], 'the real chart is clean first');

  files.push({
    path: 'k8s/templates/_helpers.tpl',
    text: `${files.find((f) => f.path === 'k8s/templates/_helpers.tpl').text}\n{{- define "spring-ai-rag.probe" -}}x{{- end }}\n`,
  });
  assert.deepEqual(
    findUnreachableHelpers(files).map((d) => d.name),
    ['spring-ai-rag.probe'],
    'one added helper, nothing includes it',
  );
  // And with a matching include it stops being a finding — so the rule is not simply
  // "any define is a finding".
  files.push({ path: 'k8s/templates/deployment.yaml', text: 'x: {{ include "spring-ai-rag.probe" . }}' });
  assert.deepEqual(findUnreachableHelpers(files), []);
});

test('the floor is below the real count and the deleted three are gone for good', () => {
  const files = templateFiles();
  const helpers = stripConfigComments(files.find((f) => f.path === 'k8s/templates/_helpers.tpl').text);
  const names = parseDefines(helpers).map((d) => d.name);
  assert.ok(names.length >= MIN_HELPERS, `${names.length} helpers, floor ${MIN_HELPERS}`);
  for (const gone of [
    'spring-ai-rag.jvm-heap',
    'spring-ai-rag.jvm-raw',
    'spring-ai-rag.spring-profile',
  ]) {
    assert.ok(!names.includes(gone), `${gone} is still defined`);
    assert.ok(!helpers.includes('heapPercent'), 'the undeclared jvm.heapPercent knob is gone with them');
    assert.ok(!helpers.includes('.Values.springProfile'), 'the undeclared top-level springProfile is gone');
  }
  // The live mechanism is untouched: the heap still comes from jvm.maxHeap, and the
  // profile still travels as the environment variable from secret.yaml.
  const deployment = stripConfigComments(
    files.find((f) => f.path === 'k8s/templates/deployment.yaml').text,
  );
  assert.ok(deployment.includes('.Values.jvm.maxHeap'), 'deployment.yaml still reads jvm.maxHeap');
  const secret = stripConfigComments(files.find((f) => f.path === 'k8s/templates/secret.yaml').text);
  assert.ok(secret.includes('SPRING_PROFILES_ACTIVE'), 'the profile still arrives as an env var');
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
  `Helm helper liveness self-test: ${cases.length - failed}/${cases.length} case(s) passed.\n`,
);
process.exit(failed === 0 ? 0 : 1);