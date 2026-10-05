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
// rules are the cases with no reading involved. There are two, added in
// different batches and deliberately kept in one gate: the body is empty, and
// the method is `private` or `static` so JUnit 5 never runs it at all. The
// second is the worse of the two and the quieter — an empty body still appears
// in the report as a pass, whereas a skipped-by-discovery method does not
// appear anywhere, so a build goes green while the test count shrinks.
//
// The companion question — a test whose *name* promises more than its body
// verifies ("fallsBackToCancelledOutcome" that never inspects the outcome) —
// needs per-file judgement rather than a rule, and is tracked in the ledger
// instead of pretended at here.
//
// Batch 897 tried to build that rule anyway, because the sentence above reads
// like an invitation, and it measured three ways of failing. The numbers are
// here so the next person does not spend a batch rediscovering them:
//
//   * every camelCase word of the name must appear in the body: 7993 of 8009
//     methods. A name names the subject (`chatRequest_constructor`) and a body
//     refers to the subject through the variable it declared, so this compares a
//     label to a variable name and the words that can never match — "request",
//     "constructor", "setter" — are structural vocabulary, not claims.
//   * only verb-like claim words count: 3100, and 3068 after following same-file
//     private helper calls. Reading the top of the list is enough: the first
//     entry is `chatRequest_defaultValues`, whose body asserts three defaults.
//   * the narrowest version — "returns empty" in the name with no emptiness
//     assertion: 23, of which 3 were real (`search_limitZero_returnsEmpty` and
//     two siblings, all `assertNotNull`) and 7 of the remaining 20 were checked
//     by hand and 7 were false positives, because the *detector* did not
//     recognise `assertEquals(List.of(), …)`, `assertEquals("", …)` or
//     `assertEquals("[]", …)` as emptiness assertions.
//
// The last one is the general lesson, and it is why this is not a rule: every
// version failed at the same place. Asking "does this test check emptiness?" is
// harder than the thing being asked, so the scanner's answer was less reliable
// than the tests it was judging. Three real defects were found on the way and
// fixed by hand; the rule itself would have reported them alongside twenty
// false alarms, which is a baseline, not a check.

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isMainModule } from './lib/is-main-module.mjs';

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

/**
 * The text between a method's opening brace and its matching close, or `null`
 * when there is no match.
 *
 * Comments are stepped over before quotes are considered, and the order is the
 * whole fix. Batch 888 found eighteen real `@Test` methods that this function
 * had been swallowing whole: a `//` comment containing a quote character — the
 * apostrophe in "the caller's assertEquals", the `"9"` in a note about
 * lexicographic ordering — was read as a string delimiter, the scan ran forward
 * looking for a partner quote that the file did not contain, and it never came
 * back. The method body came back `null`, and both rules skip a method with no
 * body, so eighteen tests spread over fourteen files were invisible to the gate
 * and an empty-bodied one among them would have passed unnoticed. It is the
 * wrong direction for a gate to fail in: the scan has to be able to lose the
 * method, and losing it to an apostrophe in prose is not a trade anybody wants.
 */
