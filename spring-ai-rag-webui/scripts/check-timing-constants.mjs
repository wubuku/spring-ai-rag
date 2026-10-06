#!/usr/bin/env node
/**
 * Timing gate: a duration at one of the four positions where one appears is a name.
 *
 * Why this exists
 * ---------------
 * Batch 941 counted every duration in `src/` written as a bare number: **thirteen, across
 * four unrelated decisions** — one HTTP request timeout, six react-query poll intervals,
 * five staleness windows, and one "saved" confirmation that fades. Eleven of the thirteen
 * were `30 000` and one was `10 000`, so a reader who greps for `30` cannot tell which of
 * the four decisions they are looking at; and two sites spelled it `30000` instead of
 * `30_000`, so a text search found only half of them.
 *
 * The cost was not the duplication as such — four numbers would have been fine. It was
 * that **four independent decisions were indistinguishable in the source**. `api/client`
 * `timeout: 30_000` is how long a request may hang; `refetchInterval: 30_000` is how
 * stale a dashboard may look. Nothing connects them, and a shared constant would have
 * hidden that rather than revealed it.
 *
 * What was already right is the precedent: `TOAST_AUTO_DISMISS_MS`, `revokeDelayMs`,
 * `MAX_RETRY_WAIT_MS` and `DEFAULT_COMMIT_DELAY_MS` were all named by whoever wrote them.
 * Four of the thirteen simply never got the treatment, and `src/utils/timing.ts` now holds
 * the other four with each one's reasoning attached.
 *
 * What the rule is
 * ---------------
 * At the four positions where a duration appears in this codebase, the value must be an
 * identifier. Everywhere else a number is still a number — `formatDecimal(n, locale, 8)`,
 * an array bound, a port — because this rule is about **a delay somebody chose**, not
 * about numbers.
 *
 * The `setTimeout` half is a small balanced-paren scan, not a regular expression, and the
 * reason is worth recording **because the first version of this rule got it wrong in a
 * way that would have been easy to leave in**.
 *
 * The census that found the thirteen wrote
 * `/set(Timeout|Interval)\([^,)]+,\s*[0-9]/` and reported **zero** durations — while
 * `Settings.tsx` held `setTimeout(() => setSaved(false), 2000)`. The character class is
 * what failed: `[^,)]+` cannot cross the `)` in `setSaved(false)`, so the match never
 * reached the comma. A rule that matches nothing is indistinguishable from a clean file.
 *
 * The obvious repair — dropping the `)` from the class, giving
 * `/setTimeout\([^,]+,\s*\d+/` — **would** have caught that line, and it is worth saying
 * so rather than claiming the naive form was hopeless: there is no comma inside that
 * callback. It is still the wrong tool, because it cannot express an argument list whose
 * first argument legitimately contains a comma (`setTimeout(f(a, b), 2000)`), and no
 * amount of editing the character class fixes that without eventually rewriting the
 * regex into a parser. So this file counts parentheses: the answer does not depend on
 * what the callback happens to contain.
 *
 * Stated limit of that choice: a call written across several lines is out of scope rather
 * than guessed at, because guessing where an argument list ends is how a rule starts
 * reporting the wrong line.
 *
 * Known limits, pinned by the self-test:
 *   - **Only those four positions.** A delay computed in a variable (`delayMs`), a
 *     `setInterval`, a CSS `transition-duration` and a backend timeout are not covered.
 *   - **Test files are out of scope**, so a fixture may write `2000` to stay readable.
 *   - Comments are stripped first: this header names all four numbers and every one of
 *     them in the tree.
 *   - It judges the shape of the position, not whether the value behind the name is
 *     sensible. That is what `src/utils/timing.ts` is for.
 *
 * Run:
 *   node scripts/check-timing-constants.mjs
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripTsxComments } from './lib/tsx-source.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

const webuiRoot = fileURLToPath(new URL('..', import.meta.url));

export const VIOLATION_KINDS = Object.freeze({
  BARE_DURATION: 'bare-duration',
});

/** Options whose value is a duration chosen by somebody: `key: 30_000`. */
export const DURATION_OPTIONS = Object.freeze([
  'refetchInterval',
  'staleTime',
  'timeout',
]);

