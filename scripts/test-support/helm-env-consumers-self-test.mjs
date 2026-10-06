#!/usr/bin/env node
// Self-test for scripts/verify-helm-env-consumers.mjs.
//
// `k8s/templates/secret.yaml` feeds the container through `envFrom: secretRef`, so
// every key in `stringData` becomes an environment variable. Two of the eighteen did
// not have a reader, and one of them — `MINIMAX_API_KEY_ID` — was also the condition
// guarding the whole MiniMax block, which made a *correct* MiniMax configuration
// silently do nothing.
//
// The cases below lean on the negative direction first: a name that appears in a
// demo, in `docker-compose.yml` or in the chart's own file must not count, because
// each of those made one of the two look alive during the census.

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import {
  DECLARER,
  findUnreadHelmVariables,
  isDeployedApplication,
  isFrameworkNamespace,
  MIN_DECLARED,
  parseHelmSecretEnv,
} from '../verify-helm-env-consumers.mjs';

// `import.meta.url` is a file: `join(here, '..', '..')` lands in `scripts/`.
const REPO = fileURLToPath(new URL('../..', import.meta.url));
const GATE = join(REPO, 'scripts', 'verify-helm-env-consumers.mjs');
const DECLARER_PATH = join(REPO, DECLARER);

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

const SECRET = [
  'apiVersion: v1',
  'kind: Secret',
  'metadata:',
  '  name: {{ include "spring-ai-rag.fullname" . }}-env',
  'type: Opaque',
  'stringData:',
  '  POSTGRES_HOST:     {{ .Values.secrets.postgresHost | quote }}',
  '  OPENAI_API_KEY:    {{ default .Values.secrets.deepseekApiKey .Values.secrets.openaiApiKey | quote }}',
  '  RAG_EMBEDDING_API_KEY: {{ required "…" .Values.secrets.embeddingApiKey | quote }}',
  '',
].join('\n');

test('only the stringData block, and only its keys', () => {
  // `apiVersion` / `kind` / `metadata` / `type` share the `NAME:` shape and are not
  // environment variables. A parser that did not distinguish them reported the
  // chart as injecting five Kubernetes fields into the container.
  assert.deepEqual(
    parseHelmSecretEnv(SECRET).map((v) => v.name),
    ['POSTGRES_HOST', 'OPENAI_API_KEY', 'RAG_EMBEDDING_API_KEY'],
  );
  assert.deepEqual(parseHelmSecretEnv('apiVersion: v1\nkind: Secret\n'), []);
  assert.deepEqual(parseHelmSecretEnv('stringData:\n'), []);
});

test('the two that actually happened are reported', () => {
  const secret = [
    'stringData:',
    '  DEEPSEEK_API_KEY: {{ required "…" .Values.secrets.deepseekApiKey | quote }}',
    '  OPENAI_API_KEY:   {{ default … }}',
    '  MINIMAX_API_KEY:  {{ .Values.secrets.minimaxApiKey | quote }}',
    '  MINIMAX_API_KEY_ID: {{ .Values.secrets.minimaxApiKeyId | quote }}',
    '',
  ].join('\n');
  const names = findUnreadHelmVariables(secret, [
    { path: 'spring-ai-rag-core/src/main/resources/application.yml', text: 'password: ${OPENAI_API_KEY:dummy}' },
    { path: 'k8s/templates/configmap.yaml', text: 'password: ${MINIMAX_API_KEY:}' },
  ]).map((v) => v.name);
  assert.deepEqual(names, ['DEEPSEEK_API_KEY', 'MINIMAX_API_KEY_ID']);
});

test('a demo, docker-compose and the chart itself are not the deployed application', () => {
  // Each of these is why one of the two looked alive during the census.
  // `demos/demo-component-level/application.yml` reads `DEEPSEEK_API_KEY`, and
  // `docker-compose.yml` uses it as a **host-side** fallback for `OPENAI_API_KEY` —
  // compose resolves it before the container exists, so it is not a reader inside
  // one. `secret.yaml` is the declarer; counting it would make every name alive.
  const secret = 'stringData:\n  DEEPSEEK_API_KEY: x\n  MINIMAX_API_KEY_ID: y\n';
  assert.deepEqual(findUnreadHelmVariables(secret, [
    { path: 'demos/demo-component-level/src/main/resources/application.yml', text: 'DEEPSEEK_API_KEY' },
    { path: 'docker/docker-compose.yml', text: 'OPENAI_API_KEY: ${DEEPSEEK_API_KEY:-…}' },
    { path: DECLARER, text: 'DEEPSEEK_API_KEY: x' },
  ]).map((v) => v.name), ['DEEPSEEK_API_KEY', 'MINIMAX_API_KEY_ID']);

  // The positive side, so the boundary is not "everything except those three".
  assert.deepEqual(findUnreadHelmVariables(secret, [
    { path: 'spring-ai-rag-core/src/main/resources/application.yml', text: 'key: ${DEEPSEEK_API_KEY:dummy}' },
    { path: 'k8s/templates/configmap.yaml', text: 'id: ${MINIMAX_API_KEY_ID:}' },
  ]), []);
});

