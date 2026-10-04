import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync, mkdtempSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { scanSource, compareLocales, flattenKeys, hasKey, collectReferencedKeys, VIOLATION_KINDS } from '../check-i18n-keys.mjs';

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
    expect(kinds(`<h1>{t('documents.loadError') || t('common.unknownError')}</h1>;`)).toEqual([
      'dead-translation-fallback',
    ]);
  });

  // Batch 874: this fixture used `common.error`, which stopped existing when the
  // only line that rendered it — a failed key listing printed as an empty state —
  // was fixed. The key set is data these cases read, so deleting a key can
  // invalidate them; `common.unknownError` carries the same meaning for the
  // rule and cannot be deleted by an unrelated fix.
  it('accepts a plain translation', () => {
    expect(kinds(`<h1>{t('common.unknownError')}</h1>;`)).toEqual([]);
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
  it('declares exactly the four rules this gate enforces', () => {
    // This assertion exists so the rule list cannot drift unnoticed. Batch 818
    // added the fourth; the guard is what made that visible in one place
    // instead of as a surprise in a CI log.
    expect([...VIOLATION_KINDS].sort()).toEqual([
      'dead-locale-key',
      'dead-translation-fallback',
      'locale-key-asymmetry',
      'missing-locale-key',
    ]);
  });
});

// ── Batch 818: proving a key is dead ──────────────────────────────────────
//
// The gate reported "176 keys are reached only through dynamic template calls
// or are unused" for as long as anyone could remember. That number merged two
// populations, one of which is a real finding and one of which is not, so
// nobody could act on it and 50 dead keys survived inside it.
//
// These cases pin the five ways a key reaches t() without appearing as its own
// literal argument, because each one was a false "dead" in the first census.

describe('a key that reaches t() indirectly', () => {
  const keys = new Set([
    'nav.dashboard', 'search.matchChannel.hybrid', 'theme.dark',
    'documents.lifecycle.READY', 'search.resultsCount_one', 'search.resultsCount_other',
    'evaluation.tabReport', 'collectionScope.callerVisible', 'documents.delete',
  ]);

  const referenced = code => collectReferencedKeys(code, keys);

  it('keeps a key held in a lookup table alive', () => {
    const code = "const MAP = { CALLER_VISIBLE: 'collectionScope.callerVisible' };";
    expect([...referenced(code)]).toContain('collectionScope.callerVisible');
  });

  it('keeps a key used as a data-array member alive', () => {
    const code = "const TABS = ['report', 'evaluation.tabReport'];";
    expect([...referenced(code)]).toContain('evaluation.tabReport');
  });

  it('keeps a key behind a template prefix alive', () => {
    const code = "t(`search.matchChannel.${channel}`);";
    expect([...referenced(code)]).toEqual(expect.arrayContaining(['search.matchChannel.hybrid']));
  });

  it('keeps a key behind a template on an aliased translate alive', () => {
    // `translate` is `t` handed down as a parameter; the rule must not care
    // what the callee is called.
    const code = "return translate(`documents.lifecycle.${value}`);";
    expect([...referenced(code)]).toContain('documents.lifecycle.READY');
  });

  it('keeps a plural family alive from its base call', () => {
    const code = "t('search.resultsCount', { count: results.length })";
    expect([...referenced(code)]).toEqual(
      expect.arrayContaining(['search.resultsCount_one', 'search.resultsCount_other']),
    );
  });

  it('still reads a plain literal call', () => {
    expect([...referenced("t('documents.delete')")]).toContain('documents.delete');
  });

  it('reports a key nothing touches', () => {
    expect([...referenced("const x = 'unrelated';")]).not.toContain('nav.dashboard');
  });

  it('does not let an empty template prefix claim the whole locale', () => {
    // The first version of this rule allowed an empty prefix, and
    // `key.startsWith('')` is true for every key — so one t(`${x}`) anywhere
    // marked all 745 alive and the rule passed while doing nothing. A gate
    // that cannot fail is the exact failure this repository keeps meeting.
    const referencedByEmpty = [...referenced("t(`${x}`)")];
    expect(referencedByEmpty).toEqual([]);
  });

  it('does not let an unrelated literal invent a reference', () => {
    // The literal criterion only counts strings that are real keys, so
    // 'collection' as a scope value cannot keep 'chat.collection' alive.
    expect([...referenced("const scope = 'collection';")])
      .not.toContain('chat.collection');
  });

  it('ignores a key that only appears inside a comment', () => {
    // The caller strips comments; this pins that the contract is "caller
    // strips", so the self-test does not need to re-implement it.
    expect([...referenced("t('nav.dashboard')")]).toContain('nav.dashboard');
  });
});

