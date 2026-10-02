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

/**
 * Attributes whose value is a machine identifier rather than something a user
 * reads. `role={state === 'error' ? 'alert' : 'status'}` is not untranslated
 * copy — 'alert' and 'status' are ARIA tokens, and translating them would break
 * the accessibility tree rather than improve it.
 */
const MACHINE_VALUE_ATTRIBUTES = new Set([
  'role', 'type', 'variant', 'size', 'id', 'name', 'as', 'to', 'href', 'form',
  'method', 'target', 'rel', 'event', 'viewBox', 'fillRule', 'clipRule',
  'strokeWidth', 'data-testid', 'data-test', 'data-state', 'data-tone',
]);

/**
 * A string that is one of the *branches* of a conditional or logical expression.
 *
 * This is the load-bearing restriction. An earlier draft accepted any capitalised
 * string inside any `{...}` and reported 70, of which most were not rendered text
 * at all: `principal.status !== 'ACTIVE'`, `event.key === 'ArrowRight'`,
 * `new Error('Tooltip expects a single React element as its trigger')`, and — the
 * real culprit — every `{ ... }` *block* and destructuring pattern in the file,
 * which a `{...}` regex cannot tell apart from a JSX container. Requiring the
 * literal to sit behind `?`, `:`, `&&`, `||` or `??` is what "this string is a
 * possible rendered value" actually means, and it drops 70 to a number worth
 * reading.
 */
const SINGLE = "'([^'\\\\]*(?:\\\\.[^'\\\\]*)*)'";
const DOUBLE = '"([^"\\\\]*(?:\\\\.[^"\\\\]*)*)"';
const BRANCH_STRING = new RegExp(
  `(\\?|:|&&|\\|\\||\\?\\?)\\s*(?:${SINGLE}|${DOUBLE})`, 'g',
);

/**
 * Blanks out every `t(...)` call, arguments and all.
 *
 * `t('nav.closeSidebar', 'Close sidebar')` — the second argument is a fallback
 * for a missing key, not rendered copy: i18next only reaches for it when the key
 * is absent, which `check-i18n-keys` reports separately. Counting it as
 * hardcoded copy would be a second gate answering the same question with a
 * worse answer.
 *
 * Regex over the argument list was not enough. The shapes actually in this tree
 * include `t('k')`, `t('k', 'Close sidebar')`, `t('k', { defaultValue: '…' })`
 * and `` t(`prefix.${code || 'DEFAULT'}`) ``, and a pattern that matched only
 * quoted arguments reported eight phantom strings on Settings.tsx — every one of
 * them a fallback `defaultValue` that `check-i18n-keys` already owns. None of
 * them is rendered text, so a scanner that balances parentheses is the honest
 * way to exclude the whole class rather than a longer pattern.
 *
 * A `t(` is only a call when it is not part of a longer identifier (`format(`,
 * `split(`), which is why the identifier boundary is checked explicitly.
 */
export function maskTranslationCalls(code) {
  let out = '';
  let i = 0;
  while (i < code.length) {
    const ch = code[i];
    if (ch === '"' || ch === "'" || ch === '`') {
      const quote = ch;
      out += ch;
      i += 1;
      while (i < code.length && code[i] !== quote) {
        if (code[i] === '\\') {
          out += code[i] ?? '';
          i += 1;
        }
        out += code[i] ?? '';
        i += 1;
      }
      out += quote;
      i += 1;
      continue;
    }
    if (ch === 't' && code[i + 1] === '(' && !/[\w$.]/.test(code[i - 1] ?? '')) {
      let depth = 0;
      let j = i + 1;
      for (; j < code.length; j += 1) {
        const inner = code[j];
        if (inner === '"' || inner === "'" || inner === '`') {
          const quote = inner;
          j += 1;
          while (j < code.length && code[j] !== quote) {
            if (code[j] === '\\') j += 1;
            j += 1;
          }
          continue;
        }
        if (inner === '(') depth += 1;
        else if (inner === ')') {
          depth -= 1;
          if (depth === 0) break;
        }
      }
      out += ' '.repeat(j - i + 1);
      i = j + 1;
      continue;
    }
    out += ch;
    i += 1;
  }
  return out;
}

/** `{ method: 'POST', headers: { ... } }` is a JavaScript object, not markup. */
const OBJECT_LITERAL = /^\s*['"]?[\w-]+['"]?\s*:/;

export function findExpressionContainerCopy(source) {
  // Template interpolations first: `${code || 'DEFAULT'}` is a key segment, and
  // the container regex would otherwise see that inner `{...}` on its own.
  // This is a deliberate false negative — copy rendered through a template
  // interpolation that also holds a conditional is not checked.
  const code = source.replace(/\$\{[^{}]*\}/g, ' ');
  const masked = maskTranslationCalls(code);
  const found = [];
  const container = /([A-Za-z][\w-]*)\s*=\s*\{([^{}]*)\}|\{([^{}]*)\}/g;
  let m;
  while ((m = container.exec(masked)) !== null) {
    const attribute = m[1];
    if (attribute && MACHINE_VALUE_ATTRIBUTES.has(attribute)) continue;
    // A bare `{...}` only counts as a JSX expression container when what stands
    // before it is markup: the end of a tag, the end of a sibling expression, an
    // opening paren of a returned fragment, or the start of a nested one.
    // Without this the scan reads ordinary functions — it reported the
    // `'DISABLED' : 'READY' : 'NOT_REQUESTED'` branches of lifecycleClass(),
    // which build a CSS class name and are never rendered.
    const before = masked.slice(0, m.index).replace(/\s+$/, '').slice(-1);
    if (!attribute && !['>', '}', '(', '{'].includes(before)) continue;
    const body = m[2] ?? m[3] ?? '';
    if (OBJECT_LITERAL.test(body)) continue;
    for (const literal of body.matchAll(BRANCH_STRING)) {
      const copy = (literal[2] ?? literal[3] ?? '').trim();
      if (!/^[A-Z]/.test(copy)) continue;
      found.push({
        copy,
        kind: 'jsx-expression',
        line: code.slice(0, m.index).split('\n').length,
      });
    }
  }
  return found;
}

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
  found.push(...findExpressionContainerCopy(code));
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
