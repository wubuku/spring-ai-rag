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
 *   - an `onError` in its options (typically `showToast`), or
 *   - a render of `<name>.isError` somewhere in the same file.
 *
 * It is deliberately file-scoped, like the accessibility gate: a component that
 * hands a mutation down and renders the error in a child is a shape this
 * checker cannot follow, and a rule that cries wolf gets ignored. The measured
 * baseline after Batch 791 is 37 mutations, 0 silent.
 *
 * Run: node scripts/check-mutation-errors.mjs
 */

import { readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripComments } from './check-design-system.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');

export const VIOLATION_KINDS = Object.freeze(['silent-mutation']);

/** `const name = useMutation(` — the declaration is what gets a name to track. */
const MUTATION_DECL = /const\s+([A-Za-z_$][\w$]*)\s*=\s*useMutation\s*\(/g;

/** `const { mutate: fire } = useMutation()` — no name to track, so not judged. */
const DESTRUCTURED_MUTATION = /\{[^}]*\bmutate\s*:\s*[A-Za-z_$][\w$]*[^}]*\}\s*=\s*useMutation\s*\(/;

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

  MUTATION_DECL.lastIndex = 0;
  let match;
  while ((match = MUTATION_DECL.exec(code)) !== null) {
    const name = match[1];
    const openParen = code.indexOf('(', match.index + match[0].length - 1);
    const options = optionsOf(code, openParen);

    // A named, non-destructured mutation is the only shape this rule judges.
    if (DESTRUCTURED_MUTATION.test(match[0])) continue;
    if (/\bonError\s*:/.test(options)) continue;
    if (new RegExp(`\\b${name}\\s*\\.\\s*isError\\b`).test(code)) continue;

    const line = code.slice(0, match.index).split('\n').length;
    // Same escape hatch shape as the accessibility gate, on the line before.
    const allow = ALLOW_COMMENT.exec(lines[line - 2] ?? '') ?? ALLOW_COMMENT.exec(lines[line - 1] ?? '');
    violations.push({
      kind: 'silent-mutation',
      file: relativePath,
      line,
      message: `${name} has no onError and nothing renders ${name}.isError`,
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
