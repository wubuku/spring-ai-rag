#!/usr/bin/env node
/**
 * Query-failure visibility gate.
 *
 * The read-side twin of `check-mutation-errors.mjs`. A failed read that is
 * never surfaced does not merely look empty — frequently it looks like data.
 *
 * Batch 797 surveyed all 37 `useQuery` calls in `src/` and found 21 whose
 * failure was invisible, in two distinct shapes.
 *
 * Named form — `const readinessQ = useQuery(…)`:
 *
 *   Embeddings.tsx  readinessQ, derivationQ, detailQ
 *                   Each rendered `{q.data && …}`, so a failed request simply
 *                   removed the whole panel. "The server could not tell you the
 *                   readiness of your embeddings" and "you have nothing to
 *                   embed" are different sentences, and the page could only
 *                   ever produce the second one.
 *
 *   Evaluation.tsx  reportQ
 *                   Rendered `isPending ? loading : cards`, and the cards read
 *                   from `data ?? {}`. A failed report therefore rendered a
 *                   full, normal-looking report made entirely of "—". That is
 *                   worse than a blank panel: it looks measured.
 *                   historyQ likewise said "no history" when the request had
 *                   failed. feedbackStatsQ, feedbackHistoryQ and runQ vanished
 *                   silently.
 *
 *   Metrics.tsx     metricsQuery
 *                   Fell through to an EmptyState reading "no metrics", i.e.
 *                   it reported a failed request as an absence of data. The
 *                   `usageQuery` rendered directly below it already handled
 *                   `isError` correctly, so one page contradicted itself.
 *
 * Destructured form — `const { data, isPending } = useQuery(…)`. The first
 * version of this gate matched only the named form and therefore checked 17 of
 * 37 queries while reporting full coverage. That blind spot was the batch's
 * larger finding: every one of these turns a failure into a confident
 * misreport, and two of them report a *negative*.
 *
 *   Alerts.tsx      listActive, listSloConfigs, listSilenceSchedules
 *                   All three rendered `!data?.data?.length ? <EmptyState>`.
 *                   On the active-alerts tab that reads "No active alerts" —
 *                   an alerting page telling an operator that nothing is on
 *                   fire when it could not reach the server. Its own
 *                   AlertDetail already did this correctly, 100 lines below.
 *
 *   ABTest.tsx      ExperimentDetail's `exp`
 *                   `if (!exp) return <EmptyState>Not found</EmptyState>` —
 *                   a network failure reported as "this experiment does not
 *                   exist". The analysis query vanished without a word.
 *
 *   Search.tsx      The search request itself
 *                   `isPending` goes false, `data` stays undefined, so nothing
 *                   renders below the form. The user cannot tell a failed
 *                   search from one still running.
 *
 *   ReembedAllButton.tsx
 *                   `if (isLoading || !status)` — after retries are exhausted
 *                   `isLoading` is false and `status` is still undefined, so
 *                   the block sat on a permanent skeleton: neither the count
 *                   nor any explanation.
 *
 *   Chat.tsx        Model list
 *                   `availableModels` collapses to `[]`, silently disabling
 *                   the model `<select>` with no stated reason.
 *
 *   Collections.tsx / Documents.tsx / Files.tsx
 *                   Collection lists feeding scope selectors; failure yields
 *                   an empty selector that reads as "no collections exist".
 *
 * Rules:
 *   1. silent-query          a query whose failure is neither handled by an
 *                            `onError` option nor surfaced in the render
 *   2. empty-panel-on-error  a `{q.data && <section>}` guard with no error
 *                            branch — the specific shape that turned a failed
 *                            request into "there is nothing here". Batch 869
 *                            widened the guard recognition to `q?.data && …`
 *                            and `q.data ? … : …`, which are the same defect.
 *                            They were already reported by rule 1, so only the
 *                            kind changed, not the count.
 *
 * The named form is checked file-scoped, like the mutation gate: a component
 * that hands a query to a child is a shape this checker cannot follow, and a
 * rule that cries wolf gets ignored. The destructured form is checked
 * per-binding, which is strictly more precise, because the binding is the only
 * thing that can legally hold the error.
 *
 * ## `query-error-allow` is a note, not a switch — on purpose
 *
 * This gate and `check-mutation-errors` record the comment and still fail;
 * `check-alignment-policy` and `check-double-submit` let it exempt. Batch 868
 * fixed the double-submit one because its self-test *claimed* to accept an
 * exemption while never checking for one. This gate's self-test makes no such
 * claim — it is named "carries a recorded exemption as context without granting
 * a pass" and says why: "a comment that silences a check is a comment anyone
 * can write".
 *
 * So Batch 869 left the behaviour alone and only corrected the error message,
 * which had been selling the comment as one of three remedies. Changing this
 * gate to exempt would be overriding a documented decision, not fixing a bug —
 * which makes it a policy call for a human, not a line to quietly edit. The
 * repo holds both positions deliberately; see the ledger entry.
 *
 * Run: node scripts/check-query-errors.mjs
 */

import { readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripComments } from './check-design-system.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');

