#!/usr/bin/env node
// Negative tests for scripts/verify-zh-translation.mjs.
//
// A gate that cannot fail is worse than no gate, and this repository has a
// documented history of producing exactly that defect. Every case here asserts
// that the checker *rejects* the shape it claims to reject — and, just as
// importantly, that it does NOT reject the English a Chinese document is
// supposed to keep: identifiers, API paths, config keys, product names.
// A gate that cries wolf gets switched off, so the false-positive cases carry
// as much weight as the true-positive ones.

import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  findUntranslated,
  longestProseRun,
  proseOf,
  chineseDocuments,
} from '../verify-zh-translation.mjs';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

const lines = (...ls) => ls.join('\n');
const hits = doc => findUntranslated(doc).map(h => h.line);

test('a copied English paragraph is rejected', () => {
  // The Batch 783 shape verbatim: prose lifted out of the English original and
  // dropped into the Chinese document.
  const doc = lines(
    '## 通用约定',
    '',
    'Upload text files and embed in one step. Suitable for direct file submission from frontend.',
    '',
    '### `POST /api/v1/rag/documents/upload`',
    '',
    '**Request body:**',
  );
  assert.deepEqual(hits(doc), [3]);
});

test('an English table row is rejected', () => {
  // Table cells were where most of the untranslated text hid: 60 rows in the
  // pre-Batch-783 file.
  const doc = lines(
    '| 字段 | 类型 | 说明 |',
    '|-------|------|------|',
    '| `totalRequests` | long | Total requests since service startup |',
  );
  assert.deepEqual(hits(doc), [3]);
});

test('a short untranslated one-liner is rejected', () => {
  // Most of the pre-Batch-783 prose was this short: "Get alert statistics."
  assert.deepEqual(hits(lines('Get alert statistics.')), [1]);
});

test('short lines made only of capitalised words are left alone', () => {
  // All-English *headings* and bold labels are a separate concern, and the
  // obvious relaxations produce far more noise than signal: allowing a
  // capitalised initial flags 20 lines across this tree, and 15 of them are
  // legitimate — Keep a Changelog section names, `## [1.1.0-SNAPSHOT] -
  // 2026-04-04 Early Morning`, `| Spring Boot | 3.4.x |`. A gate that cries
  // wolf gets switched off, so the prose rule stays strict and the heading-text
  // gap is registered as debt instead.
  assert.deepEqual(hits(lines('## Cache Monitoring')), []);
  assert.deepEqual(hits(lines('**SSE Events:**')), []);
  assert.deepEqual(hits(lines('### Technical Stack')), []);
});

test('a translated document passes', () => {
  const doc = lines(
    '## 通用约定',
    '',
    '所有错误响应遵循 RFC 7807 定义的 `application/problem+json` 格式。',
    '',
    '### `GET /api/v1/rag/health`',
    '',
    '服务健康检查。',
  );
  assert.deepEqual(hits(doc), []);
});

test('English a Chinese document must keep is accepted', () => {
  // Every line here was a false positive in an earlier revision of the checker.
  const doc = lines(
    '| `demos/` | basic / component / domain / multi-model |',
    '| PostgreSQL + pgvector | 15+ / 0.7.x |',
    '| `../spring-ai-skills-demo` | Spring AI ChatClient / Advisor / VectorStore |',
    '- Maven：3213 tests（API 530、Documents 74、Core 2557）',
    '### 9.1 Token-aware Prompt Budget',
    '### `GET /api/v1/rag/search`',
    '# spring-ai-rag',
    '- `403 COLLECTION_PURGE_FORBIDDEN`',
    '[`CollectionIdentityResolver`](../spring-ai-rag-core/src/main/java/Foo.java)',
  );
  assert.deepEqual(hits(doc), []);
});

test('fenced code blocks are never judged', () => {
  const doc = lines(
    '下面这段是示例：',
    '',
    '```bash',
    'Upload text files and embed in one step. Suitable for direct submission.',
    '```',
    '',
    '结束。',
  );
  assert.deepEqual(hits(doc), []);
});

test('an inline code span wrapping across lines is not judged', () => {
  // A long identifier path split over two lines used to read as prose.
  const doc = lines(
    '调用链：',
    '`RagChatController -> ChatCommandMapper -> ChatExecutionService ->',
    'ModeAwareChatClientFactory`',
    '结束。',
  );
  assert.deepEqual(hits(doc), []);
});

test('a line containing any Chinese is not judged', () => {
  // The gate only catches fully untranslated lines; it does not force a
  // half-Chinese sentence into a style debate.
  assert.deepEqual(hits(lines('Get the document statistics（总数、已向量化数等）。')), []);
});

test('longestProseRun separates a phrase from an identifier list', () => {
  assert.equal(longestProseRun('upload text files and embed'), 5);
  // A hyphen breaks the run, so only the words after it accumulate.
  assert.equal(longestProseRun('token-aware prompt budget'), 3);
  // Slashes and CJK enumeration commas are how identifier lists are written;
  // they break a run instead of joining one.
  assert.equal(longestProseRun('basic / component / domain / multi-model'), 1);
  // Each camelCase identifier breaks the run; only the one plain lowercase
  // word survives, which is below MIN_WORDS and so does not trip the gate.
  assert.equal(longestProseRun('citationId、documentId、externalId、title'), 1);
  // Every token is capitalised, so nothing counts as an ordinary word.
  assert.equal(longestProseRun('DeepSeek OpenAI SiliconFlow'), 0);
  // Sentence punctuation rides along with the word and must not hide it.
  assert.equal(longestProseRun('Get alert statistics.'), 2);
});

test('proseOf removes code, links, URLs and markdown markers', () => {
  const collapse = s => s.replace(/\s+/g, ' ').trim();
  assert.equal(
    collapse(proseOf('**Response:** `GET /api/v1/x` [doc](https://a.example/b)')),
    'Response:',
  );
  assert.equal(collapse(proseOf('- `POST /a` <a id="anchor"></a> tail')), 'tail');
});

test('the real Chinese document set has no untranslated prose', () => {
  // Skipped when the tree is absent: asserting on missing files asserts
  // nothing at all.
  if (!existsSync(projectRoot)) return;
  const files = chineseDocuments(projectRoot);
  assert.ok(files.length >= 30, `expected the full document set, saw ${files.length}`);
  const offenders = files.flatMap(f =>
    findUntranslated(readFileSync(f, 'utf8')).map(
      h => `${relative(projectRoot, f)}:${h.line}: ${h.text.trim()}`,
    ),
  );
  assert.deepEqual(offenders, [], 'Chinese documents carrying untranslated English prose');
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
  console.error(`\n${failures}/${cases.length} zh-translation self-test case(s) failed.`);
  process.exit(1);
}
console.log(`\nAll ${cases.length} zh-translation self-test cases passed.`);
