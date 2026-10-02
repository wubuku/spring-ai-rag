#!/usr/bin/env node
// Census: which @Test methods would pass no matter what the production code did?
//
// The defect this catches is narrow on purpose. A `@Test` whose body is empty —
// or holds nothing but comments — reports as a *passing* test in every run, so it
// inflates the passing count and the coverage number while asserting nothing.
// Batch 809's neighbour found three of them in `PgTrgmFulltextProviderTest`, each
// carrying a `@DisplayName` that promised specific behaviour and a body comment
// claiming the behaviour was "covered via HybridRetrieverService integration
// tests" — a claim that was never checked and, for `minScore` and `excludeIds`,
// was the only place the Java-side filtering was ever exercised.
//
// What this deliberately does NOT flag, and why. A first draft of this census
// asked a much broader question: "does the test state an expectation anywhere in
// its call closure?" That produced 191, then 40, then 30 candidates, and reading
// the top of each list showed the detector was wrong every time — first because
// it did not recognise `verifyNoInteractions` or MockMvc's `.andExpect`, then
// because it did not follow assertions hidden in private helpers. After the
// detector was repaired it still reported ~25 tests whose entire contract is
// "calling this must not throw", which JUnit enforces: an unexpected exception
// fails the test. Those are legitimate, and a gate that called them defects
// would push every one of them onto an allowlist, which is a baseline, not a
// check.
//
// A static gate should fail by missing something, not by crying wolf. So the
// rule is the one case with no reading involved: the body is empty.
//
// The companion question — a test whose *name* promises more than its body
// verifies ("fallsBackToCancelledOutcome" that never inspects the outcome) —
// needs per-file judgement rather than a rule, and is tracked in the ledger
// instead of pretended at here.

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));

const TEST_ROOTS = [
  'spring-ai-rag-core/src/test/java',
  'spring-ai-rag-api/src/test/java',
  'spring-ai-rag-documents/src/test/java',
  'spring-ai-rag-starter/src/test/java',
];

const TEST_ANNOTATIONS = /@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b/;

/** Strip block and line comments, then decide whether anything executable is left. */
export function executableStatements(body) {
  return body
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function bodyFrom(source, braceIndex) {
  let depth = 0;
  for (let i = braceIndex; i < source.length; i += 1) {
    const ch = source[i];
    if (ch === '"' || ch === "'") {
      const quote = ch;
      i += 1;
      while (i < source.length && source[i] !== quote) {
        if (source[i] === '\\') i += 1;
        i += 1;
      }
      continue;
    }
    if (ch === '{') depth += 1;
    else if (ch === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(braceIndex + 1, i);
    }
  }
  return null;
}

const DECLARATION_HEAD = /^[\t ]*((?:(?:public|protected|private|static|final|synchronized|abstract|default|native|strictfp)[\t ]+)*)((?:@[\w.]+(?:\([^)]*\))?[\t ]+)*)((?:[\w.<>\[\], ?]+[\t ]+)?)([A-Za-z_]\w*)[\t ]*\(/gm;

function matchParameterList(source, openIndex) {
  let depth = 0;
  for (let i = openIndex; i < source.length; i += 1) {
    const ch = source[i];
    if (ch === '(') depth += 1;
    else if (ch === ')') {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  return -1;
}

/**
 * Method declarations in source order. The tell that distinguishes a declaration
 * from a call is what follows the parameter list: `{` (or `throws ... {`) for a
 * body, never for an invocation. Matching "text that looks like a call" instead
 * reported several hundred `assertEquals(a, b);` lines as methods.
 */
export function collectMethods(source) {
  const methods = [];
  for (const match of source.matchAll(DECLARATION_HEAD)) {
    const name = match[4];
    // Positions are recovered by scanning from the match rather than by
    // reconstructing them from the match text: an earlier version computed the
    // parameter list's offset as `match.index + head.length - 1` and read the
    // body as " {" — the method looked non-empty, so the census found nothing.
    const parenStart = source.indexOf('(', match.index);
    if (parenStart < 0) continue;
    const closeIndex = matchParameterList(source, parenStart);
    if (closeIndex < 0) continue;
    const braceIndex = source.indexOf('{', closeIndex);
    // A body belongs to this declaration only if it follows immediately (a
    // `throws` clause may sit between). Anything further away is a later member.
    if (braceIndex < 0 || braceIndex - closeIndex > 400) continue;
    if (!/^[\s]*(throws[\s\S]{0,200}?)?\{/.test(source.slice(closeIndex + 1))) continue;
    const body = bodyFrom(source, braceIndex);
    if (body === null) continue;
    methods.push({ name, body, start: match.index, end: closeIndex });
  }
  return methods;
}

/** @Test methods whose body holds no executable statement. */
export function findInertTests(source) {
  const inert = [];
  let previousEnd = 0;
  for (const method of collectMethods(source)) {
    const gap = source.slice(previousEnd, method.start);
    previousEnd = Math.max(previousEnd, method.end);
    if (!TEST_ANNOTATIONS.test(gap)) continue;
    if (executableStatements(method.body) === '') inert.push(method.name);
  }
  return inert;
}

function walk(dir) {
  if (!statSync(dir, { throwIfNoEntry: false })?.isDirectory()) return [];
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : path.endsWith('.java') ? [path] : [];
  });
}

export function collectFindings(root) {
  const findings = [];
  for (const testRoot of TEST_ROOTS) {
    for (const file of walk(join(root, testRoot))) {
      for (const test of findInertTests(readFileSync(file, 'utf8'))) {
        findings.push({ file: `${testRoot}/${relative(join(root, testRoot), file)}`, test });
      }
    }
  }
  return findings;
}

function main() {
  const root = process.argv[2] ? join(process.cwd(), process.argv[2]) : repoRoot;
  const findings = collectFindings(root);
  if (findings.length > 0) {
    console.error('@Test methods with an empty body report as passes in every run:');
    for (const finding of findings) {
      console.error(`- ${finding.file}#${finding.test}`);
    }
    console.error(
      '\nEither write the assertion, or delete the method: an empty @Test inflates the\n' +
        'passing count and the coverage number while proving nothing. @Disabled is honest\n' +
        'because it is reported as a skip.',
    );
    process.exitCode = 1;
    return;
  }
  const roots = TEST_ROOTS.filter((dir) => statSync(join(root, dir), { throwIfNoEntry: false }));
  console.log(
    `Inert-test census passed; ${roots.length} test source root(s) scanned, no @Test method ` +
      'has an empty body.',
  );
}

if (import.meta.url === `file://${process.argv[1]}`) main();
