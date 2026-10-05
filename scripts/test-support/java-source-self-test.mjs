#!/usr/bin/env node
// Batch 905. Four gates each had their own `stripComments`; two of them read a
// different file from the other two. These cases pin the one correct answer,
// and the last one pins the census that keeps it the only one.

import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { stripJavaComments } from '../lib/java-source.mjs';

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));

/** The shape being replaced, so the difference is shown rather than asserted. */
const naive = (s) => s.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');

// ------------------------------------------------------------- the cases --

test('a URL in a string is not a line comment', () => {
  // The common case, and most of the measured damage: 331 of 351 literals in
  // this repository contain `//` and the naive stripper destroyed every one.
  const source = 'String u = "https://example.com/api"; int x = 1;';
  assert.equal(naive(source), 'String u = "https: ', 'the naive shape is expected to truncate');
  assert.equal(stripJavaComments(source), source, 'nothing here is a comment');
});

test('a block-comment marker in a string does not swallow the code after it', () => {
  // The expensive case. The non-greedy match runs to the next `*/` anywhere in
  // the file, so one `/*` inside a string can cost a hundred lines. Here the
  // closing marker is further down, which is the shape that actually bites.
  const source = 'String p = "/* open";\nint y = 2;\nString q = "close */";\nint z = 3;\n';
  const naiveOut = naive(source);
  assert.ok(
    !naiveOut.includes('int y = 2;') || !naiveOut.includes('int z = 3;'),
    'the naive shape is expected to lose code between the two markers',
  );
  assert.equal(stripJavaComments(source), source);
});

test('an escaped quote does not end the string early', () => {
  // Without the backslash skip, one escaped quote makes everything after it
  // read as string content, and every comment after that survives.
  const source = 'String s = "a\\"b"; // note\nint z = 3;\n';
  const out = stripJavaComments(source);
  assert.match(out, /String s = "a\\"b";/, 'the literal must survive whole');
  assert.doesNotMatch(out, /note/, 'the comment must still be removed');
  assert.match(out, /int z = 3;/, 'the following statement must survive');
});

test("an apostrophe in a comment is not a string delimiter", () => {
  // Batch 888: an apostrophe in a `//` note was read as a string opener, the
  // scan ran off looking for a partner, and eighteen real `@Test` methods came
  // back with no body at all.
  const source = "int a = 1; // it's fine\nint b = 2;\n";
  const out = stripJavaComments(source);
  assert.doesNotMatch(out, /it's/, 'the comment must be gone');
  assert.match(out, /int a = 1;/, 'the code before it must survive');
  assert.match(out, /int b = 2;/, 'the code after it must survive');
});

test('a slash in a character literal is not a comment', () => {
  const source = "char c = '/'; int d = 4;";
  assert.equal(stripJavaComments(source), source);
});

test('real comments are still removed, both kinds', () => {
  const source = '/** javadoc */\nint e = 5; // trailing\nint f = 6;\n';
  const out = stripJavaComments(source);
  assert.doesNotMatch(out, /javadoc/, 'the block comment must be gone');
  assert.doesNotMatch(out, /trailing/, 'the line comment must be gone');
  assert.match(out, /int e = 5;/);
  assert.match(out, /int f = 6;/);
});

test('length and line count are preserved', () => {
  // Not an aesthetic requirement. `verify-error-code-catalog` maps a finding
  // back to a line by counting lines in the stripped text, and had to be fixed
  // once for reporting findings up to 54 lines off.
  const source = readFileSync(
    join(repoRoot, 'spring-ai-rag-core/src/main/java/com/springairag/core/usage/LlmUsageEvent.java'),
    'utf8',
  );
  const out = stripJavaComments(source);
  assert.equal(out.length, source.length, 'length must not change');
  assert.equal(out.split('\n').length, source.split('\n').length, 'line count must not change');
});

test('the correct stripper drops nothing but comments, and the naive one does', () => {
  // Two claims, and the second is what makes the first worth reading. A case
  // that says "nothing is lost" against a stripper that was never broken would
  // pass forever; this one requires the naive shape to still lose something, so
  // the comparison is known to be live.
  //
  // Note the direction: `lostByNaive` is what the OLD shape cost, not what the
  // new one costs. The first version of this case asserted it was zero, which
  // is asserting that the bug is still present.
  const roots = [
    'spring-ai-rag-api/src',
    'spring-ai-rag-core/src',
    'spring-ai-rag-starter/src',
    'spring-ai-rag-documents/src',
  ];
  const files = [];
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      if (statSync(full).isDirectory()) walk(full);
      else if (name.endsWith('.java')) files.push(full);
    }
  };
  for (const root of roots) walk(join(repoRoot, root));
  assert.ok(files.length > 500, `only ${files.length} java file(s) walked; the walk stopped early`);

  let lostByCorrect = 0;
  let lostByNaive = 0;
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    const correct = stripJavaComments(source);
    const wrong = naive(source);
    // Only literals that carry a comment marker can be mistaken for one.
    const literals = correct.match(/"((?:[^"\\\n]|\\.)*)"/g) ?? [];
    for (const quoted of literals) {
      const literal = quoted.slice(1, -1);
      if (!literal.includes('//') && !literal.includes('/*')) continue;
      if (!source.includes(literal)) lostByCorrect += 1;
      if (!wrong.includes(literal)) lostByNaive += 1;
    }
  }
  assert.equal(lostByCorrect, 0, `the shared stripper invented or dropped ${lostByCorrect} literal(s)`);
  assert.ok(
    lostByNaive > 300,
    `the naive shape only lost ${lostByNaive} literal(s); this case is not measuring the bug it claims to`,
  );
  console.log(`      (the naive shape destroyed ${lostByNaive} string literal(s) in this tree)`);
});

test('no gate defines its own comment stripper, in either module', () => {
  // 901's lesson, applied: four correct answers in four files is how three
  // behaviours happened. And the same thing had already happened once in the
  // webui package — check-design-system exports a correct stripper that seven
  // sibling checks import, while check-hardcoded-copy kept a second, regex copy
  // beside it. Latent rather than live (measured: 0 literals lost in this
  // package), but it is the same trap with the fuse pulled.
  const dirs = [join(repoRoot, 'scripts'), join(repoRoot, 'spring-ai-rag-webui/scripts')];
  const offenders = [];
  for (const dir of dirs) {
    for (const name of readdirSync(dir)) {
      if (!name.endsWith('.mjs') || name.startsWith('test-support')) continue;
      const full = join(dir, name);
      if (full.endsWith('check-design-system.mjs')) continue; // the one that owns it
      const text = readFileSync(full, 'utf8');
      if (/function stripComments\s*\(/.test(text) && !text.includes('lib/java-source.mjs')) {
        offenders.push(name);
      }
    }
  }
  assert.deepEqual(offenders, [], `gate(s) still rolling their own: ${offenders.join(', ')}`);
});

let failed = 0;
for (const { title, fn } of cases) {
  try {
    await fn();
    console.log(`ok   ${title}`);
  } catch (error) {
    failed += 1;
    console.error(`FAIL ${title}`);
    console.error(String(error && error.message ? error.message : error));
  }
}
console.log(`\n${cases.length - failed}/${cases.length} java-source self-test case(s) passed.`);
process.exit(failed === 0 ? 0 : 1);
