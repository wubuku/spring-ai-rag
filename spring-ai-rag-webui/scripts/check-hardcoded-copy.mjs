#!/usr/bin/env node
/**
 * Hardcoded-copy gate: a string a user can read must come from the locale files.
 *
 * `check-i18n-keys` covers the failure modes of a key that *is* used: missing
 * from a locale, asymmetric between the two locales, or hidden behind a
 * `t(...) || fallback` that can never fire. It has nothing to say about a
 * component that never calls `t` at all — which is how a Chinese user ends up
 * reading "Something went wrong" on the one screen that appears when the app has
 * already broken, and "Try different keywords or adjust your search." in the
 * empty state.
 *
 * What Batch 808 found with a single sweep of the source tree:
 *
 *   - `ErrorBoundary` and `MetricsCharts` had **zero** calls to `t`. Thirteen of
 *     MetricsCharts' strings were English, including the Recharts `name` values
 *     that end up on the X axis and in tooltips, so the charts were unreadable
 *     in any other language regardless of the surrounding page.
 *   - Eight strings already had translations. `common.loading`, `common.retry`,
 *     `search.noResults`, `search.resultsCount`, `search.collection`,
 *     `search.allCollections`, `documents.collection` and `alerts.unit` were
 *     maintained in both locale files, and `search.noResults` /
 *     `search.resultsCount` had **no caller at all** — the markup had been
 *     building the same sentences by hand for as long as they existed.
 *
 * Detection deliberately runs on an expanded surface. Two sweeps were needed to
 * find everything, and the second one found things the first missed:
 *
 *   - JSX text nodes            `<h3>Call Volume</h3>`
 *   - chart labels              `{ name: 'Retrievals' }`, `<Bar name="Calls" />`
 *   - accessible names          `aria-label="Notifications"`
 *   - titles and placeholders   `title="Generate UUID"`,
 *                               `placeholder="UUID or business key"` — both
 *                               announced or shown, both missed by the first
 *                               sweep, which is why this one is written down.
 *
 * Rules:
 *   1. component-without-i18n  the file shows user-visible English and never
 *                              reaches for i18n at all. Strongest signal, and
 *                              the one that caught both untranslated files.
 *   2. hardcoded-user-copy     the file *does* use i18n but this string does not
 *                              go through it.
 *
 * The allowlist below is not an amnesty — it is the seven strings that should
 * stay in English, each with the reason it is correct rather than merely
 * tolerated. An entry that is no longer needed is removed, not left to rot.
 *
 * Run:
 *   node scripts/check-hardcoded-copy.mjs
 */

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, sep, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const SOURCE_ROOT = join(projectRoot, 'src');

export const VIOLATION_KINDS = Object.freeze({
  NO_I18N_AT_ALL: 'component-without-i18n',
  HARDCODED_COPY: 'hardcoded-user-copy',
});

/**
 * Strings that stay English on purpose, as `path:copy` → reason.
 *
 * The path is relative to `src/` and the copy is the literal; a line number is
 * deliberately not used because it drifts on every unrelated edit.
 */
export const ALLOWED = Object.freeze({
  'pages/Alerts.tsx:RECURRING':
    'select option whose visible text is its own enum value; the label the API ' +
    'stores is RECURRING, so showing anything else would desync the two.',
  'pages/Documents.tsx:ASYNC':
    'select option whose visible text is its own enum value (batch embedding mode).',
  'pages/Documents.tsx:SYNC':
    'select option whose visible text is its own enum value (batch embedding mode).',
  'pages/Documents.tsx:SKIP':
    'select option whose visible text is its own enum value (batch embedding mode).',
  'pages/Embeddings.tsx:QUEUED':
    'title attribute carrying a job state enum, not prose.',
  'pages/Evaluation.tsx:MRR':
    'metric abbreviation standing beside nDCG; both are the accepted symbols, ' +
    'and translating only MRR would split the header row.',
  'pages/Settings.tsx:English':
    'language picker labels each language in that language, which is the ' +
    'convention this picker follows; a Chinese user looking for 中文 must find it.',
});

