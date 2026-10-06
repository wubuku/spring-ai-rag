#!/usr/bin/env node
/**
 * Clipboard gate: `navigator.clipboard` is reached through one function or not at all.
 *
 * Why this exists
 * ---------------
 * Batch 939 surveyed how the WebUI writes to the clipboard and found three call sites,
 * written three ways. `Files.tsx` wrapped its copy in `try/catch` and toasted on failure.
 * The two in `ApiKeys.tsx` did not:
 *
 *     await navigator.clipboard.writeText(rawKey);
 *     showToast(t('apiKeys.copied'), 'success');
 *
 * Both of those buttons hand over a **raw API key that is shown exactly once** — close
 * the dialog and the value is unrecoverable. The failure was invisible for two separate
 * reasons, and neither of them looks like a bug in review:
 *
 *   - `writeText` returns a promise that **rejects** when the document is not focused or
 *     permission is refused. An `await` with no `catch` inside an event handler produces
 *     an unhandled rejection and no output.
 *   - `navigator.clipboard` is **`undefined` outside a secure context**. A self-hosted
 *     UI reached over plain HTTP on a LAN address — the ordinary way to run this on a
 *     second machine — has no such object, so the line above throws a `TypeError` on
 *     `undefined`. That is not a rejected promise; it is a thrown error inside an async
 *     function, which also goes nowhere.
 *
 * So on the two most important buttons in the key page, a failed copy looked exactly like
 * a successful one: no toast, no key, no sign anything happened.
 *
 * What the rule is
 * ---------------
 * "One writer, and it cannot throw." `src/utils/clipboard.ts` is the only file allowed to
 * name `navigator.clipboard`; it resolves the result to `'copied' | 'failed'` and never
 * rejects, and `useClipboardCopy` turns that into a toast on both paths. No call site can
 * forget a `catch` because there is no `catch` at a call site to forget.
 *
 * The rule needs no allowlist: the one legitimate owner is named by path.
 *
 * Known limits, pinned by the self-test:
 *   - Comments are stripped first. This file, and the tests, name `navigator.clipboard`
 *     constantly while explaining why they must not.
 *   - Test files are out of scope, so a fixture is free to install a fake clipboard.
 *   - The rule matches the property access, not the intent. A writer reached some other
 *     way — `window.navigator['clip' + 'board']` — would not be seen. Nothing in the
 *     tree does that; the point is that the rule is about the shape, so it is honest
 *     about the shapes it cannot see.
 *   - It says nothing about whether a *successful* copy is reported. That lives in
 *     `ApiKeys.clipboard.test.tsx`, which is a rendering test on purpose: the defect was
 *     never in what the helper was asked to do.
 *
 * Run:
 *   node scripts/check-clipboard-calls.mjs
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripTsxComments } from './lib/tsx-source.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

const webuiRoot = fileURLToPath(new URL('..', import.meta.url));

/** The one file allowed to name `navigator.clipboard`. Named by path, not by a line list. */
export const OWNER = 'src/utils/clipboard.ts';

export const VIOLATION_KINDS = Object.freeze({
  DIRECT_CLIPBOARD_WRITE: 'direct-clipboard-access',
});

/** `navigator.clipboard`, with or without a `window.` in front and any spacing. */
const ACCESS = /(?:window\s*\.\s*)?navigator\s*\.\s*clipboard\b/gu;

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
 *
 * Comments are stripped here rather than by the caller, so a self-test cannot pass a
 * fixture that only looks clean because nobody has read it yet. That is not a
 * hypothetical: the Batch 938 gate and the Batch 935 gate were each defeated by their own
 * explanatory comments before they were ever run against real code.
 */
export function findDirectClipboardAccess(files) {
  const findings = [];
  for (const file of files) {
    if (file.path === OWNER) continue;
    const source = stripTsxComments(file.text);
    source.split('\n').forEach((line, index) => {
      ACCESS.lastIndex = 0;
      if (!ACCESS.test(line)) return;
      findings.push({
        kind: VIOLATION_KINDS.DIRECT_CLIPBOARD_WRITE,
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

  const findings = findDirectClipboardAccess(files);

  if (findings.length > 0) {
    console.error(`${findings.length} site(s) reach the clipboard outside ${OWNER}:`);
    for (const f of findings) {
      console.error(`- [${f.kind}] ${f.path}:${f.line} — ${f.text}`);
    }
    console.error(
      `\nUse copyText() from ${OWNER}, through useClipboardCopy() to get the toast.`
      + ' The raw call rejects when permission is refused and is a TypeError outside a'
      + ' secure context, so an uncaught one tells the user nothing at the exact moment'
      + ' they most need to know.',
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `Clipboard check passed; ${files.length} source file(s), every write routed through`
    + ` ${OWNER}.`,
  );
}

if (isMainModule(import.meta.url)) {
  main();
}
