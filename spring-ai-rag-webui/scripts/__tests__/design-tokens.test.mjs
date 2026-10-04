import { describe, it, expect } from 'vitest';
import { readFileSync, writeFileSync, mkdirSync, rmSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { buildOutputs, parseSource, renderCss, renderTs } from '../build-design-tokens.mjs';
import {
  compareToBaseline,
  countUnresolved,
  fingerprint,
  readBaseline,
  scanFile,
  scanSource,
} from '../check-design-system.mjs';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));
const sourceText = readFileSync(join(projectRoot, 'design-tokens/tokens.json'), 'utf8');
const model = parseSource(sourceText);

/** Minimal valid source used to exercise the validator in isolation. */
function source(overrides = {}) {
  return JSON.stringify({
    version: 1,
    groups: [
      {
        name: 'color',
        themed: true,
        tokens: [{ key: 'bg', var: '--color-bg', light: '#ffffff', dark: '#000000' }],
      },
    ],
    aliases: [],
    ...overrides,
  });
}

describe('token source validation', () => {
  it('parses the real source and exposes every canonical var', () => {
    expect(model.groups.length).toBeGreaterThan(0);
    expect(model.cssVarToToken.has('--color-bg')).toBe(true);
    expect(model.cssVarToToken.has('--color-on-primary')).toBe(true);
  });

  it('rejects a themed token that is missing its dark value', () => {
    const text = source({
      groups: [
        {
          name: 'color',
          themed: true,
          tokens: [{ key: 'bg', var: '--color-bg', light: '#ffffff' }],
        },
      ],
    });
    // JSON.stringify drops undefined, so the token simply lacks `dark`.
    expect(() => parseSource(text)).toThrow(/dark must be a non-empty string/);
  });

  it('rejects a non-themed token that declares light/dark', () => {
    const text = source({
      groups: [
        {
          name: 'space',
          themed: false,
          tokens: [{ key: '4', var: '--space-4', light: '4px', dark: '4px' }],
        },
      ],
    });
    expect(() => parseSource(text)).toThrow(/must not declare light\/dark/);
  });

  it('rejects a themed group that mixes in a single value', () => {
    const text = source({
      groups: [
        {
          name: 'color',
          themed: true,
          tokens: [{ key: 'bg', var: '--color-bg', light: '#fff', dark: '#000', value: 'x' }],
        },
      ],
    });
    expect(() => parseSource(text)).toThrow(/must not declare a single "value"/);
  });

  it('rejects a non-colour literal in a colour-valued themed group', () => {
    const text = source({
      groups: [
        {
          name: 'color',
          themed: true,
          tokens: [{ key: 'bg', var: '--color-bg', light: 'papayawhip', dark: '#000000' }],
        },
      ],
    });
    expect(() => parseSource(text)).toThrow(/must be a colour literal/);
  });

  it('accepts non-colour values in a themed group declared as scalar', () => {
    const text = source({
      groups: [
        {
          name: 'shadow',
          themed: true,
          valueKind: 'scalar',
          tokens: [{ key: 'dialog', var: '--shadow-dialog', light: '0 1px 2px #000', dark: '0 1px 2px #111' }],
        },
      ],
    });
    expect(() => parseSource(text)).not.toThrow();
  });

  it('rejects duplicate CSS custom property names across groups', () => {
    const text = source({
      groups: [
        { name: 'color', themed: false, tokens: [{ key: 'a', var: '--dup', value: '1px' }] },
        { name: 'space', themed: false, tokens: [{ key: 'b', var: '--dup', value: '2px' }] },
      ],
    });
    expect(() => parseSource(text)).toThrow(/reuses CSS custom property --dup/);
  });

  it('rejects duplicate TypeScript bridge keys within a group', () => {
    const text = source({
      groups: [
        {
          name: 'space',
          themed: false,
          tokens: [
            { key: '4', var: '--space-4', value: '4px' },
            { key: '4', var: '--space-four', value: '4px' },
          ],
        },
      ],
    });
    expect(() => parseSource(text)).toThrow(/duplicate TypeScript bridge key "space-4"/);
  });

  it('qualifies bridge keys with the group so "4" can exist twice safely', () => {
    const text = source({
      groups: [
        { name: 'space', themed: false, tokens: [{ key: '4', var: '--space-4', value: '4px' }] },
        { name: 'radius', themed: false, tokens: [{ key: '4', var: '--radius-4', value: '4px' }] },
      ],
    });
    const parsed = parseSource(text);
    const bridgeKeys = parsed.groups.flatMap(group => group.tokens.map(token => token.bridgeKey));
    expect(bridgeKeys.sort()).toEqual(['radius-4', 'space-4']);
  });
});

