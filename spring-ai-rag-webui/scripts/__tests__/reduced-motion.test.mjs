import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkStylesheet, cssFiles } from '../check-reduced-motion.mjs';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));
const srcDir = join(projectRoot, 'src');

const selectors = (css) => checkStylesheet('T.css', css).map((f) => f.selector);

// The shape the rest of this repository already uses. A gate that rejects the
// shape it exists to enforce teaches everyone to ignore it, so this is the
// first case and it is not optional.
const guarded = `
.spinner {
  border: 2px solid var(--color-border);
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation: none;
  }
}
`;

describe('continuous motion needs a guard', () => {
  it('accepts the house pattern: infinite animation neutralized by the same selector', () => {
    expect(selectors(guarded)).toEqual([]);
  });

  it('accepts a guard written as animation-name: none', () => {
    expect(selectors(`
      .pulse { animation-name: shimmer; animation-duration: 1.5s; animation-iteration-count: infinite; }
      @media (prefers-reduced-motion: reduce) { .pulse { animation-name: none; } }
    `)).toEqual([]);
  });

  it('reports an unguarded infinite animation', () => {
    expect(selectors('.spinner { animation: spin 0.6s linear infinite; }')).toEqual(['.spinner']);
  });

  it('reports one when the guard covers a sibling selector but not this one', () => {
    expect(selectors(`
      .a { animation: spin 1s linear infinite; }
      .b { animation: spin 1s linear infinite; }
      @media (prefers-reduced-motion: reduce) { .b { animation: none; } }
    `)).toEqual(['.a']);
  });

  it('matches a comma-separated selector list against a single-selector guard', () => {
    expect(selectors(`
      .a, .b { animation: spin 1s linear infinite; }
      @media (prefers-reduced-motion: reduce) { .a { animation: none; } }
    `)).toEqual(['.b']);
  });

  it('does not accept a reduced-motion block that only shortens the animation', () => {
    // Narrowing 1.5s to 0.01s still leaves it moving, forever. Only `none`
    // stops it, which is why the rule looks at the value and not at the block.
    expect(selectors(`
      .skeleton { animation: shimmer 1.5s infinite; }
      @media (prefers-reduced-motion: reduce) { .skeleton { animation-duration: 0.01s; } }
    `)).toEqual(['.skeleton']);
  });

  it('does not count a comment that merely spells out prefers-reduced-motion', () => {
    // Batch 910's own guard comments name the media query in prose. Reading raw
    // text instead of stripping comments would accept a file with no guard at
    // all — the same trap `scripts/lib/java-source.mjs` was written to close on
    // the Java side, met for the second time in one batch by the same author.
    expect(selectors(`
      /* we should honour prefers-reduced-motion here one day */
      .skeleton { animation: shimmer 1.5s infinite; }
    `)).toEqual(['.skeleton']);
  });

  it('does not count a selector named inside a comment', () => {
    expect(selectors(`
      .spinner { animation: spin 1s linear infinite; }
      /* @media (prefers-reduced-motion: reduce) { .spinner { animation: none; } } */
    `)).toEqual(['.spinner']);
  });
});

describe('one-shot motion is deliberately not reported', () => {
  it('accepts a finite animation with no guard', () => {
    expect(selectors('.panel { animation: dialog-enter 140ms ease-out; }')).toEqual([]);
  });

  it('accepts a finite animation declared with longhands', () => {
    expect(selectors(`
      .toast { animation-name: slideIn; animation-duration: 0.2s; animation-iteration-count: 1; }
    `)).toEqual([]);
  });

  it('is not fooled by a transition that happens to be infinite', () => {
    // `infinite` inside a `transition` value is not a thing, but the word can
    // still appear in a custom property the rule reads.
    expect(selectors('.x { transition: color 1s var(--easing-infinite); }')).toEqual([]);
  });
});

describe('the real stylesheet tree', () => {
  const files = cssFiles(srcDir);

  it('finds stylesheets at all', () => {
    expect(files.length).toBeGreaterThan(20);
  });

  it('reports no unguarded continuous motion', () => {
    const findings = files.flatMap((file) =>
      checkStylesheet(file.replace(`${srcDir}/`, ''), readFileSync(file, 'utf8')));
    expect(findings.map((f) => `${f.selector} ${f.value}`)).toEqual([]);
  });

  it('actually contains continuous motion, so the rule above is not vacuous', () => {
    // A gate whose subject has disappeared would report nothing forever and
    // still pass. This asserts the subject is present.
    const all = files.map((f) => readFileSync(f, 'utf8')).join('\n');
    expect(all).toMatch(/animation[^;]*\binfinite\b/);
  });

  it('keeps every stylesheet under src/ in scope', () => {
    const names = files.map((f) => f.replace(`${srcDir}/`, ''));
    expect(names).toContain('components/Skeleton/Skeleton.module.css');
    expect(names).toContain('pages/Files.module.css');
  });
});