export const VIOLATION_KINDS = Object.freeze(['silent-query', 'empty-panel-on-error']);

/** `const reportQ = useQuery(` */
const QUERY_DECL = /const\s+([A-Za-z_$][\w$]*)\s*=\s*useQuery\s*\(/g;

/** `const { data, isPending } = useQuery(` */
const DESTRUCTURED_DECL = /const\s*\{([^}]*)\}\s*=\s*useQuery\s*\(/g;

/** The two bindings react-query exposes for a failure, by source name. */
const ERROR_BINDINGS = new Set(['isError', 'error']);

const ALLOW_COMMENT = /query-error-allow:\s*(.+?)\s*(?:\*\/)?$/;

/**
 * The options text of a `useQuery(` call: from its `(` to the matching `)`.
 *
 * Quote-aware, and it has to be. The first version counted parentheses blindly,
 * so a `)` inside a string literal in the options — a composite key like
 * `['r)']`, a URL, a filter expression — ended the slice early and every
 * `onError` written after it became invisible. The result was a **false
 * positive on correct code**: a component that passes `onError` was reported as
 * `empty-panel-on-error`. A probe isolated it — the same component, with and
 * without a paren in one string, differing only in whether `onError` sat after
 * that string, went green and red respectively.
 *
 * `stripComments` already tracks quote state for exactly this reason; this does
 * the same, including the backslash escape. Template literals are skipped whole,
 * which is fine here: a paren inside `${…}` must not move the boundary either.
 */
function optionsOf(code, openParenIndex) {
  let depth = 1;
  let i = openParenIndex + 1;
  let quote = null;
  while (i < code.length && depth > 0) {
    const ch = code[i];
    if (quote !== null) {
      if (ch === '\\') {
        i += 2;
        continue;
      }
      if (ch === quote) quote = null;
      i += 1;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') {
      quote = ch;
      i += 1;
      continue;
    }
    if (ch === '(') depth += 1;
    else if (ch === ')') depth -= 1;
    i += 1;
  }
  return code.slice(openParenIndex, i);
}

function lineOf(code, index) {
  return code.slice(0, index).split('\n').length;
}

export function scanSource(relativePath, source) {
  const violations = [];
  const code = stripComments(source);
  const rawLines = source.split('\n');
  const allowFor = line => {
    const above = ALLOW_COMMENT.exec(rawLines[line - 2] ?? '');
    const here = ALLOW_COMMENT.exec(rawLines[line - 1] ?? '');
    return (above ?? here)?.[1];
  };

  // ── Named form ────────────────────────────────────────────────────────────
  QUERY_DECL.lastIndex = 0;
  const named = [];
  let match;
  while ((match = QUERY_DECL.exec(code)) !== null) {
    const name = match[1];
    const openParen = code.indexOf('(', match.index + match[0].length - 1);
    named.push({
      name,
      line: lineOf(code, match.index),
      hasOnError: /\bonError\s*:/.test(optionsOf(code, openParen)),
    });
  }

  // Rule 2 first. It is matched independently of rule 1 so that a component
  // cannot be excused for having an error banner somewhere else in the file.
  for (const { name, line, hasOnError } of named) {
    // `q.data && …`, `q?.data && …` and `q.data ? … : …` are one shape: the
    // section disappears when the request fails. Batch 869 added the latter two.
    // They were already reported — by rule 1, as `silent-query` — so this only
    // corrects the kind a developer files the issue under. The number of
    // reported components does not change.
    const guarded = new RegExp(
      `\\{\\s*${name}\\s*(?:\\?\\s*)?\\.\\s*data\\s*(?:&&|\\?)`,
    ).test(code);
    if (!guarded || hasOnError) continue;
    if (new RegExp(`\\b${name}\\s*\\.\\s*isError\\b`).test(code)) continue;

    violations.push({
      kind: 'empty-panel-on-error',
      file: relativePath,
      line,
      message: `${name} is rendered as \`${name}.data && …\`, so a failed request is indistinguishable from an empty result`,
      detail: allowFor(line),
    });
  }

  // Rule 1: no error surface at all, whatever the render shape.
  for (const { name, line, hasOnError } of named) {
    if (hasOnError) continue;
    if (new RegExp(`\\b${name}\\s*\\.\\s*isError\\b`).test(code)) continue;
    if (violations.some(v => v.file === relativePath && v.line === line)) continue;

    violations.push({
      kind: 'silent-query',
      file: relativePath,
      line,
      message: `${name} has no onError and nothing in this file renders ${name}.isError`,
      detail: allowFor(line),
    });
  }

  // ── Destructured form ─────────────────────────────────────────────────────
  // Scoped to the binding, not the file: `error` in a destructure is the only
  // way this query's own failure can be read, and a sibling query's `error`
  // says nothing about this one.
  DESTRUCTURED_DECL.lastIndex = 0;
  while ((match = DESTRUCTURED_DECL.exec(code)) !== null) {
    const line = lineOf(code, match.index);
    const openParen = code.indexOf('(', match.index + match[0].length - 1);
    if (/\bonError\s*:/.test(optionsOf(code, openParen))) continue;

    // Bindings are `source` or `source: local`; only the local name can be
    // rendered, so that is what has to be checked for use.
    const bindings = match[1]
      .split(',')
      .map(entry => entry.split(':').map(part => part.trim()))
      .filter(parts => parts[0].length > 0)
      .map(([sourceName, localName]) => ({ source: sourceName, local: localName ?? sourceName }));
    const errorBindings = bindings.filter(binding => ERROR_BINDINGS.has(binding.source));
    if (
      errorBindings.length > 0
      && errorBindings.every(binding =>
        isReadAfterDeclaration(code, match.index + match[0].length, binding.local))
    ) continue;

    const names = bindings.map(binding => binding.local);
    // A declared-but-unread error binding is the same defect as no binding at
    // all, and it is the easy one to write by accident: deleting the error
    // banner leaves the destructuring intact, so a gate that only asks "is it
    // bound?" reports full coverage over a component that is as silent as it
    // was before. Mutation testing caught exactly that in Batch 797.
    const declaredOnly = errorBindings.length > 0;

    violations.push({
      kind: 'silent-query',
      file: relativePath,
      line,
      message: declaredOnly
        ? `a destructured useQuery binds [${names.join(', ')}], but ` +
          `${errorBindings.map(binding => binding.local).join(', ')} ` +
          'is never read, so this read still has no failure surface'
        : `a destructured useQuery binds [${names.join(', ')}] and none of them is ` +
          '`isError` or `error`, so this read has no failure surface',
      detail: allowFor(line),
    });
  }

  return violations;
}

/**
 * Whether a destructured error binding is actually *read* after its
 * declaration.
 *
 * Three refinements, each earning its place by killing a real false pass:
 *
 * 1. **After the declaration.** Deleting an error banner leaves the
 *    destructuring intact, so a gate that only asks "is it bound?" reports
 *    full coverage over a component as silent as it was before.
 * 2. **Sibling destructures do not count as uses.** A file may hold several
 *    sub-components that each destructure `isError` — `Alerts.tsx` has three.
 *    Stripping the other declarations out of the region searched stops a
 *    sibling's own destructuring from vouching for this one.
 * 3. **Comments do not count.** The scan runs on comment-stripped source, so a
 *    banner that is only commented out is not a use.
 *
 * **What this still cannot do, and is registered rather than papered over:**
 * it cannot tell which sub-component a use belongs to. If one sibling renders
 * its error and another does not, the name still occurs in the region and this
 * passes. Separating them needs scope analysis, and a scope analyzer that
 * guesses wrong emits false alarms — the one failure mode a gate must not
 * have. `scripts/__tests__/query-errors.test.mjs` pins the case, and this
 * batch's behavioural tests cover the last mile.
 */
function isReadAfterDeclaration(code, declarationEnd, local) {
  const region = code
    .slice(declarationEnd)
    .replace(/const\s*\{[^}]*\}\s*=\s*useQuery\s*\((?:[^()]|\([^()]*\))*\)/g, ' ');
  return new RegExp(`\\b${local}\\b`).test(region);
}