const PATTERNS = [
  { kind: 'jsx-text', re: />\s*([A-Z][A-Za-z0-9 ,.'’!?/&()\-:]{2,})\s*</ },
  { kind: 'chart-data', re: /\bname:\s*'([A-Z][A-Za-z0-9 ()]{2,})'/ },
  { kind: 'chart-prop', re: /\bname="([A-Z][A-Za-z0-9 ()]{2,})"/ },
  {
    kind: 'attribute',
    re: /\b(?:aria-label|title|placeholder|alt)="([A-Z][A-Za-z0-9 ,.'’!?()\-:]{2,})"/,
  },
];

/** Strips comments so a string inside prose cannot be read as rendered copy. */
export function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ');
}

export function usesI18n(source) {
  return /useTranslation\b/.test(source)
    || /withTranslation\b/.test(source)
    || /<Trans[\s>]/.test(source);
}

/**
 * @returns {{copy: string, kind: string, line: number}[]}
 */
export function findHardcodedCopy(relPath, source) {
  const code = stripComments(source);
  const found = [];
  for (const { kind, re } of PATTERNS) {
    const rx = new RegExp(re.source, re.flags.includes('g') ? re.flags : `${re.flags}g`);
    let m;
    while ((m = rx.exec(code)) !== null) {
      found.push({
        copy: m[1].trim(),
        kind,
        line: code.slice(0, m.index).split('\n').length,
      });
    }
  }
  return found.sort((a, b) => a.line - b.line);
}

export function checkFile(relPath, source, allowed = ALLOWED) {
  const hits = findHardcodedCopy(relPath, source);
  if (hits.length === 0) return [];
  const hasI18n = usesI18n(source);
  const violations = [];
  for (const hit of hits) {
    const key = `${relPath}:${hit.copy}`;
    if (Object.prototype.hasOwnProperty.call(allowed, key)) continue;
    violations.push({
      kind: hasI18n ? VIOLATION_KINDS.HARDCODED_COPY : VIOLATION_KINDS.NO_I18N_AT_ALL,
      detail:
        `${relPath}:${hit.line} renders "${hit.copy}" (${hit.kind}) as literal text. ` +
        (hasI18n
          ? 'The file uses i18n elsewhere, so this string is a gap rather than an untranslated component.'
          : 'The file never calls useTranslation, so none of its user-visible text can be translated.'),
    });
  }
  return violations;
}

function tsxFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) out.push(...tsxFiles(path));
    else if (entry.endsWith('.tsx')) out.push(path);
  }
  return out;
}

export function collectSources(root = SOURCE_ROOT) {
  if (!existsSync(root)) return [];
  return tsxFiles(root)
    .filter((p) => !/\.(test|spec)\.tsx$/.test(p))
    .map((path) => ({
      relPath: relative(root, path).split(sep).join('/'),
      source: readFileSync(path, 'utf8'),
    }));
}

function main() {
  const files = collectSources();
  const violations = files.flatMap((f) => checkFile(f.relPath, f.source));
  const allowed = files.reduce(
    (n, f) => n + findHardcodedCopy(f.relPath, f.source)
      .filter((h) => Object.prototype.hasOwnProperty.call(
        ALLOWED, `${f.relPath}:${h.copy}`,
      )).length,
    0,
  );

  if (violations.length > 0) {
    console.error('Hardcoded user-visible copy:');
    for (const v of violations) console.error(`- [${v.kind}] ${v.detail}`);
    console.error(
      '\nUser-visible text must come from src/i18n/locales via t(). If a string is a\n' +
        'technical term or an enum value whose label is its own value, add it to ALLOWED\n' +
        'in scripts/check-hardcoded-copy.mjs with the reason — not a bare suppression.',
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `Hardcoded-copy check passed; ${files.length} component source(s) scanned, ` +
      `${allowed} intentional technical string(s) allowlisted, no other user-visible ` +
      'text is a literal.',
  );
}

if (process.argv[1] && import.meta.url === `file://${resolve(process.argv[1])}`) {
  main();
}
