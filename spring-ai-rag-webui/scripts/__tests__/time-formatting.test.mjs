import { describe, it, expect } from 'vitest';
import {
  findInlineLocaleFormatting,
  FORMATTER,
  isSourceFile,
  VIOLATION_KINDS,
} from '../check-time-formatting.mjs';

// A gate that cannot fail is worse than no gate, and this rule was written after a
// defect that looked like a bug in TypeScript rather than in the UI: `ApiKeys.tsx`
// wrapped `new Date(dateStr).toLocaleString()` in a try/catch, which reads as though
// it handles a value it cannot read. It cannot — `new Date('garbage')` returns an
// Invalid Date and `toLocaleString()` returns the string "Invalid Date", so the catch
// was unreachable and the user saw those words.
//
// Most of what follows pins *non*-findings, because the two ways this rule can be
// wrong are both silent: a pattern that matches nothing (a green sweep over a file it
// cannot see) and a rule that fires on the explanation of itself.

describe('findInlineLocaleFormatting', () => {
  it('reports the nine shapes the real code had', () => {
    const files = [{
      path: 'src/pages/ApiKeys.tsx',
      text: [
        'function formatDateTime(dateStr?: string): string {',
        "  if (!dateStr) return '—';",
        '  try {',
        '    return new Date(dateStr).toLocaleString();',
        '  } catch {',
        '    return dateStr;',
        '  }',
        '}',
      ].join('\n'),
    }];
    const findings = findInlineLocaleFormatting(files);
    expect(findings).toHaveLength(1);
    expect(findings[0].kind).toBe(VIOLATION_KINDS.INLINE_LOCALE_FORMAT);
    expect(findings[0].path).toBe('src/pages/ApiKeys.tsx');
    expect(findings[0].line).toBe(4);
  });

  it('catches every member of the family, not just toLocaleString', () => {
    // The first version of the pattern named one method. `Documents.tsx` used
    // `toLocaleDateString` and `Search.tsx` used `toLocaleTimeString`, so a
    // single-method pattern would have reported a clean sweep over two of the nine.
    const files = [{
      path: 'src/pages/Documents.tsx',
      text: [
        'const a = new Date(x).toLocaleString();',
        'const b = new Date(x).toLocaleDateString();',
        'const c = new Date(x).toLocaleTimeString();',
        'const d = new Date(x).toLocaleTimeZone();',
        'const e = x.toLocaleString(  );',
      ].join('\n'),
    }];
    expect(findInlineLocaleFormatting(files)).toHaveLength(5);
  });

  it('does not report the formatter itself', () => {
    const files = [{
      path: FORMATTER,
      text: 'return date.toLocaleString(locale);',
    }];
    expect(findInlineLocaleFormatting(files)).toEqual([]);
  });

  it('does not read a comment as the violation', () => {
    // `ApiKeys.tsx` explains this very defect in a comment that spells
    // `toLocaleString()`. A scanner that reads prose counts the explanation.
    const files = [{
      path: 'src/pages/ApiKeys.tsx',
      text: [
        '// `new Date(dateStr).toLocaleString()` does not throw, it returns',
        '// the string "Invalid Date". That is why this file has a try/catch.',
        '/* and a block comment mentioning toLocaleDateString() too */',
        'const label = formatAbsolute(value, locale);',
      ].join('\n'),
    }];
    expect(findInlineLocaleFormatting(files)).toEqual([]);
  });

  it('still reports a real call that sits next to a comment about it', () => {
    const files = [{
      path: 'src/pages/ApiKeys.tsx',
      text: [
        '// do not call toLocaleString() here',
        'const value = new Date(x).toLocaleString();',
      ].join('\n'),
    }];
    const findings = findInlineLocaleFormatting(files);
    expect(findings).toHaveLength(1);
    expect(findings[0].line).toBe(2);
  });

  it('reports the line it is on', () => {
    const files = [{
      path: 'src/pages/Search.tsx',
      text: 'const a = 1;\nconst b = 2;\nconst c = new Date(t).toLocaleTimeString();\n',
    }];
    expect(findInlineLocaleFormatting(files)[0].line).toBe(3);
  });

  it('leaves the four formatter names alone', () => {
    const files = [{
      path: 'src/pages/Documents.tsx',
      text: [
        "import { formatDate } from '../utils/time';",
        'const cell = formatDate(doc.createdAt, i18n.language);',
      ].join('\n'),
    }];
    expect(findInlineLocaleFormatting(files)).toEqual([]);
  });
});

describe('isSourceFile', () => {
  it('skips tests, because a fixture has to be able to spell the bad output', () => {
    expect(isSourceFile('Search.tsx')).toBe(true);
    expect(isSourceFile('utils/time.ts')).toBe(true);
    expect(isSourceFile('time.test.ts')).toBe(false);
    expect(isSourceFile('Search.test.tsx')).toBe(false);
    expect(isSourceFile('styles.css')).toBe(false);
  });
});