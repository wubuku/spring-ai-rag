import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildOutputs, parseSource, renderCss, renderTs } from '../build-design-tokens.mjs';
import { fingerprint, scanSource } from '../check-design-system.mjs';

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