function bodyFrom(source, braceIndex) {
  let depth = 0;
  for (let i = braceIndex; i < source.length; i += 1) {
    const ch = source[i];
    if (ch === '/' && source[i + 1] === '/') {
      const newline = source.indexOf('\n', i);
      i = newline === -1 ? source.length : newline;
      continue;
    }
    if (ch === '/' && source[i + 1] === '*') {
      const close = source.indexOf('*/', i + 2);
      i = close === -1 ? source.length : close + 1;
      continue;
    }
    // A text block has to be recognised before the ordinary string case, since
    // three quotes in a row are a string delimiter the quote scan would consume
    // two at a time and then read the payload as code. The braces inside are
    // data. Batch 886 reasoned that this was harmless because the body only
    // feeds an emptiness check and an over-long body reads as non-empty; that
    // reasoning was sound and the data was still wrong — nine method bodies in
    // `MultiModelConfigLoaderTest` ran past their own closing brace and swallowed
    // the methods below them, and two text blocks in
    // `EvaluationSuiteDefinitionCaseValidationTest` do not even balance. A
    // conclusion about what a wrong value happens to be used for is not a reason
    // to leave it wrong.
    if (ch === '"' && source[i + 1] === '"' && source[i + 2] === '"') {
      let from = i + 3;
      for (;;) {
        const close = source.indexOf('"""', from);
        if (close === -1) {
          from = source.length;
          break;
        }
        // `\"""` is an escaped delimiter, not the end of the block.
        let backslashes = 0;
        for (let k = close - 1; k >= 0 && source[k] === '\\'; k -= 1) backslashes += 1;
        if (backslashes % 2 === 0) {
          from = close;
          break;
        }
        from = close + 1;
      }
      i = from;
      continue;
    }
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

/**
 * Words that can only be the "name" of a match that is really a statement.
 *
 * A declaration has a return type or a modifier in front of its name, which is
 * what the head pattern consumes first; a control-flow statement has neither, so
 * whatever lands in the name group is a Java keyword. Measured on the real tree,
 * 542 of 12150 reported "declarations" were statements — 266 `for`, 229 `if`,
 * 33 `return`, 22 `while`, 4 `new`, one `switch` and the rest — and each one
 * reported a block as if it were a method body.
 *
 * Rejecting them is deliberately surgical. The match still begins where it
 * began, so the gap window `findInertTests` builds between one declaration and
 * the next is unchanged for the methods that survive; what stops is the false
 * declarations advancing `previousEnd` past the real ones. The alternative —
 * tightening the pattern so a statement cannot match at all — would move
 * `match.index` and quietly shift every window, which is how Batch 887 broke
 * the empty-body rule once already.
 *
 * `var` is here because a local `var x = ...` has no return type to consume.
 * The contextual keywords that *can* legally name a method — `record`,
 * `sealed`, `permits` — are deliberately absent: a false rejection is the one
 * failure this list cannot show you, because the method it dropped simply stops
 * being scanned and nothing anywhere reports the absence.
 */
const STATEMENT_KEYWORDS = new Set([
  'assert', 'await', 'break', 'case', 'catch', 'continue', 'delete', 'do', 'else',
  'finally', 'for', 'if', 'instanceof', 'new', 'return', 'super', 'switch',
  'this', 'throw', 'try', 'var', 'while', 'yield',
]);

/**
 * The same distinction one step to the left, for the one statement shape that
 * carries a `{` and so survives the body check: an anonymous class, written
 * `return new Advisor() {` or `new Advisor() {`. There the name the pattern
 * captures is the class being instantiated, which is why the keyword test above
 * does not see it — the word sitting where a return type goes is `return` or
 * `new`, and no method has one of those. 37 of these were left on the real tree
 * after the keyword filter went in.
 */
const ANONYMOUS_CLASS_HEAD = /^(?:return|new)\b/;

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
    if (STATEMENT_KEYWORDS.has(name)) continue;
    if (ANONYMOUS_CLASS_HEAD.test(match[3] ?? '')) continue;
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
    methods.push({
      name,
      body,
      modifiers: match[1] ?? '',
      start: match.index,
      end: closeIndex,
    });
  }
  return methods;
}

function parenBalance(line) {
  let balance = 0;
  for (const ch of line) {
    if (ch === '(') balance += 1;
    else if (ch === ')') balance -= 1;
  }
  return balance;
}

/**
 * The annotation block sitting immediately above a declaration, or `''`.
 *
 * This walks *up* from the declaration's own line and stops at the first line
 * that is neither blank, nor a comment, nor an annotation. That contiguity is
 * what makes it exact, and exactness is the whole point: the alternative —
 * reuse the empty-body rule's "text between one declaration and the next" — is
 * bounded by the previous declaration's *parameter list*, not its body, so the
 * window still contains the previous method's entire body, and the window for
 * the first method in a file starts at byte 0. Keyed on modifiers that turned
 * into roughly two thousand reported private helpers.
 *
 * A declaration that a wrapped annotation sits above (`@CsvSource({ ... })`
 * spread over four lines) is not recognised unless the parentheses balance,
 * because the closing line does not start with `@`. That is a miss, which is
 * the acceptable direction: this rule exists to catch a `@Test` that JUnit
 * skips, and erring toward silence never invents a defect.
 */