export function walk(dir, acc = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) walk(path, acc);
    else if (/\.tsx?$/.test(entry.name) && !/\.(test|spec)\.tsx?$/.test(entry.name)) acc.push(path);
  }
  return acc;
}

function main() {
  const files = walk(sourceRoot);
  const violations = files.flatMap(path =>
    scanSource(relative(projectRoot, path), readFileSync(path, 'utf8')),
  );

  if (violations.length > 0) {
    console.error('Query failures that would go unseen:');
    for (const v of violations) {
      console.error(`- ${v.file}:${v.line} [${v.kind}] ${v.message}${v.detail ? ` [allowed: ${v.detail}]` : ''}`);
    }
    console.error(
      '\nA failed read that is never surfaced does not look empty — it often looks\n' +
        'like data, and on an alerting or not-found surface it looks like a fact.\n' +
        'Render `<name>.isError` or bind `isError`/`error` from the hook (an alert\n' +
        'is enough), or pass an `onError`.\n' +
        '\n' +
        'A `query-error-allow` comment is NOT a pass here. It records your reason\n' +
        'and prints it after the finding for the human who reviews it; the\n' +
        'finding still fails the gate. A comment that silences a check is a\n' +
        'comment anyone can write, so this gate takes the other half of that\n' +
        'trade: the reason is evidence, not a switch.\n' +
        '\n' +
        '(`check-alignment-policy` and `check-double-submit` do let a comment\n' +
        'exempt, and both require a written reason after a `--` separator. The\n' +
        'repo holds both positions on purpose; changing this one is a policy\n' +
        'call, not a bug fix.)',
    );
    process.exitCode = 1;
    return;
  }
  console.log(
    `Query-failure policy passed; ${files.length} component file(s) scanned, every read ` +
      'reports its failure instead of looking empty.',
  );
}

if (process.argv[1] && import.meta.url === `file://${resolve(process.argv[1])}`) {
  main();
}
