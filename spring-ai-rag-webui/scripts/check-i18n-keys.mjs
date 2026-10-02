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
 *
 * Dynamic calls (`t(`prefix.${x}`)`) are not checked: 6 of them exist and a
 * key prefix cannot be resolved without running the component. Keys that no
 * static call references are reported as a count, not as a failure — they are
 * assembled dynamically or belong to a page's own namespace.
 *
 * Run: node scripts/check-i18n-keys.mjs
 */

import { readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripComments } from './check-design-system.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');
const locales = ['en.json', 'zh-CN.json'];

export const VIOLATION_KINDS = Object.freeze([
  'missing-locale-key',
  'locale-key-asymmetry',
  'dead-translation-fallback',
]);

/**
 * `t('some.key')` only — never `get('some.key')`, `format(...)` or anything
 * else whose callee happens to end in `t`. The lookbehind is what keeps
 * `searchParams.get('collectionKey') || undefined` out of the results.
 */
const TRANSLATION_CALL = /(?<![A-Za-z0-9_$.])t\(\s*'([^']+)'\s*\)/g;

const FALLBACK_GUARD = /(?<![A-Za-z0-9_$.])t\(\s*'[^']+'\s*\)\s*\|\|/g;

const ALLOW_COMMENT = /i18n-allow:\s*(.+?)\s*(?:\*\/)?$/;

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

  if (violations.length > 0) {
    console.error('Translation-key violations:');
    for (const v of violations) {
      const where = v.file ? `${v.file}:${v.line} ` : '';
      console.error(`- ${where}[${v.kind}] ${v.message}${v.detail ? ` [allowed: ${v.detail}]` : ''}`);
    }
    console.error(
      '\nA missing key renders as the key itself, and that string is truthy, so a\n' +
        '`t(...) || fallback` guard never fires. Add the key to every locale, drop\n' +
        'the guard, or record an inline `/* i18n-allow: <reason> *\\/`.',
    );
    process.exitCode = 1;
    return;
  }

  const referenced = new Set();
  for (const path of files) {
    const code = stripComments(readFileSync(path, 'utf8'));
    TRANSLATION_CALL.lastIndex = 0;
    let match;
    while ((match = TRANSLATION_CALL.exec(code)) !== null) referenced.add(match[1]);
  }
  const unreferenced = [...localeKeys].filter(key => !referenced.has(key)).length;
  console.log(
    `Translation-key policy passed; ${referenced.size} key(s) referenced from ${files.length} component ` +
      `file(s) all resolve in ${locales.join(' and ')}, and the two locales carry identical key sets ` +
      `(${unreferenced} key(s) are reached only through dynamic template calls or are unused).`,
  );
}

if (process.argv[1] && import.meta.url === `file://${resolve(process.argv[1])}`) {
  main();
}