test('the deployed application is named positively', () => {
  for (const path of [
    'spring-ai-rag-api/src/main/java/A.java',
    'spring-ai-rag-core/src/main/resources/application.yml',
    'spring-ai-rag-documents/src/main/resources/application.yml',
    'spring-ai-rag-starter/src/main/resources/application.yml',
    'spring-ai-rag-webui/src/api/client.ts',
    'k8s/templates/configmap.yaml',
  ]) {
    assert.equal(isDeployedApplication(path), true, `deployed: ${path}`);
  }
  for (const path of [
    'demos/demo-multi-model/src/main/resources/application.yml',
    'docker/docker-compose.yml',
    'k8s/templates/secret.yaml',
    'k8s/templates/deployment.yaml',
    'spring-ai-rag-webui-e2e/fixtures/x.yml',
    'docs/configuration.md',
  ]) {
    assert.equal(isDeployedApplication(path), false, `not deployed: ${path}`);
  }
});

test('`SPRING_*` is consumed by the framework, as a namespace and not as a list', () => {
  // Spring Boot binds the whole prefix to properties. `SPRING_PROFILES_ACTIVE` is
  // how the chart sets the profile at all — the ConfigMap is mounted as a file at
  // `/config`, so this Secret is the only path. A one-name allowlist would say the
  // same thing and would have to be updated; a namespace rule says what it means.
  assert.equal(isFrameworkNamespace('SPRING_PROFILES_ACTIVE'), true);
  assert.equal(isFrameworkNamespace('SPRING_AI_MINIMAX_API_KEY'), true);
  assert.equal(isFrameworkNamespace('POSTGRES_HOST'), false);
  assert.equal(isFrameworkNamespace('MINIMAX_API_KEY_ID'), false,
    'the one that gated the MiniMax block is not in the framework namespace');

  assert.deepEqual(
    findUnreadHelmVariables('stringData:\n  SPRING_PROFILES_ACTIVE: "postgresql,prod"\n', []),
    [],
  );
});

test('the gate runs and reports the real chart clean', () => {
  const run = spawnSync(process.execPath, [GATE], { cwd: REPO, encoding: 'utf8' });
  assert.equal(run.status, 0, `gate exited ${run.status}:\n${run.stdout}${run.stderr}`);
  assert.match(run.stdout, /Helm env check passed/);
});

test('the gate turns red on the real chart when a key comes back', () => {
  // The reverse probe. Both keys were removed in this batch, so the only way to learn
  // whether the gate can still reject is to put one back. Restored by content, so a
  // concurrent edit to the chart cannot be discarded here.
  const original = readFileSync(DECLARER_PATH, 'utf8');
  // On the parsed names, not the raw text: the chart's comment quotes
  // `${OPENAI_API_KEY:-${DEEPSEEK_API_KEY:-…}}`, and a substring check on the file
  // matches that and refuses to run. The question is "is the key declared", and the
  // parser is what answers it.
  assert.ok(
    !parseHelmSecretEnv(original).some((v) => v.name === 'DEEPSEEK_API_KEY'),
    'the fixture key must not already be declared',
  );
  try {
    writeFileSync(
      DECLARER_PATH,
      `${original}\n  DEEPSEEK_API_KEY: {{ required "probe" .Values.secrets.deepseekApiKey | quote }}\n`,
    );
    const run = spawnSync(process.execPath, [GATE], { cwd: REPO, encoding: 'utf8' });
    assert.equal(run.status, 1, 'a key nothing reads must fail the gate');
    assert.ok(run.stderr.includes('DEEPSEEK_API_KEY'), 'and name it');
    assert.ok(run.stderr.includes('injects 1 environment variable'), `exactly one, got: ${run.stderr}`);
  } finally {
    writeFileSync(DECLARER_PATH, original);
  }
  const after = spawnSync(process.execPath, [GATE], { cwd: REPO, encoding: 'utf8' });
  assert.equal(after.status, 0, 'and pass again once the chart is restored');
});

test('the floor assertion is above the real count and the parser agrees with it', () => {
  const declared = parseHelmSecretEnv(readFileSync(DECLARER_PATH, 'utf8'));
  assert.ok(declared.length >= MIN_DECLARED, `${declared.length} declared, floor ${MIN_DECLARED}`);
  assert.equal(declared.length, 16, 'eighteen minus the two that nothing read');
  assert.deepEqual(parseHelmSecretEnv(''), [], 'an empty chart declares nothing');
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
  `Helm env consumer self-test: ${cases.length - failed}/${cases.length} case(s) passed.\n`,
);
process.exit(failed === 0 ? 0 : 1);