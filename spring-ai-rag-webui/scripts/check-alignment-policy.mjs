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
 * Run: node scripts/check-alignment-policy.mjs
 */

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');
const violations = [];
let intentionalCenterCount = 0;

function walk(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return walk(path);
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
 */
function lineAbove(text, index) {
  const before = text.slice(0, index);
  const lastBreak = before.lastIndexOf('\n');
  const previousBreak = before.lastIndexOf('\n', lastBreak - 1);
  return before.slice(previousBreak + 1, lastBreak).trim();
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

  TEXT_ALIGN.lastIndex = 0;
  let match;
  while ((match = TEXT_ALIGN.exec(source)) !== null) {
    if (insideComment(spans, match.index)) continue;
    const value = match[1].toLowerCase();
    const line = lineAt(source, match.index);
    if (value === 'center') {
      if (ALLOW_CENTER.test(lineAbove(source, match.index))) {
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
