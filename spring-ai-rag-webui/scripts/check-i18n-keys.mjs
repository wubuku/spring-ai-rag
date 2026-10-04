#!/usr/bin/env node
/**
 * Translation-key reconciliation gate.
 *
 * i18next does not fail loudly on a missing key. It returns the key string
 * itself, so `t('common.next')` renders the literal text `common.next` — a
 * truthy value that satisfies every `||` guard written to defend against it.
 *
 * Batch 792 surveyed the translation keys referenced statically from `src/` and
 * found 7 that no locale file carried:
 *
 *   common.previous, common.next      Version-history pagination. zh-CN had
 *                                     them, en.json did not, so the English UI
 *                                     showed "common.next" on a button.
 *   documents.searchPlaceholder,
 *   documents.loadError, search.history
 *                                     Missing from both, and each one sat
 *                                     behind a `t(...) || fallback` that
 *                                     therefore never fired.
 *   common.preview                   Introduced by Batch 789 itself, in both
 *                                     languages. A regression this batch
 *                                     found in its own predecessor.
 *
 * A guard that reads like protection and cannot protect anything is worse than
 * no guard, so this gate also refuses the idiom itself.
 *
 * Rules:
 *   1. missing-locale-key        a key referenced from code that a locale lacks
 *   2. locale-key-asymmetry      the two locale files do not carry the same keys
 *   3. dead-translation-fallback `t('x') || anything` — unreachable, because a
 *                                 missing key returns a truthy string
 *   4. dead-locale-key           a key every locale carries that no source
 *                                 reaches (Batch 818)
 *
 * Rules 1–3 all point one way: code asks for a key. Rule 4 points the other
 * way, and it exists because a gate that only ever checks the asking direction
 * cannot see copy that nothing renders. Such a key still costs a line in two
 * locale files and a slot in every future diff of its namespace.
 *
 * Rules 1–3 match `t('literal')` only, because a prefix is not a key and a
 * dynamic call cannot be resolved without running the component. Rule 4
 * therefore has to be generous about what counts as a reference, or it would
 * report live copy as dead. See `collectReferencedKeys`.
 *
 * ## `i18n-allow` is a note, not a switch — Batch 879
 *
 * An inline `/* i18n-allow: <reason> *\/` is recorded and printed after the
 * finding so a reviewer can weigh it. It does not make the gate pass, and until
 * this batch the error message said the opposite: it offered the comment as one
 * of three remedies, in a sentence ending "or record an inline
 * `/* i18n-allow: <reason> *\/`", while the gate went on failing. An end-to-end
 * probe put a real missing key in a real file, added the comment, and watched the
 * exit code stay at 1 — with `[allowed: …]` printed right beside it, so the
 * comment was demonstrably read and demonstrably ignored.
 *
 * Annotation rather than exemption is the right call *here*, and the reason is
 * what the defect is. A missing key is not invisible debt: i18next returns the
 * key string, so the user reads `documents.loadError` on the screen. There is
 * no reviewer-facing judgement to defer — the copy is either there or it is not,
 * and only adding it makes it so. `check-query-errors` and `check-mutation-errors`
 * reached the same conclusion for the same reason and say so in their own output.
 * The two gates where an exemption *is* the right answer (`check-design-system`,
 * `check-double-submit`) police debts a user cannot see.
 *
 * Rules 1 and 3 are the two that can carry a note. Rules 2 and 4 compare two
 * locale files and have no source line to anchor a comment to.
 *
 * Run: node scripts/check-i18n-keys.mjs
 */

import { readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripComments } from './check-design-system.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
// The self-test points this at a fixture tree so it can assert the gate's exit
// code. Batch 818 shipped a first self-test that only exercised the helpers,
// and emptying the dead-key reporting loop left every case green — the same
// "a gate that cannot fail" shape this repository keeps meeting. Asserting the
// decision to fail is the only part CI observes.
const sourceRoot = process.env.I18N_SOURCE_ROOT
  ? resolve(process.env.I18N_SOURCE_ROOT)
  : join(projectRoot, 'src');
const locales = ['en.json', 'zh-CN.json'];

export const VIOLATION_KINDS = Object.freeze([
  'missing-locale-key',
  'locale-key-asymmetry',
  'dead-translation-fallback',
  'dead-locale-key',
]);

/**
 * `t('some.key')` only — never `get('some.key')`, `format(...)` or anything
 * else whose callee happens to end in `t`. The lookbehind is what keeps
 * `searchParams.get('collectionKey') || undefined` out of the results.
 */
const TRANSLATION_CALL = /(?<![A-Za-z0-9_$.])t\(\s*'([^']+)'\s*\)/g;

const FALLBACK_GUARD = /(?<![A-Za-z0-9_$.])t\(\s*'[^']+'\s*\)\s*\|\|/g;

const ALLOW_COMMENT = /i18n-allow:\s*(.+?)\s*(?:\*\/)?$/;

