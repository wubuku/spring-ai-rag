#!/usr/bin/env node
/**
 * Truncation gate: a hard-coded two-number substring appears nowhere in `src/`.
 *
 * Why this exists
 * ---------------
 * Batch 939 counted every `slice` / `substring` / `substr` in `src/` whose two arguments
 * were numeric literals. There were **fifteen**, and behind them exactly **four** numbers:
 *
 *   | number | count | meaning                                   | spelling on screen    |
 *   |--------|-------|-------------------------------------------|-----------------------|
 *   | `256`  | 9     | the longest search query the UI accepts   | n/a — sent as `q=`    |
 *   | `8`    | 3     | a short id or content hash for display    | three different ways |
 *   | `50`   | 1     | the chat sidebar's title preview          | `'...'`               |
 *   | `16`   | 1     | the `datetime-local` input's own shape    | n/a — a form field   |
 *
 * The same three display decisions were spelled three ways. `VersionHistoryModal` wrote a
 * real `…` (U+2026), `Documents` wrote three ASCII dots, `Chat` wrote three ASCII dots
 * inside a ternary that re-implemented the length check by hand, and `Embeddings` cut a
 * job id with no marker at all — so a reader could not tell a truncated value from a
 * short one.
 *
 * The fourth number was not a truncation. `ApiKeys` cut a wire timestamp to 16 characters
 * to fill a `<input type="datetime-local">`, which is a **format conversion** — and it now
 * lives in `src/utils/time.ts` as `toDateTimeLocalValue`, next to the other timestamp
 * handling, for the same reason.
 *
 * ## Why this rule needs no allowlist at all
 *
 * The obvious design exempts `utils/text.ts`, which is where the numbers now live. It does
 * not have to: `truncate` and `capLength` both cut at a *named* value, so the tree is now
 * at **zero** numeric literal substrings and the rule has no owner to exempt. An earlier
 * draft of this gate reserved an exception for `utils/time.ts` on the assumption that its
 * `16` would stay a literal; naming the constant `DATE_TIME_LOCAL_LENGTH` made the
 * exception unnecessary, and an exception that exists only because nobody finished the job
 * is one more thing to keep true.
 *
 * What the rule is not
 * --------------------
 * It decides by the **shape of the arguments**, not by intent. A display truncation is
 * now made by `truncate()` and a typed-in cap by `capLength()`, and the two are not
 * interchangeable — an ellipsis in a search box becomes part of the `q=` parameter — but
 * the gate cannot tell a display cut from a cap. It only guarantees that any fixed cut is
 * a named decision somewhere, which is the part that can be checked.
 *
 * Known limits, pinned by the self-test:
 *   - **A computed bound is not caught.** `slice(0, n)` with a variable `n` matches
 *     nothing, and neither does a cut written as a spread, a regex, or CSS
 *     `text-overflow`. The rule is about a number typed into a call.
 *   - Test files are out of scope, so a fixture may cut a string to keep an assertion
 *     readable.
 *   - Comments are stripped first: this file spells out every one of those four numbers.
 *
 * Run:
 *   node scripts/check-text-truncation.mjs
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripTsxComments } from './lib/tsx-source.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

const webuiRoot = fileURLToPath(new URL('..', import.meta.url));

export const VIOLATION_KINDS = Object.freeze({
  NUMERIC_SUBSTRING: 'numeric-substring',
});

/**
 * `slice(0, 8)`, `substring(0, 16)`, `substr(2, 4)` — two integer literals, in order.
 *
 * Written to describe the syntax position it sits in: both arguments are numbers, which
 * is the whole signal. A looser pattern would also match `padStart(0, 8)`-shaped calls or
 * an array index, and a rule that fires on things it does not mean is a rule that gets
 * exempted.
 */
const NUMERIC_CUT = /\.\s*(?:slice|substring|substr)\s*\(\s*\d+\s*,\s*\d+\s*\)/gu;

export function isSourceFile(name) {
  return /\.(tsx|ts)$/u.test(name) && !/\.test\.tsx?$/u.test(name);
}

export function walkSources(dir, prefix = '') {
  const out = [];
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry === 'dist') continue;
    const full = join(dir, entry);
    const rel = prefix === '' ? entry : `${prefix}/${entry}`;
    if (statSync(full).isDirectory()) out.push(...walkSources(full, rel));
    else if (isSourceFile(entry)) out.push(rel);
  }
  return out;
}

/**
 * The whole check as a pure function: `[{path, text}]` → findings.
 *
 * No owner is skipped. That is the finding worth reading twice: the first draft of this
 * gate skipped `utils/time.ts`, and the reason it no longer needs to is that the one
 * number that lived there became a named constant. An exemption list is a list of things
 * that were not finished.
 */
export function findNumericSubstrings(files) {
  const findings = [];
  for (const file of files) {
    const source = stripTsxComments(file.text);
    source.split('\n').forEach((line, index) => {
      NUMERIC_CUT.lastIndex = 0;
      if (!NUMERIC_CUT.test(line)) return;
      findings.push({
        kind: VIOLATION_KINDS.NUMERIC_SUBSTRING,
        path: file.path,
        line: index + 1,
        text: line.trim(),
      });
    });
  }
  return findings;
}

function main() {
  const srcDir = join(webuiRoot, 'src');
  const files = walkSources(srcDir).map(rel => ({
    path: `src/${rel}`,
    text: readFileSync(join(srcDir, rel), 'utf8'),
  }));

  const findings = findNumericSubstrings(files);

  if (findings.length > 0) {
    console.error(`${findings.length} hard-coded substring(s) in ${files.length} file(s):`);
    for (const f of findings) {
      console.error(`- [${f.kind}] ${f.path}:${f.line} — ${f.text}`);
    }
    console.error(
      '\nA value shown to a person is shortened with truncate(); a value being typed is'
      + ' capped with capLength(). Both live in src/utils/text.ts, next to the constants'
      + ' that say what the numbers mean.',
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `Truncation check passed; ${files.length} source file(s), no fixed cut written as two`
    + ' numbers.',
  );
}

if (isMainModule(import.meta.url)) {
  main();
}
