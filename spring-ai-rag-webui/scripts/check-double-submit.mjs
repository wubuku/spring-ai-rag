#!/usr/bin/env node
/**
 * Double-submit gate.
 *
 * A write button that stays clickable while its request is in flight issues a
 * second request when the user clicks again. React Query cannot prevent that:
 * `mutate()` is a plain call, and the browser will happily send both.
 *
 * Batch 796 surveyed every `onClick={() => someMutation.mutate(...)}` in `src/`
 * and found 9 buttons with no pending guard at all:
 *
 *   ABTest.tsx       startMut ×2, pauseMut, stopMut
 *   Embeddings.tsx   cancelM, retryM
 *   Evaluation.tsx   createM, versionM, startM
 *
 * The consequences are not uniform. Cancelling a job twice is merely wasteful.
 * `createM` twice creates two suites with the same key and surfaces a confusing
 * conflict; `startM` twice starts two evaluation runs and burns the budget twice.
 *
 * The check is deliberately coarse: it asks whether the mutation's `isPending`
 * appears **anywhere in the same file**, not whether the particular button
 * consults it. That is a real limitation and it is chosen on purpose —
 *
 *   The first draft of Batch 796's survey parsed the `<button>` opening tag to
 *   decide this, and reported `ApiKeys.tsx:1042` as unguarded when the very
 *   next line is `disabled={immediateMutation.isPending}`. Multi-line JSX with
 *   nested braces defeats that parse, and a gate that cries wolf on correct code
 *   gets ignored within a week.
 *
 * File scope has the honest failure mode of a **miss**, never a false alarm: a
 * component that passes the mutation to a child, or derives `isPending` into a
 * differently-named variable, will not be flagged. That is the right way round.
 *
 * ## The escape hatch is real, and it is deliberately narrow
 *
 * A `double-submit-allow` comment **exempts** the declaration it precedes —
 * `scanSource` returns no violation for it. Batch 868 found that the previous
 * version only *annotated* the violation with the comment's text while still
 * reporting it, so the remedy this file's own error message recommended could
 * never make the gate pass. A probe confirmed it: same unguarded mutation,
 * comment added, `exit=1`, output carrying `[allowed: …]`. `src/` contained zero
 * uses of the comment, so nothing had ever exercised the path.
 *
 * The hatch now matches `check-alignment-policy`, which has honoured its own
 * `alignment-policy: allow-center` comment since Batch 862 (11 real uses). That
 * convention is stricter on purpose: a bare keyword is not enough, the reason
 * has to be written out after a `--` separator, so the exemption cannot be added
 * without a justification sitting next to it in review.
 *
 * Run: node scripts/check-double-submit.mjs
 */

import { readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripComments } from './check-design-system.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');

export const VIOLATION_KINDS = Object.freeze(['unguarded-write']);

/** `const saveM = useMutation(` — gives a mutation the name to track. */
const MUTATION_DECL = /const\s+([A-Za-z_$][\w$]*)\s*=\s*useMutation\s*\(/g;

/** `onClick={() => saveM.mutate(`, `void saveM.mutateAsync()`, `saveM.mutate()`. */
const MUTATE_CALL = /(?<![A-Za-z0-9_$.])([A-Za-z_$][\w$]*)\s*\.\s*mutate(?:Async)?\s*\(/g;

/**
 * `// double-submit-allow -- <reason>` on the declaration line or the one above.
 *
 * The `--` separator and the non-empty reason are load-bearing, not decoration:
 * they are what keeps the hatch from being a bare keyword anyone can type to
 * silence the gate. Same shape as `check-alignment-policy`'s `allow-center`.
 */
const ALLOW_COMMENT = /^\s*(?:\/\/|\/\*)\s*double-submit-allow\s+--\s+(\S.*?)\s*(?:\*\/)?\s*$/;

export function scanSource(relativePath, source) {
  const violations = [];
  const code = stripComments(source);
  const rawLines = source.split('\n');

  MUTATION_DECL.lastIndex = 0;
  let match;
  while ((match = MUTATION_DECL.exec(code)) !== null) {
    const name = match[1];
    const declaredLine = code.slice(0, match.index).split('\n').length;

    MUTATE_CALL.lastIndex = 0;
    let call;
    let fired = false;
    while ((call = MUTATE_CALL.exec(code)) !== null) {
      if (call[1] === name) {
        fired = true;
        break;
      }
    }
    if (!fired) continue;

    // The whole-file question: does anyone in this file look at isPending?
    if (new RegExp(`\\b${name}\\s*\\.\\s*isPending\\b`).test(code)) continue;

    // A justified exemption on the declaration line or the line above silences
    // this finding. See the file header: the previous version still reported it
    // and merely pasted the reason into the message, which made the remedy this
    // gate recommends impossible to apply.
    const allow = ALLOW_COMMENT.exec(rawLines[declaredLine - 1] ?? '')
      ?? ALLOW_COMMENT.exec(rawLines[declaredLine - 2] ?? '');
    if (allow) continue;

    violations.push({
      kind: 'unguarded-write',
      file: relativePath,
      line: declaredLine,
      message: `${name} is fired but nothing in this file reads ${name}.isPending`,
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
  const violations = files.flatMap(path =>
    scanSource(relative(projectRoot, path), readFileSync(path, 'utf8')),
  );

  if (violations.length > 0) {
    console.error('Write actions that can be fired twice:');
    for (const v of violations) {
      console.error(`- ${v.file}:${v.line} [${v.kind}] ${v.message}`);
    }
    console.error(
      '\nReact Query does not deduplicate `mutate()` calls: a second click sends a\n' +
        'second request. Disable the control while the mutation is pending\n' +
        '(`disabled={saveM.isPending}`), or record an inline\n' +
        '`// double-submit-allow -- <reason>` on the line above the declaration.\n' +
        'The reason is required, and the comment must sit next to the declaration.',
    );
    process.exitCode = 1;
    return;
  }
  console.log(
    `Double-submit policy passed; ${files.length} component file(s) scanned, every write ` +
      'action is guarded while its request is in flight.',
  );
}

if (process.argv[1] && import.meta.url === `file://${resolve(process.argv[1])}`) {
  main();
}
