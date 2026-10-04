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

export const VIOLATION_KINDS = Object.freeze(['silent-mutation', 'no-op-error-handler', 'unreasoned-failure', 'swallowed-rejection']);

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

/**
 * Reads the balanced contents starting at the `(`/`{`/`[` at `openIndex`.
 *
 * Used instead of a regex because a toast's message argument can contain
 * parentheses of its own — `showToast(t('x'), 'error')` — and a regex that
 * stops at the first `)` reports the severity as the message.
 */
function balancedContentsOf(code, openIndex) {
  let depth = 0;
  for (let i = openIndex; i < code.length; i += 1) {
    const ch = code[i];
    if (ch === '(' || ch === '{' || ch === '[') depth += 1;
    else if (ch === ')' || ch === '}' || ch === ']') {
      depth -= 1;
      if (depth === 0) return code.slice(openIndex + 1, i);
    }
  }
  return null;
}

/** The text before the first top-level comma — i.e. the first argument. */
function firstArgument(argumentList) {
  let depth = 0;
  let quote = null;
  for (let i = 0; i < argumentList.length; i += 1) {
    const ch = argumentList[i];
    if (quote) {
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === "'" || ch === '"' || ch === '`') quote = ch;
    else if (ch === '(' || ch === '{' || ch === '[') depth += 1;
    else if (ch === ')' || ch === '}' || ch === ']') depth -= 1;
    else if (ch === ',' && depth === 0) return argumentList.slice(0, i);
  }
  return argumentList;
}

/**
 * The body of the arrow at `start`, and the offset of that body within `code`.
 *
 * A block body is read to its matching brace. An expression body is read to the
 * first top-level `,` or `;` — **not** to the next `;`, which is the trap this
 * function exists to avoid. `onError: () => showToast(t('x'), 'error'),` carries
 * no semicolon at all, so "scan to the next one" runs past the end of the
 * handler and swallows the *following* mutation's toast as well, reporting one
 * defect twice and putting it on the wrong line.
 */
function arrowBodyAt(code, start) {
  if (code[start] === '{') {
    const text = balancedContentsOf(code, start);
    return text === null ? null : { text, offset: start + 1 };
  }
  let depth = 0;
  let quote = null;
  for (let i = start; i < code.length; i += 1) {
    const ch = code[i];
    if (quote) {
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === "'" || ch === '"' || ch === '`') quote = ch;
    else if (ch === '(' || ch === '{' || ch === '[') depth += 1;
    else if (ch === ')' || ch === '}' || ch === ']') {
      depth -= 1;
      // The closing paren of the enclosing options object: the body ended above.
      if (depth < 0) return null;
    } else if (depth === 0 && (ch === ',' || ch === ';')) {
      return { text: code.slice(start, i), offset: start };
    }
  }
  return { text: code.slice(start, start + 500), offset: start };
}

const ON_ERROR_PROPERTY = /\bonError\s*:\s*(?:\(\s*[^)]*\)\s*=>|[A-Za-z_$][\w$]*\s*=>)/g;

const SHOW_TOAST_CALL = /\bshowToast\s*\(/g;

/**
 * `t('some.key')` with nothing from the failure spliced in.
 *
 * Only a plain quoted key counts. A template literal is excluded on purpose:
 * `t(\`documents.relocationErrors.${code || 'DEFAULT'}\`)` looks like a literal
 * at a glance but builds its key *from* the failure, so the message it produces
 * is the specific reason. Judging the surface syntax would flag it as the
 * defect it is the opposite of.
 */
const BARE_TRANSLATION_CALL = /^t\(\s*(['"])([^'"$]*)\1\s*\)$/;

/**
 * The failure sentences that announce nothing about why the write was rejected,
 * each reported on the line of the `onError` it belongs to.
 *
 * The line matters: every other rule in this gate reports the `useMutation(`
 * declaration, and so does the `mutation-error-allow` escape hatch — it reads
 * the comment on the line *above* the reported one. Reporting the line of the
 * `showToast` call instead puts the anchor several lines below the decision the
 * author is about to make, and the exemption then has nowhere to go. The `onError`
 * property is both the stable anchor and the line the author is looking at.
 *
 * This is the rule that had to exist because the three above all pass on a
 * mutation whose `onError` is `() => showToast(t('alerts.deleteError'), 'error')`
 * — a handler that is present, is not a no-op, and tells the user that the
 * delete failed and nothing more. The gate's own summary line claimed "every
 * write action reports its failure", and for these it did not: `api/client.ts`
 * had already lifted the server's reason into `Error.message`, and the handler
 * threw it away at the signature by taking no parameter at all.
 *
 * The shape checked is deliberately narrow — the toast's **message argument is
 * itself a bare `t('literal')`**. Everything that carries a reason passes:
 *   - `showToast(failureMessage(t, 'k', error), 'error')` — `t` is a bare
 *     identifier there, not a call;
 *   - `showToast(formatMutationError(t('k'), error), 'error')` — the message
 *     argument is the call, not `t('k')`;
 *   - `showToast(t('files.importError', { error: msg }), 'error')` — an
 *     interpolation is present.
 *
 * A coarser rule ("the body mentions no identifier matching the parameter")
 * was written first and measured at **zero** hits across all fourteen handlers
 * that take a named parameter, so it was dropped rather than shipped: a rule
 * that never fires reports "nothing to fix" forever and is worse than no rule,
 * because it reads as coverage.
 *
 * **Known limit.** This reads the `onError` body and cannot follow a call into
 * a local helper, so a handler of the form `onError: e => reportFailure(e, 'k')`
 * where `reportFailure` drops `e` is not detected. `Documents.tsx` had exactly
 * that shape: five mutations called a shared `handleMutationError(error, key)`
 * whose body ended in `showToast(t(fallbackKey), 'error')`, and the reason died
 * inside the helper where no rule could see it. Batch 859 fixed the helper
 * rather than teaching this gate to chase through it, and that is the honest
 * boundary: the gate keeps the sites where the message is written out in full
 * at the call site, and the helper shape stays a review question.
 */
function findUnreasonedFailures(options) {
  const found = [];
  ON_ERROR_PROPERTY.lastIndex = 0;
  let match;
  while ((match = ON_ERROR_PROPERTY.exec(options)) !== null) {
    const body = arrowBodyAt(options, match.index + match[0].length);
    if (body === null) continue;
    SHOW_TOAST_CALL.lastIndex = 0;
    let call;
    while ((call = SHOW_TOAST_CALL.exec(body.text)) !== null) {
      const argumentList = balancedContentsOf(body.text, call.index + call[0].length - 1);
      if (argumentList === null) continue;
      const key = firstArgument(argumentList).trim().match(BARE_TRANSLATION_CALL);
      if (key === null) continue;
      found.push({ key: key[2], offset: match.index });
    }
  }
  return found;
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
      if (!NOOP_HANDLER.test(options)) {
        // Present and doing something is not the same as telling the user why.
        // `options` is a slice of `code` starting at `openParen`, so an offset
        // inside it maps back by adding that same index.
        for (const { key, offset } of findUnreasonedFailures(options)) {
          const line = code.slice(0, openParen + offset).split('\n').length;
          const allow = allowAt(line);
          violations.push({
            kind: 'unreasoned-failure',
            file: relativePath,
            line,
            message: `${name} reports the failure as the fixed sentence "${key}" and never says what the server objected to`,
            detail: allow ? allow[1] : undefined,
          });
        }
        continue;
      }
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
