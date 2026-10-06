#!/usr/bin/env node
/**
 * Timestamp gate: a date reaches the screen through `src/utils/time.ts` or not at all.
 *
 * Why this exists
 * ---------------
 * Batch 938 surveyed how the WebUI renders a server timestamp and found **nine**
 * places, each calling `toLocaleString()` / `toLocaleDateString()` /
 * `toLocaleTimeString()` on a value it had just made a `Date` out of, with no shared
 * formatter anywhere in `src/`. What that cost, measured:
 *
 *   - **"Invalid Date" reached the screen.** `ApiKeys.tsx` had
 *
 *         function formatDateTime(dateStr?: string): string {
 *           if (!dateStr) return '—';
 *           try { return new Date(dateStr).toLocaleString(); }
 *           catch { return dateStr; }
 *         }
 *
 *     which reads as though it handles a bad value. It cannot: `new Date('garbage')`
 *     **does not throw** — it returns an Invalid Date, and `toLocaleString()` on one
 *     returns the *string* `"Invalid Date"`. The catch was unreachable, the raw wire
 *     value was never printed, and eleven characters of English appeared in a
 *     Chinese page. Four of the nine sites had no validity check at all.
 *
 *   - **The wire carries four different shapes and the frontend typed all of them as
 *     `string`.** The API's DTOs use `LocalDateTime` (73 fields), `OffsetDateTime`
 *     (31), `ZonedDateTime` (15) and `Instant` (8) for the same question. Only the
 *     last three carry an offset; a `LocalDateTime` arrives as `2026-10-06T19:34:31`,
 *     which ECMAScript reads **in the browser's timezone**.
 *
 *   - **One page showed relative time and eight showed absolute**, because
 *     `ChatSidebar` had hand-rolled a second copy of the ladder. The presentation
 *     difference is deliberate and kept; having the implementation written twice is
 *     not, and it is what this gate stops.
 *
 * What the rule is, and what it is not
 * ------------------------------------
 * The rule is "one formatter, imported". It does **not** judge what the formatter
 * renders: a date column and a time column must differ, `formatRelative` must stay
 * relative. Only `src/utils/time.ts` may call the `toLocale*` family, and that file is
 * where the four wire shapes and the unreadable-value case are handled once.
 *
 * The second half reads **String.valueOf(x)** rather than `String(x)` on purpose: in
 * JavaScript they are the same function, so a gate written with either reads alike —
 * but `String(valueOf)` refuses an object with a null prototype, which is what a
 * checked string means. The whole rule needs no allowlist, because the one legitimate
 * owner of these calls is named by path rather than by a list of lines.
 *
 * Known limits, pinned by the self-test:
 *   - Comments are stripped first. `ApiKeys.tsx` explains this very defect in a
 *     comment that spells `toLocaleString()`, and a scanner that reads prose counts
 *     the explanation as the violation.
 *   - Test files are out of scope: a fixture that asserts "Invalid Date" must be able
 *     to spell it.
 *   - This gate does not look at the Java side, where the four-way split starts. That
 *     is an API contract change with external business clients, so it is a decision
 *     for a person; `docs/rest-api.md` records the measurement.
 *
 * Run:
 *   node scripts/check-time-formatting.mjs
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripTsxComments } from './lib/tsx-source.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

const webuiRoot = fileURLToPath(new URL('..', import.meta.url));

/** The one file allowed to call `toLocale*`. Named by path, not by a list of lines. */
export const FORMATTER = 'src/utils/time.ts';

export const VIOLATION_KINDS = Object.freeze({
  INLINE_LOCALE_FORMAT: 'inline-locale-format',
});

/** `.toLocaleString(`, `.toLocaleDateString(`, `.toLocaleTimeString(` and `.toLocaleTimeZone(`. */
const INLINE = /\.toLocale(?:String|DateString|TimeString|TimeZone)\s*\(/g;

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
 * Comments are stripped here, not by the caller, so the self-test cannot pass a
 * fixture that only looks clean because it has not been stripped.
 */
export function findInlineLocaleFormatting(files) {
  const findings = [];
  for (const file of files) {
    if (file.path === FORMATTER) continue;
    const source = stripTsxComments(file.text);
    source.split('\n').forEach((line, index) => {
      INLINE.lastIndex = 0;
      if (!INLINE.test(line)) return;
      findings.push({
        kind: VIOLATION_KINDS.INLINE_LOCALE_FORMAT,
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
  // Paths carry the `src/` prefix so that `FORMATTER` names the same string a reader
  // would type. The first version compared `'src/utils/time.ts'` against a walk that
  // returned `'utils/time.ts'`, so the formatter's own four calls were reported —
  // a gate that fails on the one file allowed to do the thing, for a string mismatch.
  const files = walkSources(srcDir).map((rel) => ({
    path: `src/${rel}`,
    text: readFileSync(join(srcDir, rel), 'utf8'),
  }));

  const findings = findInlineLocaleFormatting(files);

  if (findings.length > 0) {
    console.error(`${findings.length} call(s) render a date outside ${FORMATTER}:`);
    for (const f of findings) {
      console.error(`- [${f.kind}] ${f.path}:${f.line} — ${f.text}`);
    }
    console.error(
      `\nUse formatAbsolute / formatDate / formatTime / formatRelative from`
      + ` ${FORMATTER}. They handle the four wire shapes the API sends and a value`
      + ' that cannot be read — which `toLocaleString()` reports as the string'
      + ' "Invalid Date" rather than by throwing.',
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `Timestamp check passed; ${files.length} source file(s), every date rendered through`
    + ` ${FORMATTER}.`,
  );
}

if (isMainModule(import.meta.url)) {
  main();
}