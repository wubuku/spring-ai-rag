#!/usr/bin/env node
/**
 * Text-alignment policy gate.
 *
 * The rule is one sentence: a stylesheet may only align text logically.
 * `text-align: left` and `right` name a physical edge, so they mean the wrong
 * thing the moment the interface is right-to-left; `center` is permitted only
 * where a comment says why, because centring a paragraph is a legibility
 * problem rather than a direction one. The same sentence holds for inline
 * `style={{ textAlign }}`, which is banned outright in favour of a CSS Module
 * class.
 *
 * **Batch 862 found the rule matching the wrong text in both directions.**
 * The scan was line-by-line with two literal regexes, and every probe below is
 * the *same violation written differently*:
 *
 *   - `text-align: CENTER` and `text-align: LEFT` passed. CSS keywords are
 *     case-insensitive; the patterns were not.
 *   - a declaration split across lines, `text-align:\n  center;`, passed. The
 *     regex ran per line, so a value on the next line was not a value at all.
 *   - inline `textAlign: 'Center'` passed, for the first reason twice.
 *
 * and the other way, prose about a violation being reported as one:
 *
 *   - `/* historically this said text-align: center *\/` was reported at the
 *     line of the comment, and a multi-line comment even reported line 3 for a
 *     property written on line 2.
 *
 * Fixing the first group by lowering the regex to the next line would have made
 * the second group worse, so this version scans the whole file with `\s*`
 * between the property and its value, matches case-insensitively, and takes
 * `stripComments` from the design-system gate first — the same one every other
 * gate uses, and the one that preserves line count so the reported line is
 * still the real one.
 *
 * **Batch 881 found three more things answering for a declaration that was not
 * one.** Same family as the batch above — the rule is about a declaration, and
 * something that merely resembles one was standing in for it:
 *
 *   - test files were walked. This was the only one of the six frontend gates
 *     that did, and it was the worst version of the mistake: a fixture holding
 *     one template literal with `text-align: center` inside failed the whole
 *     gate and pointed at a file nobody ships.
 *   - `--text-align: center` was read as a declaration. It is a custom
 *     property definition, and in a project built around a token layer that is
 *     the obvious next name to use. Worse, the `allow-center` escape hatch
 *     papered over it: a token could be waved through with a comment about
 *     centring.
 *   - an `allow-center` comment outlived the centre it justified, and nothing
 *     noticed, so the next centre written under it inherited a reason written
 *     for a declaration that no longer exists. The same rot
 *     `check-hardcoded-copy` had in its allowlist.
 *
 * Run: node scripts/check-alignment-policy.mjs
 */

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');
const violations = [];
let intentionalCenterCount = 0;

/**
 * Every source file the policy applies to.
 *
 * Batch 881. This was the only one of the six frontend gates that walked test
 * files. Every sibling filters them — `check-a11y-forms`, `check-design-system`,
 * `check-hardcoded-copy`, `check-i18n-keys` and `check-page-shell` all do — and
 * the reason is the same in each: a test is not shipped code, and a test that
 * quotes a violation in order to assert on it is the normal way to document one.
 * Here it was worse than inconsistent. A fixture holding a single template
 * literal with `text-align: center` inside it failed the whole gate and pointed
 * at a file nobody ships, and the only way out was an `allow-center` comment
 * justifying a *test string* as if it were a centring decision.
 */
function walk(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return walk(path);
    if (/\.(test|spec)\./.test(entry.name)) return [];
    return /\.(css|tsx|ts)$/.test(entry.name) ? [path] : [];
  });
}

function addViolation(path, lineNumber, message) {
  violations.push(`${relative(projectRoot, path)}:${lineNumber} ${message}`);
}

/** The 1-based line an offset falls on. */
function lineAt(text, index) {
  return text.slice(0, index).split('\n').length;
}

/** The whole trimmed line an offset falls on. */
function lineTextAt(text, index) {
  const start = text.lastIndexOf('\n', index - 1) + 1;
  const end = text.indexOf('\n', index);
  return text.slice(start, end === -1 ? text.length : end).trim();
}

/**
 * The spans every comment occupies, so a match can be told apart from prose
 * that merely talks about one.
 *
 * The scan deliberately reads the **raw** source. `stripComments` replaces a
 * comment with a single space, which is the right way to stop prose from
 * satisfying a rule and the wrong way to find an exemption: the
 * `alignment-policy: allow-center` comment *is* the answer this gate is asking
 * for, so erasing it turns all eleven intentional centres in the tree into
 * violations. `check-mutation-errors` hit the identical trap and says so next
 * to its `swallowed-rejection` rule.
 */
function commentSpans(source) {
  const spans = [];
  const pattern = /\/\*[\s\S]*?\*\/|\/\/[^\n]*/g;
  let match;
  while ((match = pattern.exec(source)) !== null) {
    spans.push([match.index, match.index + match[0].length]);
  }
  return spans;
}

function insideComment(spans, index) {
  return spans.some(([start, end]) => index >= start && index < end);
}

/**
 * The line immediately above `index`, or `''` at the top of the file. Read from
 * the offset rather than from an array so that a declaration split across lines
 * still anchors on the property — the line the author is looking at, and the
 * line a comment would sit above.
 *
 * Batch 881 also returns the line's own number, so an exemption can be tracked
 * by where it was written rather than by how many times it happened to match.
 */
