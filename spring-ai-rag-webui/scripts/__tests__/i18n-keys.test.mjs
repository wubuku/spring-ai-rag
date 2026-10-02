import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { scanSource, compareLocales, flattenKeys, hasKey, VIOLATION_KINDS } from '../check-i18n-keys.mjs';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');

const en = JSON.parse(readFileSync(join(sourceRoot, 'i18n/locales/en.json'), 'utf8'));
const zh = JSON.parse(readFileSync(join(sourceRoot, 'i18n/locales/zh-CN.json'), 'utf8'));
const loaded = [['en.json', en], ['zh-CN.json', zh]];

const kinds = (source, files = loaded) =>
  scanSource('src/pages/Sample.tsx', source, files).map(violation => violation.kind);

function walk(directory) {
  const entries = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) entries.push(...walk(path));
    else if (statSync(path).isFile() && /\.tsx?$/.test(entry.name)) entries.push(path);
  }
  return entries;
}

describe('missing-locale-key', () => {
  it('reports a key no locale file carries', () => {
    expect(kinds(`<h1>{t('common.nope')}</h1>;`)).toEqual(['missing-locale-key']);
  });

  it('names the locale files that lack the key', () => {
    // The real en-only shape: zh-CN carried `common.next` and en.json did not,
    // so the English UI rendered the literal string on a button.
    const files = [
      ['en.json', { common: { previous: 'Previous' } }],
      ['zh-CN.json', { common: { previous: '上一页', next: '下一页' } }],
    ];
    const [violation] = scanSource(
      'src/pages/Sample.tsx',
      `<button>{t('common.next')}</button>;`,
      files,
    );
    expect(violation.message).toContain('missing from en.json');
  });

  it('accepts a key both locales carry', () => {
    expect(kinds(`<h1>{t('common.cancel')}</h1>;`)).toEqual([]);
  });

  it('does not read a dynamic call as a missing key', () => {
    // 6 real `t(`prefix.${x}`)` calls exist; a prefix is not a key.
    expect(kinds('<div>{t(`embeddings.${label}`)}</div>;')).toEqual([]);
  });

  it('is not fooled by a callee that merely ends in t', () => {
    // `searchParams.get('collectionKey')` is not a translation call. Reading
    // it as one put a false positive on `Documents.tsx:36`.
    expect(kinds(`searchParams.get('collectionKey') || undefined;`)).toEqual([]);
  });

  it('ignores a key that only appears in a comment', () => {
    expect(kinds(`// t('common.removed') is no longer used`)).toEqual([]);
  });
});

describe('dead-translation-fallback', () => {
  it('reports a guard that can never fire', () => {
    // i18next returns the key string for a missing key, and that string is
    // truthy, so `|| 'fallback'` never runs. Documents.tsx had four of these
    // and three of them were the only thing standing between a missing key and
    // a literal `documents.loadError` on screen.
    expect(kinds(`<h1>{t('common.nope') || 'Fallback'}</h1>;`)).toEqual(['dead-translation-fallback']);
  });

  it('reports a guard that falls back to another translation', () => {
    expect(kinds(`<h1>{t('documents.loadError') || t('common.error')}</h1>;`)).toEqual([
      'dead-translation-fallback',
    ]);
  });

  it('accepts a plain translation', () => {
    expect(kinds(`<h1>{t('common.error')}</h1>;`)).toEqual([]);
  });

  it('accepts a recorded, justified guard', () => {
    const source = `
      // i18n-allow: the platform team confirmed this key is unreachable
      <h1>{t('common.nope') || 'Fallback'}</h1>;
    `;
    const [violation] = scanSource('src/pages/Sample.tsx', source, loaded);
    expect(violation.detail).toContain('platform team');
  });
});

describe('locale-key-asymmetry', () => {
  it('reports a key present in one locale only', () => {
    const violations = compareLocales([
      ['en.json', { a: { keep: 'x', onlyEn: 'x' } }],
      ['zh-CN.json', { a: { keep: 'x' } }],
    ]);
    expect(violations.map(v => v.message)).toEqual(['a.onlyEn is in en.json but not in zh-CN.json']);
  });

  it('reports the reverse direction too', () => {
    const violations = compareLocales([
      ['en.json', { a: { keep: 'x' } }],
      ['zh-CN.json', { a: { keep: 'x', onlyZh: 'x' } }],
    ]);
    expect(violations.map(v => v.message)).toEqual(['a.onlyZh is in zh-CN.json but not in en.json']);
  });

  it('accepts two locales with identical key sets', () => {
    expect(compareLocales(loaded)).toEqual([]);
  });
});

describe('the locale files', () => {
  it('carry identical key sets', () => {
    expect(compareLocales(loaded)).toEqual([]);
  });

  it('actually carry keys, so the case above is not vacuous', () => {
    expect(flattenKeys(en).length).toBeGreaterThan(600);
    expect(flattenKeys(en).length).toBe(flattenKeys(zh).length);
  });

  it('translate the version-history pagination Batch 792 added', () => {
    // These rendered as the literal keys in English before this batch.
    expect(hasKey(en, 'common.next')).toBe(true);
    expect(hasKey(en, 'common.previous')).toBe(true);
    expect(en.common.next).not.toBe('common.next');
  });
});

describe('the real component tree', () => {
  const files = walk(sourceRoot).filter(path => !/\.(test|spec)\.[jt]sx?$/.test(path));
  const violations = files.flatMap(path =>
    scanSource(path.replace(`${projectRoot}/`, ''), readFileSync(path, 'utf8'), loaded),
  );

  it('resolves every translation key in both languages', () => {
    expect(violations).toEqual([]);
  });

  it('actually found translation calls to judge', () => {
    const source = readFileSync(join(sourceRoot, 'pages', 'Documents.tsx'), 'utf8');
    expect((source.match(/[^A-Za-z0-9_$.]t\(\s*'/g) ?? []).length).toBeGreaterThan(10);
  });
});

describe('kinds', () => {
  it('declares exactly the three rules this gate enforces', () => {
    expect([...VIOLATION_KINDS].sort()).toEqual([
      'dead-translation-fallback',
      'locale-key-asymmetry',
      'missing-locale-key',
    ]);
  });
});
