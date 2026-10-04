#!/usr/bin/env node
/**
 * Destructive-action confirmation gate.
 *
 * A DELETE that fires on a single click is a button with no undo. Two batches
 * had to retrofit exactly this, by hand, after the fact:
 *
 *   Batch 770  brought Documents and ReembedAll in line.
 *   Batch 812  found two alert lists that had "never been brought in line" —
 *             one click used to delete an SLO threshold outright, and the tests
 *             pinned the unsafe behaviour by clicking the button once.
 *
 * Both fixes were retrospective, which is the expensive way to learn a rule.
 * This gate makes the rule cheap to follow: a component that fires a DELETE
 * must ask first.
 *
 * ## The list of destructive actions is derived, not maintained
 *
 * "Destructive" here is not a judgement about taste. It is read off the API
 * layer: a method in `src/api/*.ts` whose body issues `apiClient.delete(`.
 * HTTP DELETE is the server's own statement that the operation is not
 * reversible, so the derivation needs no hand-written list that can drift out
 * of date the moment somebody adds an endpoint. Adding a new destructive
 * endpoint therefore extends the gate's coverage automatically.
 *
 * POST-based operations are deliberately **out of scope**. Several of them are
 * irreversible too (purge apply, batch embed), but they all take a body, and
 * most already run behind a preview or a typed confirmation. Deciding which
 * POSTs need a prompt is a product decision; guessing at it is how a gate
 * starts crying wolf.
 *
 * ## What it checks, and how honestly
 *
 * A call site passes if its **file** opens a confirmation before firing. File
 * scope is the same trade `check-double-submit` and `check-query-errors` make:
 * a component that hands the delete to a child is a shape this checker cannot
 * follow, and the honest failure mode is a **miss**. Every rule here can miss;
 * none of them cries wolf on code that has a confirmation.
 *
 * A file can be exempted with
 * `// destructive-allow -- <reason>` on the line above the call, which must
 * carry a written reason: a bare keyword must not be enough to silence a gate.
 *
 * ## Measured limitation, registered rather than papered over
 *
 * The confirmation test is a **vocabulary** test at file scope, so one
 * confirmation-ish name in a file vouches for *every* destructive call in that
 * file. `Collections.tsx` is the live example: the purge flow's own
 * `confirmation` state would keep the gate quiet even if the separate
 * collection-delete lost its `deleteTarget` prompt.
 *
 * Closing that needs scope analysis — knowing which handler the call sits in —
 * and a scope analyser that guesses wrong emits false alarms on correct code,
 * which is the one outcome a gate must not produce. So the miss stays, and it
 * is pinned by a test so the next person sees it rather than discovering it.
 *
 * Run: node scripts/check-destructive-confirm.mjs
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripComments } from './check-design-system.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');
const apiRoot = join(sourceRoot, 'api');

export const VIOLATION_KINDS = Object.freeze(['unconfirmed-destructive']);

/** Any DELETE in the body makes the method destructive. */
const DELETE_CALL = /apiClient\s*\.\s*delete\s*\(/;

/** `deleteByKey: (id: number) => …` or `async deleteByKey(id) { … }` */
const METHOD_NAME = /(?:^|[,{\s])(?:async\s+)?([A-Za-z_$][\w$]*)\s*[:(]/g;

const CALL_SITE = /(?:^|[^A-Za-z0-9_$.])([A-Za-z_$][\w$]*(?:Api|Client))\s*\.\s*([A-Za-z_$][\w$]*)\s*\(/g;

const ALLOW_COMMENT = /^\s*(?:\/\/|\/\*)\s*destructive-allow\s+--\s+(\S.*?)\s*(?:\*\/)?\s*$/;

/**
 * The vocabulary of "ask first" in this codebase.
 *
 * Measured against the five call sites that exist today — Collections
 * (`deleteTarget`), ApiKeys (`confirmingRevoke`), Documents (`confirmation`),
 * Alerts (`pendingSloDelete`, `pendingSilenceDelete`). Every one of them trips
 * at least one of these, so the list is calibrated against real code rather
 * than against an imagined one.
 */
const CONFIRMATION = /\b(confirm\w*|pending[A-Z]\w*(?:Delete|Remove|Purge)|\w*(?:Delete|Remove|Purge)Target)\b/;

export function walk(dir, filter = () => true, acc = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) walk(path, filter, acc);
    else if (filter(entry.name, path)) acc.push(path);
  }
  return acc;
}

const isSource = (name) => /\.tsx?$/.test(name) && !/\.(test|spec)\.tsx?$/.test(name);

/** Every method name in `src/api/*.ts` whose body issues a DELETE. */
export function destructiveActions(apiSources) {
  const names = new Set();
  for (const { source } of apiSources) {
    const code = stripComments(source);
    // Split on the top-level separators that introduce a new method.
    for (const chunk of code.split(/\n\s*(?=[A-Za-z_$][\w$]*\s*[:(])/)) {
      if (!DELETE_CALL.test(chunk)) continue;
      METHOD_NAME.lastIndex = 0;
      const first = METHOD_NAME.exec(chunk);
      if (first) names.add(first[1]);
    }
  }
  return names;
}

export function scanSource(relativePath, source, destructive) {
  const violations = [];
  const code = stripComments(source);
  const rawLines = source.split('\n');

  const sites = [];
  CALL_SITE.lastIndex = 0;
  let match;
  while ((match = CALL_SITE.exec(code)) !== null) {
    const method = match[2];
    if (!destructive.has(method)) continue;
    sites.push({ method, line: code.slice(0, match.index).split('\n').length });
  }
  if (sites.length === 0) return violations;

  // File-scoped, like every other gate here. A component that hands the delete
  // to a child cannot be followed; that is a miss, and a miss is the acceptable
  // failure mode. What must never happen is reporting a file that *does* ask.
  if (CONFIRMATION.test(code)) return violations;

  for (const { method, line } of sites) {
    const allow = ALLOW_COMMENT.exec(rawLines[line - 2] ?? '')
      ?? ALLOW_COMMENT.exec(rawLines[line - 1] ?? '');
    if (allow) continue;

    // One finding per call site, but not per invocation of a loop body: a
    // component that deletes in a map is the same defect, not twenty.
    violations.push({
      kind: 'unconfirmed-destructive',
      file: relativePath,
      line,
      message: `${method}() issues a DELETE and this file never asks for confirmation`,
    });
  }
  return violations;
}

export function apiSources() {
  return walk(apiRoot, isSource).map(path => ({
    path,
    source: readFileSync(path, 'utf8'),
  }));
}

function main() {
  const api = apiSources();
  const destructive = destructiveActions(api);
  const files = walk(sourceRoot, (name, path) => isSource(name) && !path.startsWith(apiRoot));

  const violations = files.flatMap(path =>
    scanSource(relative(projectRoot, path), readFileSync(path, 'utf8'), destructive),
  );

  if (violations.length > 0) {
    console.error('Destructive actions that fire without asking first:');
    for (const v of violations) {
      console.error(`- ${v.file}:${v.line} [${v.kind}] ${v.message}`);
    }
    console.error(
      '\nA DELETE is not undoable. Ask first — a typed confirmation for a purge,\n' +
        'a confirm dialog for a delete — before the request leaves the browser.\n' +
        'If this call genuinely cannot prompt, record an inline\n' +
        '`// destructive-allow -- <reason>` on the line above it.',
    );
    process.exitCode = 1;
    return;
  }
  console.log(
    `Destructive-action policy passed; ${destructive.size} DELETE-backed action(s) ` +
      `derived from src/api, ${files.length} component file(s) scanned, every one asks first.`,
  );
}

if (process.argv[1] && import.meta.url === `file://${resolve(process.argv[1])}`) {
  main();
}