function annotationBlockAbove(source, declarationStart) {
  const collected = [];
  let depth = 0;
  let lineEnd = declarationStart > 0 ? source.lastIndexOf('\n', declarationStart - 1) : -1;
  while (lineEnd !== -1) {
    const lineStart = source.lastIndexOf('\n', lineEnd - 1) + 1;
    const trimmed = source.slice(lineStart, lineEnd).trim();
    lineEnd = lineStart - 1;
    if (trimmed === '') continue;
    if (trimmed.startsWith('//') || trimmed.startsWith('/*') || trimmed.startsWith('*')) continue;
    // The walk runs upwards, so a wrapped annotation presents its *closing* line
    // first and `depth` is still zero when that line is reached. A line that
    // nets negative parentheses can only be closing something, so it carries the
    // walk past the point a `@`-prefix test would have stopped at.
    const balance = parenBalance(trimmed);
    if (trimmed.startsWith('@') || depth !== 0 || balance < 0) {
      if (trimmed.startsWith('@')) collected.push(trimmed);
      depth += balance;
      continue;
    }
    break;
  }
  return collected.join('\n');
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

/**
 * `@Test` methods JUnit 5 will not run at all (Batch 887).
 *
 * JUnit 5 discovers a test method only if it is non-private, non-static and
 * concrete. A `private @Test` or a `static @Test` is not an error and not a
 * failure: the class still compiles, still appears in the report, and simply
 * contributes one fewer test case. So the coverage quietly shrinks and the build
 * stays green — the same family as an inert test body, except that this one does
 * not even show up as a test.
 *
 * The rule is definitional rather than heuristic, which is why it belongs here
 * next to the empty-body rule and not in a separate gate: "JUnit ignores it" has
 * an exact answer, so there is nothing to read and nothing to allowlist. When
 * the rule was written the tree held no occurrence, so this closes a hole rather
 * than reporting existing damage — and the census re-measures and prints the
 * population on every run, so "no occurrence" cannot quietly become a stale
 * claim the way a count typed into this comment would.
 *
 * **The annotation is read off the lines directly above the declaration, not off
 * the text that precedes it.** The obvious cheap alternative is to reuse the
 * empty-body rule's window, "the source between one declaration and the next".
 * That window is bounded by the previous declaration's *parameter list* rather
 * than its body, and for the first declaration in a file it starts at byte 0 —
 * so a private helper is judged by text that does not belong to it. Measured on
 * the real tree, reading from the whole file prefix reports 743 private/static
 * helpers (`request` 23 times, `dataSource` 17, `document` 10); reading from the
 * previous parameter list reports 1, so the volume depends on how wide the
 * window is drawn and the defect is not the volume but the unfoundedness — it
 * would also report a private helper under a file header comment that merely
 * mentions `@Test`. `annotationBlockAbove` stops at the first line that is not
 * an annotation, so neither the class header nor the previous member's body is
 * in scope.
 */
export function findUnrunnableTests(source) {
  return collectMethods(source)
    .filter((method) => TEST_ANNOTATIONS.test(annotationBlockAbove(source, method.start)))
    .filter((method) => /\b(?:private|static)\b/.test(method.modifiers))
    .map((method) => method.name);
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
  let files = 0;
  let tests = 0;
  for (const testRoot of TEST_ROOTS) {
    for (const file of walk(join(root, testRoot))) {
      const source = readFileSync(file, 'utf8');
      const where = `${testRoot}/${relative(join(root, testRoot), file)}`;
      files += 1;
      for (const method of collectMethods(source)) {
        if (TEST_ANNOTATIONS.test(annotationBlockAbove(source, method.start))) tests += 1;
      }
      for (const test of findInertTests(source)) findings.push({ kind: 'inert', file: where, test });
      for (const test of findUnrunnableTests(source)) {
        findings.push({ kind: 'unrunnable', file: where, test });
      }
    }
  }
  return { findings, files, tests };
}

function main() {
  const root = process.argv[2] ? join(process.cwd(), process.argv[2]) : repoRoot;
  const { findings, files, tests } = collectFindings(root);
  if (findings.length > 0) {
    for (const kind of ['inert', 'unrunnable']) {
      const group = findings.filter((f) => f.kind === kind);
      if (group.length === 0) continue;
      console.error(kind === 'inert'
        ? '@Test methods with an empty body report as passes in every run:'
        : '@Test methods JUnit 5 never runs, so the class simply reports one test fewer:');
      for (const finding of group) {
        console.error(`- [${kind}] ${finding.file}#${finding.test}`);
      }
      console.error(kind === 'inert'
        ? '\nEither write the assertion, or delete the method: an empty @Test inflates the\n'
          + 'passing count and the coverage number while proving nothing. @Disabled is honest\n'
          + 'because it is reported as a skip.'
        : '\nJUnit 5 discovers a test method only if it is non-private, non-static and concrete.\n'
          + 'A `private` or `static` @Test is not an error and not a failure: the class still\n'
          + 'compiles, still reports, and contributes one fewer test case. Drop the modifier.');
      console.error('');
    }
    process.exitCode = 1;
    return;
  }
  const roots = TEST_ROOTS.filter((dir) => statSync(join(root, dir), { throwIfNoEntry: false }));
  console.log(
    `Inert-test census passed; ${tests} @Test method(s) across ${files} file(s) in ${roots.length} `
      + 'test source root(s): none has an empty body, and none is private or static.',
  );
}

if (isMainModule(import.meta.url)) main();