describe('the locale files as delivered', () => {
  it('carry no key that source cannot reach', () => {
    const localeKeys = new Set(flattenKeys(en));
    const referenced = new Set();
    const walkSrc = dir => readdirSync(dir, { withFileTypes: true }).flatMap(e => {
      const p = join(dir, e.name);
      if (e.isDirectory()) return walkSrc(p);
      return /\.tsx?$/.test(e.name) && !/\.(test|spec)\./.test(e.name) ? [p] : [];
    });
    for (const file of walkSrc(sourceRoot)) {
      for (const key of collectReferencedKeys(readFileSync(file, 'utf8'), localeKeys)) {
        referenced.add(key);
      }
    }
    const dead = [...localeKeys].filter(k => !referenced.has(k));
    expect(dead).toEqual([]);
  });
});

// ── the gate end to end ──────────────────────────────────────────────────
//
// Mutation W5 emptied the loop that reports dead keys and all thirty cases
// stayed green, because every one of them called collectReferencedKeys
// directly. The helper was fine; the gate had stopped failing and nothing
// noticed. These cases run the script as a child process and assert the exit
// code, which is the only thing CI sees.

const GATE = fileURLToPath(new URL('../check-i18n-keys.mjs', import.meta.url));

function runGate(files) {
  const dir = mkdtempSync(join(tmpdir(), 'i18n-gate-'));
  mkdirSync(join(dir, 'i18n/locales'), { recursive: true });
  for (const [name, body] of Object.entries(files)) {
    const target = join(dir, name);
    mkdirSync(join(target, '..'), { recursive: true });
    writeFileSync(target, body, 'utf8');
  }
  const result = spawnSync(process.execPath, [GATE], {
    env: { ...process.env, I18N_SOURCE_ROOT: dir },
    encoding: 'utf8',
  });
  rmSync(dir, { recursive: true, force: true });
  return result;
}

const locales = (extra = {}) => ({
  'i18n/locales/en.json': JSON.stringify({ nav: { dashboard: 'Dashboard' }, dead: { key: 'Gone' }, ...extra }),
  'i18n/locales/zh-CN.json': JSON.stringify({ nav: { dashboard: '仪表盘' }, dead: { key: '没了' }, ...extra }),
});

/** The same tree with the deliberately dead key removed, for passing cases. */
const cleanLocales = (extra = {}) => ({
  'i18n/locales/en.json': JSON.stringify({ nav: { dashboard: 'Dashboard' }, ...extra }),
  'i18n/locales/zh-CN.json': JSON.stringify({ nav: { dashboard: '仪表盘' }, ...extra }),
});

