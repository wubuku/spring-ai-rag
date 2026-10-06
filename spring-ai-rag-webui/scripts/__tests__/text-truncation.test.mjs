import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
import {
  findNumericSubstrings,
  isSourceFile,
  VIOLATION_KINDS,
} from '../check-text-truncation.mjs';

const webuiRoot = fileURLToPath(new URL('../..', import.meta.url));

// Fifteen call sites cut strings with four hard-coded numbers, and the same three display
// decisions reached the screen three different ways — a real `…` in one component, three
// ASCII dots in another, three ASCII dots written by hand inside a ternary in a third,
// and nothing at all in a fourth, so a reader could not tell a truncated value from a
// short one.
//
// The rule is judged by the shape of the call's arguments, so the interesting cases are
// the near misses: a named constant, a single argument, a computed bound. Those are what
// separate a rule that means something from one that fires on everything.

describe('findNumericSubstrings', () => {
  it('reports each of the four numbers the real code had', () => {
    const files = [{
      path: 'src/pages/Documents.tsx',
      text: [
        'const normalized = value.trim().slice(0, 256);',
        'const value = e.target.value.slice(0, 256);',
        '<td>{doc.contentHash?.slice(0, 8)}...</td>',
        "const title = msg.content.slice(0, 50) + (msg.content.length > 50 ? '...' : '');",
        "const expires = principal.expiresAt?.slice(0, 16) ?? '';",
        "const [a, b] = params.get('q')?.trim().substring(0, 256) ?? '';",
        "const c = legacy.substr(2, 4);",
      ].join('\n'),
    }];

    const findings = findNumericSubstrings(files);

    expect(findings).toHaveLength(7);
    expect(findings.every(f => f.kind === VIOLATION_KINDS.NUMERIC_SUBSTRING)).toBe(true);
    expect(findings.map(f => f.line)).toEqual([1, 2, 3, 4, 5, 6, 7]);
  });

  it('does not report a cut at a named constant', () => {
    // This is the shape the whole tree was moved to, and the reason the gate ended up
    // with no allowlist: `truncate`, `capLength` and `toDateTimeLocalValue` all cut at an
    // identifier, so `utils/text.ts` and `utils/time.ts` need no exemption.
    const files = [{
      path: 'src/utils/text.ts',
      text: [
        'export function truncate(text: string, maxLength: number): string {',
        '  return `${text.slice(0, maxLength)}${ELLIPSIS}`;',
        '}',
        'export function capLength(text: string, max = MAX_QUERY_LENGTH): string {',
        '  return text.slice(0, max);',
        '}',
      ].join('\n'),
    }];

    expect(findNumericSubstrings(files)).toEqual([]);
  });

  it('does not report a single-argument cut, a negative bound, or a computed one', () => {
    const files = [{
      path: 'src/a.ts',
      text: [
        'const rest = line.slice(8);',
        'const tail = list.slice(-3);',
        'const head = s.slice(0, n);',
        'const width = s.substring(0, maxLength + 1);',
        'const from = s.slice(0);',
      ].join('\n'),
    }];

    expect(findNumericSubstrings(files)).toEqual([]);
  });

  it('does not report an unrelated call that takes two numbers', () => {
    // The pattern is anchored on the string methods, so a two-number argument elsewhere
    // is not this gate's business. A rule that fires on what it does not mean is a rule
    // that gets exempted.
    const files = [{
      path: 'src/a.ts',
      text: [
        'const padded = value.padStart(2, "0");',
        'const spaced = line.padEnd(80, " ");',
        'const fixed = Number(value).toFixed(2);',
        'const joined = array.splice(0, 8);',
      ].join('\n'),
    }];

    expect(findNumericSubstrings(files)).toEqual([]);
  });

  it('does not report a comment that names the numbers', () => {
    // The first draft of `check-text-truncation.mjs` opens with a table containing
    // `slice(0, 256)` written out in full, which its own scanner would have flagged the
    // moment the header became a scanned file. Comments are stripped for that reason.
    const files = [{
      path: 'src/pages/Files.tsx',
      text: [
        '// Batch 939 removed nine slice(0, 256) calls and three slice(0, 8) calls.',
        "const normalized = capLength(params.get('q')?.trim() ?? '');",
      ].join('\n'),
    }];

    expect(findNumericSubstrings(files)).toEqual([]);
  });

  it('leaves test files out of scope', () => {
    expect(isSourceFile('text.test.ts')).toBe(false);
    expect(isSourceFile('Files.test.tsx')).toBe(false);
    expect(isSourceFile('text.ts')).toBe(true);
    expect(isSourceFile('text.module.css')).toBe(false);
  });

  it('finds the violation in a real file with one line appended', () => {
    // A green sweep is the outcome this file exists to make suspicious, so the sweep has
    // to be shown capable of not being green against the bytes actually on disk.
    for (const target of ['src/utils/text.ts', 'src/utils/time.ts', 'src/pages/Chat.tsx']) {
      const real = readFileSync(join(webuiRoot, target), 'utf8');
      const findings = findNumericSubstrings([{
        path: target,
        text: `${real}\nconst relapse = 'abcdefghijkl'.slice(0, 8);\n`,
      }]);
      expect(findings, target).toHaveLength(1);
      expect(findings[0].path).toBe(target);
    }
  });

  it('finds nothing in the two files that used to hold the numbers', () => {
    for (const target of ['src/utils/text.ts', 'src/utils/time.ts']) {
      const real = readFileSync(join(webuiRoot, target), 'utf8');
      expect(findNumericSubstrings([{ path: target, text: real }]), target).toEqual([]);
    }
  });
});
