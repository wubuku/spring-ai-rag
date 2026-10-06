import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
import {
  findIntlOutsideOwner,
  isSourceFile,
  OWNER,
  VIOLATION_KINDS,
} from '../check-number-formatting.mjs';

const webuiRoot = fileURLToPath(new URL('../..', import.meta.url));

// The cost this rule protects is invisible in review. `Metrics.tsx` had two
// `Intl.NumberFormat` constructions, one of them called inside a `.map()` over table
// rows, so every cell of the usage table built a formatter on every render. Nothing
// about that reads as a mistake and nothing about it fails a test — the output is
// byte-identical either way, which is exactly why the behaviour test stayed green when
// the cache was deleted and the cost test had to count constructions instead.
//
// So the non-findings below matter as much as the findings: a pattern that matches
// nothing is indistinguishable from a clean tree, and a rule that fires on the prose
// explaining itself is a rule that gets switched off.

describe('findIntlOutsideOwner', () => {
  it('reports the shape the real code had', () => {
    const files = [{
      path: 'src/pages/Metrics.tsx',
      text: [
        'function formatInteger(value) {',
        "  if (/^\\d+$/.test(text)) return new Intl.NumberFormat().format(BigInt(text));",
        '  return Number.isFinite(numeric) ? new Intl.NumberFormat().format(numeric) : text;',
        '}',
      ].join('\n'),
    }];

    const findings = findIntlOutsideOwner(files);

    expect(findings).toHaveLength(2);
    expect(findings[0].kind).toBe(VIOLATION_KINDS.INTL_OUTSIDE_OWNER);
    expect(findings[0].path).toBe('src/pages/Metrics.tsx');
    expect(findings.map(f => f.line)).toEqual([2, 3]);
  });

  it('reports a bare `Intl` reference, not only a construction', () => {
    const files = [{ path: 'src/a.ts', text: 'const N = Intl.NumberFormat;\n' }];
    expect(findIntlOutsideOwner(files)).toHaveLength(1);
  });

  it('does not report the one module allowed to do it', () => {
    const real = readFileSync(join(webuiRoot, OWNER), 'utf8');
    expect(findIntlOutsideOwner([{ path: OWNER, text: real }])).toEqual([]);
  });

  it('does not report a comment that quotes the rule', () => {
    // The file documenting this gate is itself scanned, and it names the pattern in
    // nearly every sentence. A gate that fails on its own header is a gate somebody
    // disables.
    const files = [{
      path: 'src/pages/Metrics.tsx',
      text: [
        '// Batch 940: new Intl.NumberFormat().format() per cell cost 348.7 ms,',
        '// against 4.9 ms for a reused formatter. Now formatCount() does it once.',
        'const value = formatCount(row.totals.totalTokens);',
      ].join('\n'),
    }];

    expect(findIntlOutsideOwner(files)).toEqual([]);
  });

  it('does not report the timestamp formatter, which is a different job', () => {
    // `utils/time.ts` calls `toLocaleString()` on a `Date` under the Batch 938 gate.
    // Two different formatters, two different owners, and neither is the other's
    // business — a rule that could not tell them apart would need an allowlist.
    const files = [{
      path: 'src/utils/time.ts',
      text: "export function formatAbsolute(value, locale) {\n  return date.toLocaleString(locale);\n}",
    }];

    expect(findIntlOutsideOwner(files)).toEqual([]);
  });

  it('leaves test files out of scope', () => {
    // A test has to be able to build a formatter, and `number.test.ts` spies on the
    // constructor to prove it is only built once.
    expect(isSourceFile('number.test.ts')).toBe(false);
    expect(isSourceFile('Metrics.test.tsx')).toBe(false);
    expect(isSourceFile('number.ts')).toBe(true);
    expect(isSourceFile('number.module.css')).toBe(false);
  });

  it('finds the violation in a real file with one line appended', () => {
    // A green sweep is the outcome this file exists to make suspicious, so the sweep
    // has to be shown capable of not being green against the bytes on disk. The file
    // used is one that genuinely has no `Intl` in it today, so the count is exactly
    // the appended line — a fixture that already contained the pattern would not
    // distinguish "the scanner works" from "the scanner counts".
    const real = readFileSync(join(webuiRoot, 'src/pages/Metrics.tsx'), 'utf8');
    expect(findIntlOutsideOwner([{ path: 'src/pages/Metrics.tsx', text: real }]))
      .toEqual([]);

    const findings = findIntlOutsideOwner([{
      path: 'src/pages/Metrics.tsx',
      text: `${real}\nconst relapse = new Intl.NumberFormat().format(n);\n`,
    }]);
    expect(findings).toHaveLength(1);
    expect(findings[0].path).toBe('src/pages/Metrics.tsx');
  });

  it('finds nothing in the file that now owns the construction', () => {
    const real = readFileSync(join(webuiRoot, OWNER), 'utf8');
    expect(findIntlOutsideOwner([{ path: OWNER, text: real }])).toEqual([]);
  });
});