describe('alias integrity', () => {
  it('rejects an alias that points at another alias (cycle risk)', () => {
    const text = source({
      aliases: [
        { key: 'one', var: '--alias-one', ref: '--alias-two' },
        { key: 'two', var: '--alias-two', ref: '--color-bg' },
      ],
    });
    expect(() => parseSource(text)).toThrow(/is not a canonical token/);
  });

  it('rejects an alias pointing at an unknown var', () => {
    const text = source({ aliases: [{ key: 'x', var: '--alias-x', ref: '--nope' }] });
    expect(() => parseSource(text)).toThrow(/is not a canonical token/);
  });

  it('accepts an alias pointing at a canonical token', () => {
    const text = source({ aliases: [{ key: 'x', var: '--alias-x', ref: '--color-bg' }] });
    expect(() => parseSource(text)).not.toThrow();
  });
});

describe('generator output', () => {
  it('is deterministic: rendering twice yields byte-identical output', () => {
    const first = buildOutputs(sourceText);
    const second = buildOutputs(sourceText);
    expect(first.css).toBe(second.css);
    expect(first.ts).toBe(second.ts);
  });

  it('emits no timestamp that would break idempotency', () => {
    expect(renderCss(model)).not.toMatch(/\d{4}-\d{2}-\d{2}T/);
  });

  it('emits every themed token into both :root and the dark block', () => {
    const css = renderCss(model);
    const rootBlock = css.slice(css.indexOf(':root {'), css.indexOf('[data-theme="dark"]'));
    const darkBlock = css.slice(css.indexOf('[data-theme="dark"]'));

    for (const group of model.groups) {
      if (!group.themed) continue;
      for (const token of group.tokens) {
        expect(rootBlock).toContain(`${token.var}: ${token.light};`);
        expect(darkBlock).toContain(`${token.var}: ${token.dark};`);
      }
    }
  });

  it('emits non-themed tokens exactly once, in :root only', () => {
    const css = renderCss(model);
    const darkBlock = css.slice(css.indexOf('[data-theme="dark"]'));
    const spaceGroup = model.groups.find(group => group.name === 'space');
    expect(spaceGroup).toBeDefined();
    for (const token of spaceGroup.tokens) {
      expect(darkBlock).not.toContain(`${token.var}:`);
    }
  });

  it('keeps color-scheme in sync with the resolved theme', () => {
    const css = renderCss(model);
    expect(css).toContain('color-scheme: light;');
    expect(css).toContain('color-scheme: dark;');
  });

  it('emits aliases as var() references rather than duplicated values', () => {
    const css = renderCss(model);
    for (const alias of model.aliases) {
      expect(css).toContain(`${alias.var}: var(${alias.ref});`);
    }
  });

  it('keeps the TypeScript bridge free of palette value copies', () => {
    const ts = renderTs(model);
    // Names and var() references only: no hex colour may be duplicated into TS.
    expect(ts).not.toMatch(/#[0-9a-f]{3,8}\b/i);
    expect(ts).toContain('export const DESIGN_TOKEN_VARS');
    expect(ts).toContain('export const CHART_TOKEN_VARS');
    expect(ts).toContain("'chart-axis': '--chart-axis'");
  });

  it('keeps the bridge group-qualified while preserving existing CSS variable names', () => {
    const ts = renderTs(model);
    // The bridge key is group-qualified (`status-success`) but the CSS property
    // keeps its established `--color-*` name, so no page selector has to change.
    expect(ts).toContain("'status-success': '--color-success'");
    expect(ts).toContain("'space-4': '--space-4'");
    expect(ts).toContain("'radius-4': '--radius-4'");
  });

  it('matches the tracked generated outputs on disk', () => {
    const { outputs } = buildOutputs(sourceText);
    for (const { path, content } of outputs) {
      expect(readFileSync(path, 'utf8')).toBe(content);
    }
  });
});

describe('accessibility of filled surfaces', () => {
  /** Each filled surface, and the foreground token that is required on it. */
  const PAIRS = [
    { surface: 'primary', on: 'on-primary' },
    { surface: 'primary-hover', on: 'on-primary-hover' },
    { surface: 'error', on: 'on-error' },
    { surface: 'warning', on: 'on-warning' },
    { surface: 'success', on: 'on-success' },
    { surface: 'accent', on: 'on-accent' },
  ];

  // Filled surfaces live in the `color` group (primary) and the `status` group
  // (error/warning/success), so look a key up across every group.
  const valueOf = (key, theme) =>
    model.groups.flatMap(group => group.tokens).find(t => t.key === key)?.[theme];

  function channel(c) {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  }

  function luminance(hex) {
    const h = hex.replace('#', '');
    const [r, g, b] = [0, 2, 4].map(i => channel(parseInt(h.slice(i, i + 2), 16)));
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  }

  function contrast(a, b) {
    const la = luminance(a);
    const lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  it('defines an on-* foreground for every filled surface', () => {
    for (const { on } of PAIRS) {
      expect(valueOf(on, 'light'), `${on} light`).toBeTruthy();
      expect(valueOf(on, 'dark'), `${on} dark`).toBeTruthy();
    }
  });

  it.each(PAIRS)('$on clears WCAG AA (4.5:1) on $surface in both themes', ({ surface, on }) => {
    // 4.5:1 is the AA threshold for normal-size text. Button and badge labels
    // are not large text, so they are held to it rather than to 3:1.
    for (const theme of ['light', 'dark']) {
      const ratio = contrast(valueOf(surface, theme), valueOf(on, theme));
      expect(ratio, `${on} on ${surface} (${theme})`).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('never picks a foreground that reads worse than plain white', () => {
    // The rule is not "never white" — white is correct on a dark surface — but
    // "do not ship a worse option than the one that used to be hard-coded".
    // A vivid fill takes a dark label; a deep fill keeps white.
    for (const { surface, on } of PAIRS) {
      for (const theme of ['light', 'dark']) {
        const bg = valueOf(surface, theme);
        expect(contrast(bg, valueOf(on, theme)), `${on} on ${surface} (${theme})`).toBeGreaterThanOrEqual(
          contrast(bg, '#ffffff'),
        );
      }
    }
  });

  it('would have failed under the previous hard-coded white label', () => {
    // Records what this batch fixed, so the regression cannot be reintroduced
    // by simply putting `color: white` back.
    const regressed = PAIRS.filter(({ surface }) =>
      ['light', 'dark'].some(theme => contrast(valueOf(surface, theme), '#ffffff') < 4.5),
    );
    expect(regressed.map(r => r.surface)).toEqual(
      expect.arrayContaining(['primary', 'error', 'warning', 'success']),
    );
  });
});

describe('compatibility aliases', () => {
  it('are fully retired from the token source', () => {
    expect(model.aliases).toEqual([]);
  });

  it('emits no alias declarations into the stylesheet', () => {
    const css = renderCss(model);
    expect(css).not.toContain('Compatibility aliases');
  });
});

describe('design debt gate', () => {
  const context = { definedVars: new Set([...model.cssVarToToken, '--extra-defined']) };

  function scanFixture(fileName, body) {
    return scanSource(fileName, body, context);
  }

  it('flags an undefined CSS variable', () => {
    const violations = scanFixture(
      'src/pages/__Fixture.module.css',
      '.a { color: var(--not-a-token); }',
    );
    expect(violations.map(v => v.kind)).toContain('undefined-variable');
  });

  it('accepts a defined CSS variable', () => {
    const violations = scanFixture('src/pages/__Fixture.module.css', '.a { color: var(--color-bg); }');
    expect(violations.map(v => v.kind)).not.toContain('undefined-variable');
  });

  it('flags numeric z-index but not a layer token', () => {
    const numeric = scanFixture('src/pages/__Fixture.module.css', '.a { z-index: 7; }');
    expect(numeric.map(v => v.kind)).toContain('numeric-z-index');
    const tokenized = scanFixture('src/pages/__Fixture.module.css', '.a { z-index: var(--z-dialog); }');
    expect(tokenized.map(v => v.kind)).not.toContain('numeric-z-index');
  });

  it('flags raw colours including named colours the old gate missed', () => {
    expect(
      scanFixture('src/pages/__Fixture.module.css', '.a { color: #ff0000; }').map(v => v.kind),
    ).toContain('raw-color');
    expect(
      scanFixture('src/pages/__Fixture.module.css', '.a { background: white; }').map(v => v.kind),
    ).toContain('raw-color');
  });

  it('does not flag structural keywords or var() references as raw colours', () => {
    for (const value of ['transparent', 'currentColor', 'none', 'inherit', 'unset']) {
      const violations = scanFixture('src/pages/__Fixture.module.css', `.a { color: ${value}; }`);
      expect(violations.map(v => v.kind)).not.toContain('raw-color');
    }
  });

  it('flags transition: all', () => {
    const violations = scanFixture('src/pages/__Fixture.module.css', '.a { transition: all 0.2s; }');
    expect(violations.map(v => v.kind)).toContain('transition-all');
  });

  it('does not flag the banned patterns when they only appear inside a comment', () => {
    // A stylesheet must stay able to document the rule it is held to.
    const source = [
      '/* `transition: all` is banned design debt; colour: #ff0000 too. */',
      '.a { color: var(--color-text); }',
    ].join('\n');
    const violations = scanFixture('src/pages/__Fixture.module.css', source);
    expect(violations.map(v => v.kind)).not.toContain('transition-all');
    expect(violations.map(v => v.kind)).not.toContain('raw-color');
  });

  it('keeps line numbers aligned after stripping comments', () => {
    const source = ['/* one */', '/* two', ' * three */', '.a { color: var(--missing); }'].join('\n');
    const violations = scanFixture('src/pages/__Fixture.module.css', source);
    expect(violations.map(v => v.line)).toEqual([4]);
  });

  it('still reads an allow reason that lives in a stripped comment', () => {
    const source = '.a { color: #ff0000; } /* design-token-allow: vendor preview swatch */';
    const violations = scanFixture('src/pages/__Fixture.module.css', source);
    const rawColors = violations.filter(v => v.kind === 'raw-color');
    expect(rawColors).toHaveLength(1);
    expect(rawColors[0].allowed).toMatch(/vendor preview/);
  });

  it('flags non-zero letter-spacing but not zero', () => {
    expect(
      scanFixture('src/pages/__Fixture.module.css', '.a { letter-spacing: 0.05em; }').map(v => v.kind),
    ).toContain('letter-spacing');
    expect(
      scanFixture('src/pages/__Fixture.module.css', '.a { letter-spacing: 0; }').map(v => v.kind),
    ).not.toContain('letter-spacing');
  });

  it('flags !important without a stated reason', () => {
    const violations = scanFixture('src/pages/__Fixture.module.css', '.a { padding: 1px !important; }');
    expect(violations.map(v => v.kind)).toContain('important');
  });

  it('honours an inline design-token-allow exemption', () => {
    const source = [
      '.a { color: #ff0000; } /* design-token-allow: third-party vendor preview colour */',
    ].join('\n');
    const violations = scanFixture('src/pages/__Fixture.module.css', source);
    const rawColors = violations.filter(v => v.kind === 'raw-color');
    expect(rawColors).toHaveLength(1);
    expect(rawColors[0].allowed).toMatch(/third-party/);
  });

  it('rejects an exemption whose reason is too thin to be a real reason', () => {
    const source = '.a { color: #ff0000; } /* design-token-allow: ok */';
    const violations = scanFixture('src/pages/__Fixture.module.css', source);
    expect(violations.map(v => v.kind)).toContain('weak-allow-reason');
  });

  it('flags legacy alias call sites', () => {
    const violations = scanFixture('src/pages/__Fixture.module.css', '.a { color: var(--color-text-secondary); }');
    expect(violations.map(v => v.kind)).toContain('legacy-alias');
  });

  it('flags a page importing another page CSS module', () => {
    const violations = scanFixture('src/pages/__Fixture.tsx', "import s from '../pages/Other.module.css';");
    expect(violations.map(v => v.kind)).toContain('cross-page-import');
  });

  it('allows a page to import its own CSS module', () => {
    const violations = scanFixture('src/pages/__Fixture.tsx', "import s from './__Fixture.module.css';");
    expect(violations.map(v => v.kind)).not.toContain('cross-page-import');
  });

  it('does not charge style debt to test files, which never ship', () => {
    const violations = scanFixture(
      'src/pages/__Fixture.test.tsx',
      '.a { color: #ff0000; transition: all; }',
    );
    expect(violations).toHaveLength(0);
  });

  it('builds a stable fingerprint that ignores line numbers', () => {
    const a = fingerprint({ file: 'src/a.css', kind: 'raw-color', value: 'white', line: 3 });
    const b = fingerprint({ file: 'src/a.css', kind: 'raw-color', value: 'white', line: 99 });
    expect(a).toBe(b);
    expect(a).toBe('src/a.css|raw-color|white');
  });
});

describe('stylesheet syntax gate', () => {
  const context = { definedVars: new Set([...model.cssVarToToken]) };
  const syntax = (body, fileName = 'src/pages/__Fixture.module.css') =>
    scanSource(fileName, body, context).filter(v => v.kind === 'css-syntax');

  it('flags a stylesheet with a stray closing brace', () => {
    // The exact defect that shipped once: `FilePreview.module.css` carried an
    // extra `}`. typecheck, lint and all 765 Vitest cases passed on it, because
    // jsdom stubs CSS modules and no line-based rule can see a stray brace.
    const violations = syntax('.a { color: var(--color-text); }\n}\n');
    expect(violations).toHaveLength(1);
    expect(violations[0].value).toMatch(/Unexpected/);
  });

  it('reports the line the parser stopped at, not line 1', () => {
    const violations = syntax('.a { color: var(--color-text); }\n.b { color: var(--color-bg); }\n}\n');
    expect(violations[0].line).toBe(3);
  });

  it('accepts a well-formed stylesheet', () => {
    expect(
      syntax('.a { color: var(--color-text); }\n@media (min-width: 40rem) { .a { color: var(--color-bg); } }\n'),
    ).toEqual([]);
  });

  it('accepts braces and colons inside comments', () => {
    // Documentation inside a stylesheet must be able to show CSS.
    expect(
      syntax('/* example: .x { color: red; } */\n.a { color: var(--color-text); }\n'),
    ).toEqual([]);
  });

  it('flags an unterminated block', () => {
    expect(syntax('.a { color: var(--color-text);\n').length).toBeGreaterThan(0);
  });

  it('cannot be waived with design-token-allow', () => {
    // A stylesheet that does not load is not a style preference.
    const violations = syntax(
      '.a { color: var(--color-text); }\n}\n/* design-token-allow: vendor ships this broken */\n',
    );
    expect(violations).toHaveLength(1);
    expect(violations[0].allowed).toBeNull();
  });

  it('still parses a stylesheet whatever the file is called', () => {
    // The test-file exemption exists for JS suites that embed style snippets as
    // strings. A `.css` file on disk is a real stylesheet, so a parse failure is
    // a parse failure regardless of its name.
    expect(syntax('}\n', 'src/pages/__Fixture.test.css')).toHaveLength(1);
    expect(syntax('}\n', 'src/pages/__Fixture.spec.module.css')).toHaveLength(1);
    expect(
      scanSource(
        'src/pages/__Fixture.test.tsx',
        'const style = `.a { color: red; }`;\n}\n',
        context,
      ).filter(v => v.kind === 'css-syntax'),
    ).toEqual([]);
  });

  it('leaves the shipped stylesheets parseable', () => {
    const skip = new Set(['node_modules', 'dist', 'coverage']);
    const walk = directory =>
      readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
        if (entry.isDirectory()) return skip.has(entry.name) ? [] : walk(join(directory, entry.name));
        return entry.name.endsWith('.css') ? [join(directory, entry.name)] : [];
      });
    const broken = walk(join(projectRoot, 'src'))
      .flatMap(path => scanFile(path, context))
      .filter(v => v.kind === 'css-syntax')
      .map(v => `${v.file}:${v.line} ${v.value}`);
    expect(broken).toEqual([]);
  });
});

describe('emoji and dingbat gate', () => {
  const context = { definedVars: new Set([...model.cssVarToToken]) };

  function glyphs(fileName, body) {
    return scanSource(fileName, body, context).filter(v => v.kind === 'emoji-glyph');
  }

  it('flags a pictograph typed straight into JSX', () => {
    const violations = glyphs('src/pages/__Fixture.tsx', '<span>📁</span>');
    expect(violations).toHaveLength(1);
    expect(violations[0].value).toBe('📁');
  });

  it('flags a dingbat delivered through a string literal', () => {
    // This is the shape the gate most easily misses: the glyph never appears in
    // JSX text, it is a value picked by a conditional inside an expression.
    const violations = glyphs(
      'src/pages/__Fixture.tsx',
      "{open ? <ChevronUp /> : '⌄'}",
    );
    expect(violations.map(v => v.value)).toEqual(['⌄']);
  });

  it('flags a dingbat in every kind of quote', () => {
    expect(glyphs('src/pages/__Fixture.tsx', `const a = '↑';`)).toHaveLength(1);
    expect(glyphs('src/pages/__Fixture.tsx', `const a = "↑";`)).toHaveLength(1);
    expect(glyphs('src/pages/__Fixture.tsx', 'const a = `↑`;')).toHaveLength(1);
  });

  it('flags a glyph in CSS content', () => {
    expect(glyphs('src/pages/__Fixture.module.css', `.a::before { content: '📁'; }`)).toHaveLength(1);
  });

  it('does not flag prose arrows inside a line comment', () => {
    // The most common false positive this rule could have: documentation that
    // explains a data flow in Chinese using `→`. `App.tsx` is full of these.
    const violations = glyphs(
      'src/pages/__Fixture.tsx',
      '// 数据流：查询 → 过滤 → 排序；失败 → 重试。',
    );
    expect(violations).toHaveLength(0);
  });

  it('does not flag prose inside a block comment either', () => {
    const violations = glyphs(
      'src/pages/__Fixture.tsx',
      '/* 排序方向 ↑ 表示升序，↓ 表示降序。 */\n<span>ok</span>',
    );
    expect(violations).toHaveLength(0);
  });

  it('treats // inside a string as string content, not a comment', () => {
    // If the scanner were naive, the `//` in this URL would start a "comment"
    // and mask the glyph, silently hiding a real violation.
    const violations = glyphs('src/pages/__Fixture.tsx', `const u = 'https://x.dev/📁';`);
    expect(violations.map(v => v.value)).toEqual(['📁']);
  });

  it('does not flag layout characters that are not icons', () => {
    // Box drawing, CJK punctuation and typographic marks are prose, not UI.
    for (const ch of ['─', '·', '—', '、', '。', '°', '§', '€']) {
      expect(glyphs('src/pages/__Fixture.tsx', `const s = '${ch}';`)).toHaveLength(0);
    }
  });

  it('flags the multiplication sign, which reads as a close affordance', () => {
    // Four hand-rolled close buttons shipped as a bare `×`. It is visually an
    // icon even though it predates Unicode pictographs, so it must not slip
    // through a pictograph-only rule.
    expect(glyphs('src/pages/__Fixture.tsx', '<button>×</button>').map(v => v.value)).toEqual(['×']);
  });

  it('reports a presentation selector once, attached to its base glyph', () => {
    // '⚠️' is two code points. Counting both would make the fingerprint depend
    // on whether the author typed the selector, not on what is rendered.
    const violations = glyphs('src/pages/__Fixture.tsx', `const s = '⚠️';`);
    expect(violations.map(v => v.value)).toEqual(['⚠']);
  });

  it('does not charge glyph debt to test files', () => {
    expect(glyphs('src/pages/__Fixture.test.tsx', '<span>☰</span>')).toHaveLength(0);
  });

  it('honours an inline design-token-allow exemption', () => {
    const violations = glyphs(
      'src/pages/__Fixture.tsx',
      '// design-token-allow: pasted verbatim from the upstream chart legend\n<span>★</span>',
    );
    expect(violations).toHaveLength(1);
    expect(violations[0].allowed).toMatch(/upstream/);
  });

  it('leaves the shipped source tree free of interface glyphs', () => {
    // The regression guard the earlier batches lacked. A previous pass claimed
    // the tree was clean using a narrower pattern and missed every dingbat.
    const skip = new Set(['node_modules', 'dist', 'coverage', 'playwright-report', 'test-results']);
    const walk = directory =>
      readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
        if (entry.isDirectory()) return skip.has(entry.name) ? [] : walk(join(directory, entry.name));
        return /\.(css|ts|tsx|svg)$/.test(entry.name) ? [join(directory, entry.name)] : [];
      });
    const realContext = { definedVars: new Set([...model.cssVarToToken]) };
    const found = walk(join(projectRoot, 'src'))
      .flatMap(path => scanFile(path, realContext))
      .filter(v => v.kind === 'emoji-glyph')
      .map(v => `${v.file}:${v.line} ${v.value}`);
    expect(found).toEqual([]);
  });
});

describe('design-language document tracks the gate', () => {
  // The document enumerates the violation classes in prose. That list is a
  // hand-maintained copy of a machine-maintained one, which is exactly the
  // shape that rots: this batch shipped a document claiming "ten classes"
  // while the checker enforced eleven, and `weak-allow-reason` was simply
  // missing. Nothing failed. So the copy is now checked against the original.
  const checkerSource = readFileSync(join(projectRoot, 'scripts/check-design-system.mjs'), 'utf8');

  const enforcedKinds = () =>
    [...new Set([...checkerSource.matchAll(/kind: '([a-z-]+)'/g)].map(match => match[1]))].sort();

  const documentedKinds = (relativePath) => {
    const text = readFileSync(join(projectRoot, '..', relativePath), 'utf8');
    const section = text.match(/## 4\.[^\n]*\n[\s\S]*?\n### 4\.1/);
    if (!section) throw new Error(`${relativePath} has no section 4 listing the rule kinds`);
    return [...new Set([...section[0].matchAll(/^\d+\. `([a-z-]+)`/gm)].map(match => match[1]))].sort();
  };

  it('finds every kind the checker can emit', () => {
    // Guards the extraction itself: a regex that silently matches nothing would
    // make every assertion below pass for the wrong reason.
    expect(enforcedKinds().length).toBeGreaterThanOrEqual(10);
    expect(enforcedKinds()).toContain('css-syntax');
    expect(enforcedKinds()).toContain('weak-allow-reason');
  });

  it('documents exactly the enforced kinds, in both languages', () => {
    const enforced = enforcedKinds();
    expect(documentedKinds('docs/webui-design-language.md')).toEqual(enforced);
    expect(documentedKinds('docs/webui-design-language-zh-CN.md')).toEqual(enforced);
  });
});

describe('Batch 878: the exemption that was a no-op', () => {
  // `scanFile` recorded an inline `design-token-allow:` reason on every
  // violation it found and nothing ever read that field except the line
  // deciding whether to print a hint — the counts the gate fails on were built
  // from *all* violations. So the documented escape hatch did not escape, and
  // the gate's own error message told the reader to go and write one. The real
  // tree has never used it, which is the only reason this survived.
  const found = scanSource('src/a.module.css', '.a { z-index: 3; }', { definedVars: new Set() });
  const waived = scanSource(
    'src/a.module.css',
    '/* design-token-allow: stacking order belongs to the z-index scale */\n.a { z-index: 3; }',
    { definedVars: new Set() },
  );

  it('records the reason it was given', () => {
    // The positive control: the gate did parse the comment, so "the exemption
    // does not work" cannot be explained by the comment being missed.
    expect(found.filter(v => v.kind === 'numeric-z-index')[0].allowed).toBe(null);
    expect(waived.filter(v => v.kind === 'numeric-z-index')[0].allowed)
      .toBe('stacking order belongs to the z-index scale');
  });

  it('leaves an unwaived violation in the counts', () => {
    expect([...countUnresolved(found).values()]).toEqual([1]);
  });

  it('takes a waived violation out of the counts', () => {
    expect(countUnresolved(waived).size).toBe(0);
  });

  it('keeps a weak reason as a violation in its own right', () => {
    // `weak-allow-reason` is recorded with `allowed: null` precisely so that a
    // two-word justification cannot quietly buy the line it was attached to.
    const weak = scanSource(
      'src/a.module.css',
      '/* design-token-allow: ok */\n.a { z-index: 3; }',
      { definedVars: new Set() },
    );
    expect(countUnresolved(weak).size).toBe(1);
    expect(weak.map(v => v.kind)).toContain('weak-allow-reason');
  });

  it('reports a thin reason once, not once per line it governs', () => {
    // A comment written *above* its declaration used to be read on both lines,
    // producing two `weak-allow-reason` records with different `value` fields —
    // two baseline fingerprints for one bad sentence. The same-line form never
    // showed it, which is why the existing case had never caught it.
    const above = scanSource(
      'src/a.module.css',
      '/* design-token-allow: ok */\n.a { z-index: 3; }',
      { definedVars: new Set() },
    );
    expect(above.filter(v => v.kind === 'weak-allow-reason')).toHaveLength(1);
    const inline = scanSource(
      'src/a.module.css',
      '.a { z-index: 3; } /* design-token-allow: ok */',
      { definedVars: new Set() },
    );
    expect(inline.filter(v => v.kind === 'weak-allow-reason')).toHaveLength(1);
  });

  it('still waives a line whose comment sits on the line above it', () => {
    // The other half of the same change: a *good* reason above the declaration
    // has to keep working, or the fix would have cost more than it bought.
    const good = scanSource(
      'src/a.module.css',
      '/* design-token-allow: stacking order belongs to the z-index scale */\n.a { z-index: 3; }',
      { definedVars: new Set() },
    );
    expect(good.map(v => v.kind)).not.toContain('weak-allow-reason');
    expect(countUnresolved(good).size).toBe(0);
  });
});

describe('Batch 878: the debt contract, which had no test at all', () => {
  // Around seventy lines of gate logic — "counts may only decrease", "a stale
  // over-sized entry fails", "an unreadable baseline is a failed gate, not an
  // empty one" — with three promises made loudly in comments and not one
  // assertion anywhere. The repo has a documented habit of shipping a gate that
  // cannot fail, so these are the cases that would have caught it.
  const key = 'src/a.module.css|numeric-z-index|.a { z-index: 3; }';
  const sample = { file: 'src/a.module.css', kind: 'numeric-z-index', value: '.a { z-index: 3; }', line: 7, allowed: null };

  it('fingerprints by file, kind and value', () => {
    expect(fingerprint(sample)).toBe(key);
    expect(fingerprint({ ...sample, value: 'other' })).not.toBe(key);
  });

  it('passes when the code has exactly what the baseline allows', () => {
    expect(compareToBaseline(new Map([[key, 2]]), { [key]: 2 }, [sample])).toEqual([]);
  });

  it('fails when the code has grown past the baseline', () => {
    const errors = compareToBaseline(new Map([[key, 3]]), { [key]: 2 }, [sample]);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain('3 occurrence(s), baseline allows 2');
    expect(errors[0]).toContain('first at src/a.module.css:7');
  });

  it('fails on a fingerprint the baseline has never heard of', () => {
    expect(compareToBaseline(new Map([[key, 1]]), {}, [sample])).toHaveLength(1);
  });

  it('fails when the baseline is stale, so paid-off debt cannot hide', () => {
    // The half of the contract that was the whole reason readBaseline refuses
    // to collapse "corrupt" into "empty": an entry left at 2 after the code
    // dropped to 1 would otherwise let a new violation back in under cover.
    const errors = compareToBaseline(new Map([[key, 1]]), { [key]: 2 }, [sample]);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain('baseline is stale (allows 2, found 1)');
  });

  it('treats an absent baseline file as no debt, and reads the path it was given', () => {
    // The path assertion is the point. The checked-in baseline happens to be
    // `{ version: 1, entries: {} }`, so asserting only the absent-file result
    // passed on the old implementation *by coincidence* — it was returning the
    // real file and the expectation matched it. Sitting a populated file beside
    // the missing one is what separates "read what I asked for" from "read the
    // constant regardless".
    const dir = join(tmpdir(), `b878-absent-${process.pid}`);
    rmSync(dir, { recursive: true, force: true });
    mkdirSync(dir, { recursive: true });
    try {
      const populated = join(dir, 'populated.json');
      writeFileSync(populated, JSON.stringify({ version: 1, entries: { [key]: 7 } }));
      expect(readBaseline(join(dir, 'missing.json'))).toEqual({ version: 1, entries: {} });
      expect(readBaseline(populated).entries).toEqual({ [key]: 7 });
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it('refuses to treat a corrupt baseline as no debt', () => {
    // This is the distinction the whole function exists for. Reading it as
    // "nothing to check" would disable the staleness check above, which is how
    // debt comes back quietly.
    const corrupt = join(tmpdir(), `b878-corrupt-${process.pid}.json`);
    try {
      writeFileSync(corrupt, '{ not json');
      expect(() => readBaseline(corrupt)).toThrow(/not valid JSON/);
      writeFileSync(corrupt, JSON.stringify({ version: 1, entries: { [key]: 4 } }));
      expect(readBaseline(corrupt).entries).toEqual({ [key]: 4 });
    } finally {
      rmSync(corrupt, { force: true });
    }
  });

  it('reads the checked-in baseline, which records no debt at all', () => {
    // Anchors the real file rather than a fixture: the point of Batch 795 was
    // driving design debt to zero, and a baseline that quietly grew again would
    // be invisible unless something reads the real one.
    const real = JSON.parse(
      readFileSync(join(projectRoot, 'design-tokens', 'design-debt-baseline.json'), 'utf8'),
    );
    expect(real.entries).toEqual({});
  });
});