describe('the gate as a process', () => {
  it('fails on a locale key no source can reach', () => {
    const result = runGate({
      ...locales(),
      'Page.tsx': "export const x = t('nav.dashboard');",
    });
    expect(result.status).toBe(1);
    expect(result.stdout + result.stderr).toMatch(/dead-locale-key/);
    expect(result.stdout + result.stderr).toMatch(/dead\.key/);
  });

  it('passes when every key is reachable', () => {
    const result = runGate({
      ...cleanLocales(),
      'Page.tsx': "export const x = t('nav.dashboard');",
    });
    expect(result.status).toBe(0);
  });

  it('passes a key held in a lookup table', () => {
    const result = runGate({
      ...cleanLocales(),
      'Page.tsx': "const MAP = { DASH: 'nav.dashboard' }; export const y = t(MAP.DASH);",
    });
    expect(result.status).toBe(0);
  });

  it('passes a key behind a template prefix', () => {
    const result = runGate({
      ...cleanLocales({ nav: { dashboard: 'Dashboard', alerts: 'Alerts' } }),
      'Page.tsx': "export const x = t(`nav.${page}`);",
    });
    expect(result.status).toBe(0);
  });

  // Mutation M3 dropped the `localeKeys.has()` filter around the literal test
  // and all thirty-five cases stayed green: no case asserted the number the
  // gate prints. The filter cannot change the pass/fail decision — a key that
  // is not in the locale is never asked about — so it only reaches the reader
  // through this line, and dropping it turned "695 keys referenced" into
  // "994" on a tree that had not changed at all. A gate that cannot miscount
  // is worth as much as a gate that cannot miss, because the count is the part
  // a human actually reads.
  it('counts no key outside the locale as referenced', () => {
    const result = runGate({
      ...cleanLocales({ nav: { dashboard: 'Dashboard', alerts: 'Alerts' } }),
      'Page.tsx': [
        "const MAP = { DASH: 'nav.dashboard', ALERTS: 'nav.alerts' };",
        "const ROUTE = '/collections';",
        "const FLAG = 'some.other.key';",
        'export const y = t(MAP.DASH);',
      ].join('\n'),
    });
    expect(result.status).toBe(0);
    expect(result.stdout).toMatch(/2 key\(s\) referenced/);
    expect(result.stdout).not.toMatch(/3 key\(s\) referenced/);
  });
});

describe('Batch 879: what an i18n-allow comment is, pinned', () => {
  // The stance was the defect. `i18n-allow` was recorded and printed beside the
  // finding, and the error message listed it as one of three remedies — while
  // the gate went on failing, because the finding was still counted. An
  // end-to-end probe on a real tree showed exactly that: the comment read back
  // as `[allowed: …]` and the exit code stayed at 1.
  //
  // These cases assert the decision in both directions. The first stops anyone
  // "fixing" the note into a pass. The second stops the note rotting into dead
  // code by making it look like it does nothing at all — it is recorded and
  // shown to a reviewer, and that is the whole of its job.
  const missing = {
    ...cleanLocales(),
    'Page.tsx': "export const y = t('nav.absent');",
  };
  const annotated = {
    ...missing,
    'Page.tsx':
      '/* i18n-allow: registered at runtime by the tenant bootstrap */\n'
      + "export const y = t('nav.absent');",
  };

  it('does not let a note turn a missing key into a pass', () => {
    expect(runGate(annotated).status).toBe(1);
  });

  it('fails the same tree without the note, so the two are comparable', () => {
    // Without this, "status 1" above could just mean the fixture is broken.
    expect(runGate(missing).status).toBe(1);
  });

  it('shows the reason to the reviewer, so the note is not dead code', () => {
    const result = runGate(annotated);
    expect(result.stderr).toMatch(/\[allowed: registered at runtime by the tenant bootstrap\]/);
    expect(result.stderr).not.toMatch(/\[allowed: \]/);
  });

  it('stops offering the note as a remedy', () => {
    // The sentence that made the bug: "Add the key to every locale, drop the
    // guard, or record an inline /* i18n-allow: … */".
    const result = runGate(missing);
    expect(result.stderr).not.toMatch(/or record an inline/);
    expect(result.stderr).toMatch(/is NOT a remedy/);
  });

  it('passes once the key is actually added', () => {
    // The positive control for the whole family: the gate is not refusing to
    // pass, it is refusing to pass *this*.
    expect(runGate({ ...cleanLocales(), 'Page.tsx': "export const y = t('nav.dashboard');" }).status)
      .toBe(0);
  });

  it('can carry a note on a dead fallback, which is the same kind of defect', () => {
    const fallbacks = {
      ...cleanLocales(),
      'Page.tsx':
        '/* i18n-allow: the guard is for a future runtime that can return empty */\n'
        + "export const y = t('nav.dashboard') || 'Dashboard';",
    };
    const result = runGate(fallbacks);
    expect(result.status).toBe(1);
    expect(result.stderr).toMatch(/\[allowed: the guard is for a future runtime/);
  });
});
