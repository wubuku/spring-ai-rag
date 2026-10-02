#!/usr/bin/env node
/**
 * Mutation-failure visibility gate.
 *
 * A write button whose failure produces no output at all is indistinguishable
 * from a button that does nothing. Batch 791 surveyed every `useMutation` in
 * `src/` and found 37 of them, of which 6 failed *invisibly*:
 *
 *   Embeddings.tsx  cancelM, retryM, applyRepairM
 *   Evaluation.tsx  createM, versionM, startM
 *
 * Each had an `onSuccess` and no `onError`, and the component rendered neither
 * `.isError` nor a toast. Press "Cancel job" on an embedding job whose backend
 * returns 500 and the page does not flicker, does not explain, and leaves the
 * job looking untouched — the user cannot tell a rejected request from a button
 * that is simply broken, and will press it again.
 *
 * The `apiClient` response interceptor does not save them: it normalises the
 * error message and clears the credential on 401, and then rejects. Nothing is
 * shown unless a component chooses to show it.
 *
 * This gate requires every mutation to have at least one of:
 *   - an `onError` in its options that actually does something (typically
 *     `showToast`), or
 *   - a render of `<name>.isError` somewhere in the same file.
 *
 * Batch 798 changed the first of those. It had only ever asked whether the key
 * `onError` appeared, so `onError: () => {}` satisfied it — and `Alerts.tsx`
 * shipped four of them, on the create and delete of both SLO configurations
 * and silence schedules. Worse than a silent write, the two create mutations
 * call `onHideForm()` from `onSuccess`, so a rejected create closed the form
 * and cleared the fields: that reads as success. The rule now inspects the
 * body (`no-op-error-handler`), and a second rule (`swallowed-rejection`) asks
 * a bare `catch` to say why discarding the reason is acceptable.
 *
 * It is deliberately file-scoped, like the accessibility gate: a component that
 * hands a mutation down and renders the error in a child is a shape this
 * checker cannot follow, and a rule that cries wolf gets ignored. The measured
 * baseline after Batch 798 is 37 mutations, 0 silent, 0 no-op.
 *
 * Run: node scripts/check-mutation-errors.mjs
 */

import { readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripComments } from './check-design-system.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');

export const VIOLATION_KINDS = Object.freeze(['silent-mutation', 'no-op-error-handler', 'swallowed-rejection']);