/** Calls whose **last** argument is a delay. */
export const DELAY_CALLS = Object.freeze(['setTimeout', 'setInterval']);

/** A bare integer literal, possibly with digit separators. */
const BARE_INTEGER = /^\d[\d_]*$/u;

/** `key:` or bare `setTimeout(` / `setInterval(` at a word boundary. */
const OPTION = /\b(refetchInterval|staleTime|timeout)\s*:/gu;
const DELAY_CALL = /\b(setTimeout|setInterval)\s*\(/gu;

/**
 * Split one argument list on commas that are not nested inside brackets or quotes.
 *
 * `openIndex` is the index of the opening parenthesis itself, so a caller can pass
 * `line.indexOf('(')` without having to remember to add one. Returns the raw argument
 * texts, or `null` when the list never closes on this line — a multi-line call is out of
 * scope rather than guessed at, because guessing is how a rule starts reporting the wrong
 * argument.
 */
export function splitArguments(source, openIndex) {
  const args = [];
  let depth = 0;
  let quote = null;
  let current = '';
  for (let i = openIndex + 1; i < source.length; i += 1) {
    const ch = source[i];
    if (quote) {
      current += ch;
      if (ch === '\\') { current += source[i + 1] ?? ''; i += 1; }
      else if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') { quote = ch; current += ch; continue; }
    if (ch === '(' || ch === '[' || ch === '{') depth += 1;
    if (ch === ')' || ch === ']' || ch === '}') {
      if (depth === 0) {
        if (current.trim() !== '') args.push(current.trim());
        return args;
      }
      depth -= 1;
    }
    if (ch === ',' && depth === 0) { args.push(current.trim()); current = ''; continue; }
    current += ch;
  }
  return null;
}

/** The whole check as a pure function: `[{path, text}]` → findings. */
export function findBareDurations(files) {
  const findings = [];
  for (const file of files) {
    const source = stripTsxComments(file.text);
    source.split('\n').forEach((line, index) => {
      const report = text => findings.push({
        kind: VIOLATION_KINDS.BARE_DURATION,
        path: file.path,
        line: index + 1,
        text: line.trim(),
        detail: text,
      });

      OPTION.lastIndex = 0;
      let match = OPTION.exec(line);
      while (match !== null) {
        const value = line.slice(match.index + match[0].length)
          .split(',')[0].trim();
        if (BARE_INTEGER.test(value)) report(`${match[1]}: ${value}`);
        match = OPTION.exec(line);
      }

      DELAY_CALL.lastIndex = 0;
      let call = DELAY_CALL.exec(line);
      while (call !== null) {
        const open = line.indexOf('(', call.index);
        const parsed = open < 0 ? null : splitArguments(line, open);
        if (parsed !== null && parsed.length >= 2
            && BARE_INTEGER.test(parsed[parsed.length - 1])) {
          report(`${call[1]}: ${parsed[parsed.length - 1]}`);
        }
        call = DELAY_CALL.exec(line);
      }
    });
  }
  return findings;
}

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

function main() {
  const srcDir = join(webuiRoot, 'src');
  const files = walkSources(srcDir).map(rel => ({
    path: `src/${rel}`,
    text: readFileSync(join(srcDir, rel), 'utf8'),
  }));

  const findings = findBareDurations(files);

  if (findings.length > 0) {
    console.error(`${findings.length} duration(s) written as a bare number:`);
    for (const f of findings) {
      console.error(`- [${f.kind}] ${f.path}:${f.line} — ${f.text}  (${f.detail})`);
    }
    console.error(
      '\nHTTP_TIMEOUT_MS, POLL_INTERVAL_MS, STALE_TIME_MS,'
      + ' DOCUMENT_LIST_STALE_TIME_MS and SAVED_FEEDBACK_MS live in src/utils/timing.ts,'
      + ' each with the reasoning for its own value. They are deliberately four'
      + ' decisions rather than one constant: they share 30 000 by coincidence, and a'
      + ' shared name would hide that.',
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `Timing check passed; ${files.length} source file(s), every duration at a`
    + ' refetchInterval / staleTime / timeout / delay position is a named decision.',
  );
}

if (isMainModule(import.meta.url)) {
  main();
}
