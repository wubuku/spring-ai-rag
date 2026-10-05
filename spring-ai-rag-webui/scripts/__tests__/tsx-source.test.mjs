import { describe, it, expect } from 'vitest';
import { stripTsxNoise } from '../lib/tsx-source.mjs';

// Batch 905 built the Java version of this file after measuring that four gates
// destroyed 316 string literals between them. This is the TSX dialect.
//
// One difference from the Java contract is deliberate and worth stating: the
// Java stripper leaves string literals *visible*, because the gates that use it
// count them. Here the gates match markup — `<h1>`, `aria-hidden=` — and
// matching markup that happens to sit inside a string is exactly the false
// alarm this file exists to prevent, so string contents become spaces too.
describe('stripTsxNoise', () => {
  it('blanks a line comment and keeps the newline', () => {
    const src = 'const a = 1; // <h3>nope</h3>\nconst b = 2;';
    const out = stripTsxNoise(src);
    expect(out).not.toContain('nope');
    expect(out.split('\n')).toHaveLength(2);
  });

  it('blanks a block comment', () => {
    const src = '/* <svg role="img" /> */\nconst a = 1;';
    const out = stripTsxNoise(src);
    expect(out).not.toContain('role=');
    expect(out).toContain('const a = 1;');
  });

  it('does not let a // inside a double-quoted string swallow the rest of the line', () => {
    // The property that matters is not that the string survives, but that what
    // follows it on the same line is still real code.
    const src = 'const url = "https://example.com/a"; const after = 1; // gone';
    const out = stripTsxNoise(src);
    expect(out).toContain('const after = 1;');
    expect(out).not.toContain('gone');
  });

  it('does the same for single quotes', () => {
    const src = "const url = 'https://example.com/a'; const after = 1;";
    expect(stripTsxNoise(src)).toContain('const after = 1;');
  });

  it('does the same for template literals', () => {
    const src = 'const url = `https://example.com/${host}/a`; const after = 1;';
    expect(stripTsxNoise(src)).toContain('const after = 1;');
  });

  it('keeps real code inside a template interpolation', () => {
    const src = 'const a = `x ${b /* hidden */ + 1}`;';
    const out = stripTsxNoise(src);
    expect(out).toContain('+ 1');
    expect(out).not.toContain('hidden');
  });

  it('does not let one escaped quote turn the rest of the file into a string', () => {
    // This is how the Java version was nearly wrong, and the failure is silent:
    // every comment after the escape survives, so a gate stops seeing the tree.
    const src = 'const s = "a\\" // still a string";\n// a real comment\nconst b = 2;';
    const out = stripTsxNoise(src);
    expect(out).not.toContain('a real comment');
    expect(out).toContain('const b = 2;');
  });

  it('does not treat a regex literal as a comment', () => {
    const src = 'const re = /https?:\\/\\//; // a real comment';
    const out = stripTsxNoise(src);
    expect(out).toContain('/https?:\\/\\//');
    expect(out).not.toContain('a real comment');
  });

  it('does not let a slash inside a regex character class end it early', () => {
    const src = 'const re = /[/]/; const after = 1;';
    expect(stripTsxNoise(src)).toContain('const after = 1;');
  });

  it('preserves length so a line number still points at the same line', () => {
    const src = 'const a = 1; // comment\nconst b = "<h3>";\n/* tail */\n';
    expect(stripTsxNoise(src)).toHaveLength(src.length);
  });

  it('is idempotent on already-stripped text', () => {
    const src = 'const a = 1; // c\nconst b = 2;';
    const once = stripTsxNoise(src);
    expect(stripTsxNoise(once)).toBe(once);
  });

  it('leaves ordinary code byte-identical', () => {
    const src = 'export function Page({ title }: { title: string }) {\n  return <h1>{title}</h1>;\n}\n';
    expect(stripTsxNoise(src)).toBe(src);
  });
});