/**
 * Batch 818. A key that reaches `t()` by anything other than a literal argument,
 * and the idioms this tree actually uses for each:
 *
 *   1. a template on any callee            t(`theme.${mode}`)
 *   2. the same through a parameter alias  translate(`documents.lifecycle.${v}`)
 *   3. i18next plural suffixes             t('search.resultsCount', { count })
 *   4. a key held in a lookup table        CALLER_VISIBLE: 'collectionScope.…'
 *   5. a key as a data-array member        ['report', 'evaluation.tabReport']
 *
 * Rather than model all five, the rule takes the coarsest criterion that still
 * fails in the safe direction: **a key that appears verbatim as a string
 * literal in non-test source is not dead.** Modelling each idiom would be
 * precise and wrong the moment a sixth one appears, and a wrong answer here
 * argues for deleting copy somebody still renders. This one can only under-
 * report, never invent a deletion.
 */
const STRING_LITERAL = /'([A-Za-z0-9_.]+)'/g;

/** `t(`theme.${mode}`)` — the callee may be `t`, an alias, or anything else. */
const TEMPLATE_CALL = /[A-Za-z_$][\w.$]*\(\s*`([A-Za-z0-9_.]*)\$\{/g;

/** `t('search.resultsCount', { count })` — the base of a plural family. */
const PLURAL_CALL = /(?<![A-Za-z0-9_$.])t\(\s*'([^']+)'\s*,\s*\{[^}]*\bcount\b/g;

/**
 * Keys this file can be shown to use. Test sources are excluded by the caller,
 * and so are the locale files themselves, whose keys are all string literals —
 * including them would make every key look referenced and the rule vacuous.
 */
export function collectReferencedKeys(code, localeKeys) {
  const referenced = new Set();

  TRANSLATION_CALL.lastIndex = 0;
  let match;
  while ((match = TRANSLATION_CALL.exec(code)) !== null) referenced.add(match[1]);

  // The coarse criterion. Only keys that actually exist are considered, so an
  // unrelated literal can never invent a reference.
  STRING_LITERAL.lastIndex = 0;
  while ((match = STRING_LITERAL.exec(code)) !== null) {
    if (localeKeys.has(match[1])) referenced.add(match[1]);
  }

  // Template prefixes: every key under the static part is reachable.
  TEMPLATE_CALL.lastIndex = 0;
  while ((match = TEMPLATE_CALL.exec(code)) !== null) {
    const prefix = match[1];
    // An empty prefix would make `key.startsWith('')` true for every key in the
    // file, so one `t(`${x}`)` would mark the whole locale alive and the rule
    // would pass while doing nothing. This is the shape of a gate that cannot
    // fail, and it is worth the explicit guard rather than a comment.
    if (!prefix) continue;
    for (const key of localeKeys) {
      if (key.startsWith(prefix)) referenced.add(key);
    }
  }

  // Plural families: `resultsCount` is written once and stored as
  // `resultsCount_one` / `resultsCount_other`.
  PLURAL_CALL.lastIndex = 0;
  while ((match = PLURAL_CALL.exec(code)) !== null) {
    const base = match[1];
    for (const key of localeKeys) {
      if (key.startsWith(`${base}_`)) referenced.add(key);
    }
  }

  return referenced;
}

/** Flattens a nested locale object into `a.b.c` keys. */
export function flattenKeys(object, prefix = '') {
  return Object.entries(object).flatMap(([key, value]) =>
    value !== null && typeof value === 'object'
      ? flattenKeys(value, `${prefix}${key}.`)
      : [`${prefix}${key}`],
  );
}

export function hasKey(locale, key) {
  return key.split('.').reduce((node, part) => (node == null ? undefined : node[part]), locale) !== undefined;
}

export function walk(dir, acc = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) walk(path, acc);
    else if (/\.tsx?$/.test(entry.name)) acc.push(path);
  }
  return acc;
}

/**
 * @param {string} relativePath for messages
 * @param {string} source component source
 * @param {Array<[string, Record<string, unknown>]>} loadedLocaleFiles parsed locales
 * @returns {{kind: string, file: string, line: number, message: string, detail?: string}[]}
 */
export function scanSource(relativePath, source, loadedLocaleFiles) {
  const violations = [];
  const code = stripComments(source);
  const rawLines = source.split('\n');
  const allowFor = line => {
    const above = ALLOW_COMMENT.exec(rawLines[line - 2] ?? '');
    const here = ALLOW_COMMENT.exec(rawLines[line - 1] ?? '');
    return (above ?? here)?.[1];
  };

  TRANSLATION_CALL.lastIndex = 0;
  let match;
  while ((match = TRANSLATION_CALL.exec(code)) !== null) {
    const key = match[1];
    // Skip the `t('x') ||` occurrences; they get their own, more specific kind.
    if (/\|\|\s*$/.test(code.slice(match.index + match[0].length, match.index + match[0].length + 4))) {
      continue;
    }
    const missingFrom = loadedLocaleFiles.filter(([, locale]) => !hasKey(locale, key)).map(([name]) => name);
    if (missingFrom.length === 0) continue;
    const line = code.slice(0, match.index).split('\n').length;
    violations.push({
      kind: 'missing-locale-key',
      file: relativePath,
      line,
      message: `t('${key}') is missing from ${missingFrom.join(', ')}`,
      detail: allowFor(line),
    });
  }

  FALLBACK_GUARD.lastIndex = 0;
  while ((match = FALLBACK_GUARD.exec(code)) !== null) {
    const line = code.slice(0, match.index).split('\n').length;
    violations.push({
      kind: 'dead-translation-fallback',
      file: relativePath,
      line,
      message: 't() returns the key itself when it is missing, so this || can never fire',
      detail: allowFor(line),
    });
  }

  return violations;
}

/** @returns {{kind: string, message: string}[]} */
export function compareLocales(loadedLocaleFiles) {
  const violations = [];
  const [firstName, first] = loadedLocaleFiles[0];
  const firstKeys = new Set(flattenKeys(first));
  for (const [name, locale] of loadedLocaleFiles.slice(1)) {
    const keys = new Set(flattenKeys(locale));
    for (const key of [...firstKeys].filter(k => !keys.has(k)).sort()) {
      violations.push({ kind: 'locale-key-asymmetry', message: `${key} is in ${firstName} but not in ${name}` });
    }
    for (const key of [...keys].filter(k => !firstKeys.has(k)).sort()) {
      violations.push({ kind: 'locale-key-asymmetry', message: `${key} is in ${name} but not in ${firstName}` });
    }
  }
  return violations;
}

function main() {
  const loadedLocaleFiles = locales.map(name => {
    const path = join(sourceRoot, 'i18n', 'locales', name);
    return [name, JSON.parse(readFileSync(path, 'utf8'))];
  });
  const localeKeys = new Set(flattenKeys(loadedLocaleFiles[0][1]));

  const files = walk(sourceRoot).filter(path => !/\.(test|spec)\.[jt]sx?$/.test(path));
  const violations = files.flatMap(path =>
    scanSource(relative(projectRoot, path), readFileSync(path, 'utf8'), loadedLocaleFiles),
  );
  violations.push(...compareLocales(loadedLocaleFiles));

  // Batch 818. This used to print a single unactionable number — "N keys are
  // reached only through dynamic template calls or are unused" — which merged
  // two very different populations. A count nobody can act on gets ignored, and
  // an ignored count is how 50 dead keys survived: copy no source renders,
  // still maintained in two languages, inflating every diff that touches a
  // namespace.
  //
  // Computed before the violation check so a tree that also has other problems
  // still reports its dead keys instead of hiding them behind the first failure.
  const referenced = new Set();
  for (const path of files) {
    const code = stripComments(readFileSync(path, 'utf8'));
    for (const key of collectReferencedKeys(code, localeKeys)) referenced.add(key);
  }
  for (const key of [...localeKeys].filter(k => !referenced.has(k)).sort()) {
    violations.push({
      kind: 'dead-locale-key',
      message: `${key} is in every locale but no source references it`,
    });
  }

  if (violations.length > 0) {
    console.error('Translation-key violations:');
    for (const v of violations) {
      const where = v.file ? `${v.file}:${v.line} ` : '';
      console.error(`- ${where}[${v.kind}] ${v.message}${v.detail ? ` [allowed: ${v.detail}]` : ''}`);
    }
    console.error(
      '\nA missing key renders as the key itself, and that string is truthy, so a\n'
      + '`t(...) || fallback` guard never fires. Add the key to every locale, or\n'
      + 'drop the guard.\n'
      + '\n'
      + 'An `i18n-allow` comment is NOT a remedy — it records your reason next to\n'
      + 'the finding so a reviewer can weigh it, and the finding still fails. That\n'
      + 'is deliberate: a missing key is not invisible debt, it is copy the user\n'
      + 'reads off the screen, so the only fix is the key itself. Add it.\n'
      + '\n'
      + 'A dead key is the opposite problem: nothing renders it, so delete it from\n'
      + 'every locale. If it really is assembled at runtime, name it as a string\n'
      + 'literal somewhere so this rule can see it — that is how the lookup tables\n'
      + 'and data arrays in this tree already keep their keys alive.',
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `Translation-key policy passed; ${referenced.size} key(s) referenced from ${files.length} component `
      + `file(s) all resolve in ${locales.join(' and ')}, the two locales carry identical key sets, `
      + `and every one of the ${localeKeys.size} keys is reachable from source.`,
  );
}

if (process.argv[1] && import.meta.url === `file://${resolve(process.argv[1])}`) {
  main();
}