/** `const name = useMutation(` — the declaration is what gets a name to track. */
const MUTATION_DECL = /const\s+([A-Za-z_$][\w$]*)\s*=\s*useMutation\s*\(/g;

/** `const { mutate: fire } = useMutation()` — no name to track, so not judged. */
const DESTRUCTURED_MUTATION = /\{[^}]*\bmutate\s*:\s*[A-Za-z_$][\w$]*[^}]*\}\s*=\s*useMutation\s*\(/;

/**
 * An error handler that exists and does nothing.
 *
 * Batch 798 found four of these in `Alerts.tsx`, all written as
 * `onError: () => {}`, and the gate was green the whole time — Batch 791 had
 * added it, and "the options object contains `onError`" is the only thing it
 * ever asked. A handler that swallows is not a handler: the write fails, the
 * user is told nothing, and on the two create mutations `onSuccess` runs
 * `onHideForm()`, so the form closes and the fields clear. That reads as
 * success. The check has to look at what the body does, not at its presence.
 */
/**
 * Deliberately **not** global. This is only ever used with `.test()`, and a
 * `/g` regex carries `lastIndex` across calls, so the same module would answer
 * differently depending on which file it had scanned before — the first
 * version of this rule was written with `/g`, and its own self-test caught the
 * resulting order dependence within minutes.
 */
const NOOP_HANDLER =
  /\b(onError|onSettled)\s*:\s*(?:\([^()]*\)|[A-Za-z_$][\w$]*)\s*=>\s*(?:\{\s*\}|null|undefined)\s*(?![\w(.[`])/;

/**
 * A `catch` that discards the reason without saying why that is acceptable.
 *
 * Every legitimate one in `src/` carries a sentence saying why — "storage may be
 * unavailable", "error reporting must never break the UI", "best-effort". This
 * rule asks for that sentence. It is the weakest of the gates by construction,
 * since anyone can write `// ignore`, and it is here anyway: a silent `catch`
 * is the single easiest way to lose a failure without noticing, and the
 * comment costs one line at the moment the decision is being made.
 *
 * `catch (e) { console.error(e) }` is **not** this rule. Writing to the console
 * is a decision with a visible trace, and deciding which of those deserve a
 * user-facing message is a product call this gate should not make.
 */
/**
 * The shape of a `catch` that swallows, capturing whatever sits inside so the
 * rule can tell an empty `catch` from one that explains itself. Judged against
 * the raw source: `stripComments` would erase the very sentence being asked
 * for.
 */
const CATCH_WITH_EMPTY_BODY =
  /catch\s*(?:\([^)]*\))?\s*\{\s*((?:\/\/[^\n]*\n?|\/\*[\s\S]*?\*\/|\s)*)\}/g;

const ALLOW_COMMENT = /mutation-error-allow:\s*(.+?)\s*(?:\*\/)?$/;

/** Walks forward from the `(` of the options object to its matching `)`. */
function optionsOf(code, openParenIndex) {
  let depth = 1;
  let i = openParenIndex + 1;
  while (i < code.length && depth > 0) {
    const ch = code[i];
    if (ch === '(') depth += 1;
    else if (ch === ')') depth -= 1;
    i += 1;
  }
  return code.slice(openParenIndex, i);
}

export function scanSource(relativePath, source) {
  const violations = [];
  const code = stripComments(source);
  const lines = source.split('\n');
  const allowAt = line =>
    ALLOW_COMMENT.exec(lines[line - 2] ?? '') ?? ALLOW_COMMENT.exec(lines[line - 1] ?? '');

  MUTATION_DECL.lastIndex = 0;
  let match;
  while ((match = MUTATION_DECL.exec(code)) !== null) {
    const name = match[1];
    const openParen = code.indexOf('(', match.index + match[0].length - 1);
    const options = optionsOf(code, openParen);

    // A named, non-destructured mutation is the only shape this rule judges.
    if (DESTRUCTURED_MUTATION.test(match[0])) continue;
    // A handler that exists but does nothing is not a handler. This is the
    // check that had to change in Batch 798: `onError: () => {}` satisfied the
    // line above for as long as the rule existed.
    if (/\bonError\s*:/.test(options)) {
      if (!NOOP_HANDLER.test(options)) continue;
      const line = code.slice(0, match.index).split('\n').length;
      const allow = allowAt(line);
      violations.push({
        kind: 'no-op-error-handler',
        file: relativePath,
        line,
        message: `${name} has an \`onError\` whose body is empty, so a rejected write is still invisible`,
        detail: allow ? allow[1] : undefined,
      });
      continue;
    }
    if (new RegExp(`\\b${name}\\s*\\.\\s*isError\\b`).test(code)) continue;

    const line = code.slice(0, match.index).split('\n').length;
    // Same escape hatch shape as the accessibility gate, on the line before.
    const allow = allowAt(line);
    violations.push({
      kind: 'silent-mutation',
      file: relativePath,
      line,
      message: `${name} has no onError and nothing renders ${name}.isError`,
      detail: allow ? allow[1] : undefined,
    });
  }

  // A `catch` that is empty and gives no reason, judged on the raw source so
  // that a comment explaining it counts as a reason.
  CATCH_WITH_EMPTY_BODY.lastIndex = 0;
  while ((match = CATCH_WITH_EMPTY_BODY.exec(source)) !== null) {
    const body = match[1] ?? '';
    // Whitespace or comments only, and no comment at all means no reason.
    if (stripComments(body).trim().length > 0) continue;
    if (/[/*]/.test(body)) continue;
    const line = source.slice(0, match.index).split('\n').length;
    const allow = allowAt(line);
    violations.push({
      kind: 'swallowed-rejection',
      file: relativePath,
      line,
      message: 'a `catch` block discards the failure without saying why that is acceptable',
      detail: allow ? allow[1] : undefined,
    });
  }
  return violations;
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
  const violations = files.flatMap(path => scanSource(relative(projectRoot, path), readFileSync(path, 'utf8')));

  if (violations.length > 0) {
    console.error('Mutation failures that would go unseen:');
    for (const v of violations) {
      console.error(`- ${v.file}:${v.line} [${v.kind}] ${v.message}${v.detail ? ` [allowed: ${v.detail}]` : ''}`);
    }
    console.error(
      '\nA write action whose failure shows nothing is a button the user cannot\n' +
        'trust. Add an `onError` (showToast) or render `<name>.isError`, or record\n' +
        'an inline `/* mutation-error-allow: <reason> *\/` on the preceding line.',
    );
    process.exitCode = 1;
    return;
  }
  console.log(
    `Mutation-failure policy passed; ${files.length} component file(s) scanned, ` +
      'every write action reports its failure.',
  );
}

if (process.argv[1] && import.meta.url === `file://${resolve(process.argv[1])}`) {
  main();
}