function lineAbove(text, index) {
  const before = text.slice(0, index);
  const lastBreak = before.lastIndexOf('\n');
  const previousBreak = before.lastIndexOf('\n', lastBreak - 1);
  return {
    text: before.slice(previousBreak + 1, lastBreak).trim(),
    line: text.slice(0, previousBreak + 1).split('\n').length,
  };
}

const ALLOW_CENTER = /^\/\*\s*alignment-policy:\s*allow-center\s+--\s+\S.+\*\/$/;

/**
 * `\s*` between the property and its value, so a declaration split over two
 * lines is one declaration again. The `i` flag is not optional: CSS keywords
 * are ASCII case-insensitive, and a policy that can be written out of the gate
 * by typing `CENTER` is not a policy.
 */
const TEXT_ALIGN = /text-align\s*:\s*([a-z]+)/gi;

const INLINE_TEXT_ALIGN = /textAlign\s*:\s*['"]([a-z]+)['"]/gi;

for (const path of walk(sourceRoot)) {
  const source = readFileSync(path, 'utf8');
  const spans = commentSpans(source);

  // Batch 881. Every `allow-center` comment written in this file, so the ones no
  // centre ever claimed can be reported below.
  const exemptions = new Set();
  for (const [start] of spans) {
    if (ALLOW_CENTER.test(lineTextAt(source, start))) exemptions.add(lineAt(source, start));
  }
  const consumed = new Set();

  TEXT_ALIGN.lastIndex = 0;
  let match;
  while ((match = TEXT_ALIGN.exec(source)) !== null) {
    if (insideComment(spans, match.index)) continue;
    // Batch 881. `--text-align: center` is a *custom property definition*, not
    // an alignment declaration, and the regex has no way to tell them apart
    // because it only looks for the substring. In a project whose whole point
    // is a token layer, a token literally named for this property is the
    // obvious next thing to add, and the only way out of the resulting report
    // would be an `allow-center` comment justifying a token as a centring
    // decision. Checked as a character rather than a lookbehind so the rule
    // reads the way the rest of this scan does.
    if (source[match.index - 1] === '-') continue;
    const value = match[1].toLowerCase();
    const line = lineAt(source, match.index);
    if (value === 'center') {
      const above = lineAbove(source, match.index);
      if (ALLOW_CENTER.test(above.text)) {
        consumed.add(above.line);
        intentionalCenterCount += 1;
      } else {
        addViolation(
          path,
          line,
          'text-align:center requires an immediately preceding alignment-policy allow-center comment',
        );
      }
    } else if (value === 'left' || value === 'right') {
      addViolation(path, line, `text-align:${value} is not allowed; use logical start/end`);
    }
    // `start`, `end` and anything the gate does not legislate is left alone:
    // `justify` is direction-neutral, so banning it would be a rule about
    // typography rather than about the thing this gate exists for.
  }

  // Batch 881. An exemption nothing consumed is the same rot `check-hardcoded-copy`
  // had in its allowlist: the centre is gone, the justification is not, and the
  // next `text-align: center` written under it inherits a reason written for a
  // declaration that no longer exists.
  for (const line of [...exemptions].filter(l => !consumed.has(l)).sort((a, b) => a - b)) {
    addViolation(
      path,
      line,
      'this allow-center comment exempts nothing: the text-align:center it justified is gone. '
      + 'Delete the comment, or restore the declaration it was written for.',
    );
  }

  INLINE_TEXT_ALIGN.lastIndex = 0;
  while ((match = INLINE_TEXT_ALIGN.exec(source)) !== null) {
    if (insideComment(spans, match.index)) continue;
    addViolation(
      path,
      lineAt(source, match.index),
      `inline textAlign:${match[1].toLowerCase()} is not allowed; use a CSS Module class`,
    );
  }
}

const mainSource = readFileSync(join(sourceRoot, 'main.tsx'), 'utf8');
const appSource = readFileSync(join(sourceRoot, 'App.tsx'), 'utf8');
const globalSource = readFileSync(join(sourceRoot, 'styles/global.css'), 'utf8');

if (!mainSource.includes("import './styles/global.css';")) {
  violations.push('src/main.tsx must import ./styles/global.css as the canonical global stylesheet');
}
if (mainSource.includes("import './index.css';")) {
  violations.push('src/main.tsx must not import the removed Vite template stylesheet');
}
if (appSource.includes("import './styles/global.css';")) {
  violations.push('src/App.tsx must not import the global stylesheet a second time');
}
if (!/#root\s*\{[^}]*text-align\s*:\s*start\b/s.test(globalSource)) {
  violations.push('src/styles/global.css must define #root { text-align: start; }');
}
if (existsSync(join(sourceRoot, 'index.css'))) {
  violations.push('src/index.css is a removed Vite template stylesheet and must not return');
}
if (existsSync(join(sourceRoot, 'App.css'))) {
  violations.push('src/App.css is an unused Vite template stylesheet and must not return');
}

if (violations.length > 0) {
  console.error('Alignment policy violations:');
  for (const violation of violations) console.error(`- ${violation}`);
  process.exitCode = 1;
} else {
  console.log(`Alignment policy passed; intentional text centers: ${intentionalCenterCount}`);
}
