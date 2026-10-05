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
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripComments } from './check-design-system.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

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
 * Batch 875: **no longer used to decide anything.** It is kept as the shape the
 * dialog attribute is matched on, and its file-scoped use is the bug this batch
 * closes — see `destructiveInvocations` for what replaced it. The five call
 * sites that calibrated it are listed here because the calibration is still
 * what tells you a *name* is not evidence: Collections (`deleteTarget`),
 * ApiKeys (`confirmingRevoke`), Documents (`confirmation`), Alerts
 * (`pendingSloDelete`, `pendingSilenceDelete`).
 */
const CONFIRMATION = /\b(confirm\w*|pending[A-Z]\w*(?:Delete|Remove|Purge)|\w*(?:Delete|Remove|Purge)Target)\b/;

/** Index of the closer matching the opener at `open`, or -1. */
function matchPair(src, open, openChar, closeChar) {
  let depth = 0;
  for (let i = open; i < src.length; i += 1) {
    if (src[i] === openChar) depth += 1;
    else if (src[i] === closeChar) {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  return -1;
}

/** The JSX tag name that owns the attribute starting at `at`, or null. */
function enclosingTagName(code, at) {
  const head = code.slice(0, at);
  const lt = head.lastIndexOf('<');
  if (lt < 0) return null;
  const tag = /^<([A-Za-z][\w.]*)/.exec(code.slice(lt));
  return tag ? tag[1] : null;
}

/**
 * Batch 875. Every point at which a destructive action actually *fires*.
 *
 * Two shapes, and both have to be handled — a rule that only understands one of
 * them trades a known blind spot for an unknown one.
 *
 *   Routed: `const removeM = useMutation({ mutationFn: () => api.delete(id) })`
 *     The dangerous line is the declaration; the user's decision is made at
 *     `removeM.mutate()`, somewhere else entirely. All seven invocations in the
 *     tree today sit inside an `onConfirm` handler of a dialog, so this is where
 *     the check belongs.
 *
 *   Direct: `onClick={() => collectionsApi.deleteByKey(key)}`
 *     Nothing to follow — the call *is* the firing point. Skipping this shape
 *     would be a new blind spot in exchange for closing an old one, which is
 *     not a trade worth making.
 */
export function destructiveInvocations(code, destructive) {
  const covered = new Set();
  const mutations = [];

  // Pass 1: which `useMutation` declarations carry a destructive call.
  const decl = /const\s+([A-Za-z_$][\w$]*)\s*=\s*useMutation\s*\(/g;
  let m;
  while ((m = decl.exec(code)) !== null) {
    const varName = m[1];
    const open = code.indexOf('(', m.index + m[0].length - 1);
    const close = matchPair(code, open, '(', ')');
    if (close < 0) continue;
    const body = code.slice(open + 1, close);
    const call = /\b([A-Za-z_$][\w$]*(?:Api|Client))\s*\.\s*([A-Za-z_$][\w$]*)\s*\(/.exec(body);
    // Absolute, because it is compared against CALL_SITE matches below, which
    // are absolute. A body-relative index here silently fails to suppress the
    // duplicate, and the duplicate is reported as an unconfirmed direct call
    // sitting inside a mutationFn — a violation in code that asks first.
    if (call && destructive.has(call[2])) {
      covered.add(open + 1 + call.index);
      mutations.push({ varName, method: call[2], declaredAt: close });
    }
    decl.lastIndex = close;
  }

  // Pass 2: bind each `.mutate()` to the nearest declaration *above* it.
  //
  // Not "the declaration in this file": `Alerts.tsx` has two components that
  // each declare their own `const deleteMutation`, and a whole-file search let
  // each one claim all four call sites. One component's dialog would then vouch
  // for the other's delete — the exact cross-component leak the scoped check
  // exists to close, reintroduced one level up. Nearest-preceding is sound for
  // React: a hook is declared above every use of it in the same component.
  const found = [];
  for (const names of new Set(mutations.map(x => x.varName))) {
    const decls = mutations.filter(x => x.varName === names).sort((a, b) => a.declaredAt - b.declaredAt);
    const uses = new RegExp(`\\b${names}\\s*\\.\\s*mutate\\s*\\(`, 'g');
    let u;
    while ((u = uses.exec(code)) !== null) {
      const owner = [...decls].reverse().find(d => d.declaredAt < u.index);
      if (!owner) continue;
      found.push({
        varName: names,
        method: owner.method,
        index: u.index,
        line: code.slice(0, u.index).split('\n').length,
        shape: 'routed',
      });
    }
  }

  CALL_SITE.lastIndex = 0;
  let direct;
  while ((direct = CALL_SITE.exec(code)) !== null) {
    const method = direct[2];
    if (!destructive.has(method)) continue;
    // CALL_SITE's match starts at the non-word character *before* the call, so
    // `direct.index` is not the call's position. Comparing it against the
    // absolute index recorded for a routed mutation silently fails to suppress
    // the duplicate.
    const at = direct.index + direct[0].indexOf(direct[1]);
    if (covered.has(at)) continue;
    found.push({
      varName: null,
      method,
      index: at,
      line: code.slice(0, at).split('\n').length,
      shape: 'direct',
    });
  }
  return found.sort((a, b) => a.index - b.index);
}

/**
 * Whether `index` sits inside the `onConfirm` handler of a dialog element.
 *
 * Requires both halves: the attribute is an `onConfirm…` handler, *and* the
 * element carrying it is a dialog. A component that takes an `onConfirm` prop
 * and fires the delete from a plain `onClick` — which is what the ApiKeys
 * fixture in this gate's self-test used to claim — is not asking the user
 * anything, and the tag name is what distinguishes the two.
 *
 * **What this still cannot do**, and is recorded rather than papered over: it
 * reads prop *names*. It cannot tell a real `<ConfirmDialog>` from a component
 * that happens to be called `SomethingDialog` and ignores its `onConfirm`.
 * That last mile is a behavioural question and belongs to the Playwright suite;
 * a gate that claimed to answer it would be asserting something it cannot see.
 */
function inConfirmationContext(code, index) {
  const attr = /onConfirm[A-Za-z]*\s*=\s*\{/g;
  let m;
  while ((m = attr.exec(code)) !== null) {
    if (m.index >= index) break;
    const open = code.indexOf('{', m.index);
    const close = matchPair(code, open, '{', '}');
    if (close < 0) continue;
    if (index <= open || index >= close) continue;
    const tag = enclosingTagName(code, m.index);
    if (tag && /Dialog$/.test(tag)) return tag;
  }
  return null;
}

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

  const invocations = destructiveInvocations(code, destructive);
  if (invocations.length === 0) return violations;

  for (const { method, line, index } of invocations) {
    if (inConfirmationContext(code, index)) continue;

    const allow = ALLOW_COMMENT.exec(rawLines[line - 2] ?? '')
      ?? ALLOW_COMMENT.exec(rawLines[line - 1] ?? '');
    if (allow) continue;

    // One finding per invocation, but not per call of a shared handler: a
    // component that deletes in a map is the same defect, not twenty.
    violations.push({
      kind: 'unconfirmed-destructive',
      file: relativePath,
      line,
      message:
        `${method}() issues a DELETE, and this invocation is not inside the onConfirm `
        + 'handler of a dialog — nothing asks the user before the delete fires',
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

if (isMainModule(import.meta.url)) {
  main();
}
