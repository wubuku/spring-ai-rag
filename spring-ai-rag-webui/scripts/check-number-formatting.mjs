#!/usr/bin/env node
/**
 * Number gate: `Intl.NumberFormat` is constructed in one module, and built once there.
 *
 * Why this exists
 * ---------------
 * Batch 940 surveyed how `src/` formats a number for a person and found
 * `Intl.NumberFormat` in exactly one file — `Metrics.tsx` — in two constructions. One of
 * the two was called from inside a `.map()` over table rows, so **every cell of the usage
 * table built its own formatter on every render**. Measured on the machine that ran the
 * census, 20 000 calls with identical output:
 *
 *     new Intl.NumberFormat()    348.7 ms
 *     a reused formatter           4.9 ms      71x
 *
 * Construction is the expensive half of `Intl`, not `format`. So this is not a
 * micro-optimisation to be argued about; it is a table that is cheap to repaint or not.
 *
 * ## The second thing the rule settles
 *
 * A number the server did not send has to render as something, and the app's answer
 * everywhere else is `—`: `Dashboard`'s `Metric` renders `value ?? '—'`, `Documents`
 * renders `doc.collectionName ?? '—'`, `ABTest` uses `'—'`. `Metrics.formatInteger` was
 * the exception in two directions at once — `'0'` for an absent value, which is a *claim*
 * that the count was zero, and the raw wire text for an unreadable one, which is what
 * Batch 938 caught in `ApiKeys.formatDateTime` when `toLocaleString()` on an Invalid Date
 * put the string `"Invalid Date"` on a Chinese page. `src/utils/number.ts` renders `—`
 * for both.
 *
 * What the rule is
 * ---------------
 * "`Intl` lives in `src/utils/number.ts`." The module builds its formatters once per
 * locale and option set; a call site formats a value. No allowlist, because there is
 * exactly one legitimate owner and it is named by path.
 *
 * Known limits, pinned by the self-test:
 *   - **Only `Intl.NumberFormat` is matched.** `Number.prototype.toLocaleString()` is a
 *     second way to do the same thing and it is not covered — there is no instance of it
 *     on a number anywhere in `src/`, but the rule is about the shape it recognises.
 *   - **It cannot see the cost.** A caller that formats correctly but through its own
 *     `Intl.NumberFormat` constructor in another file is exactly what it does catch; a
 *     caller that never formats at all is not. The 71x figure is pinned by counting
 *     constructions in `number.test.ts`, not here.
 *   - It says nothing about whether a *count* should be grouped. That is a product
 *     judgement — `CreateCollectionModal`'s `{description.length}/500` counter
 *     deliberately stays ungrouped because a 500 ceiling never reaches four digits.
 *   - Comments are stripped first; this file names `Intl.NumberFormat` in every sentence.
 *
 * Run:
 *   node scripts/check-number-formatting.mjs
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripTsxComments } from './lib/tsx-source.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

const webuiRoot = fileURLToPath(new URL('..', import.meta.url));

/** The one module allowed to construct a number formatter. */
export const OWNER = 'src/utils/number.ts';

export const VIOLATION_KINDS = Object.freeze({
  INTL_OUTSIDE_OWNER: 'intl-outside-owner',
});

/** `Intl.NumberFormat`, with or without `Number` on the namespace. */
const INTL = /\b(?:Intl\s*\.\s*NumberFormat|new\s+Intl\b)/gu;

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

/** The whole check as a pure function: `[{path, text}]` → findings. */
export function findIntlOutsideOwner(files) {
  const findings = [];
  for (const file of files) {
    if (file.path === OWNER) continue;
    const source = stripTsxComments(file.text);
    source.split('\n').forEach((line, index) => {
      INTL.lastIndex = 0;
      if (!INTL.test(line)) return;
      findings.push({
        kind: VIOLATION_KINDS.INTL_OUTSIDE_OWNER,
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

  const findings = findIntlOutsideOwner(files);

  if (findings.length > 0) {
    console.error(`${findings.length} construction(s) of a number formatter outside ${OWNER}:`);
    for (const f of findings) {
      console.error(`- [${f.kind}] ${f.path}:${f.line} — ${f.text}`);
    }
    console.error(
      '\nUse formatCount() / formatDecimal() from'
      + ` ${OWNER}. It builds each Intl.NumberFormat once per locale and option set —`
      + ' construction, not format, is the expensive half, and 20 000 calls measured'
      + ' 348.7 ms against 4.9 ms for a reused one.',
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `Number check passed; ${files.length} source file(s), every number formatted through`
    + ` ${OWNER}.`,
  );
}

if (isMainModule(import.meta.url)) {
  main();
}
