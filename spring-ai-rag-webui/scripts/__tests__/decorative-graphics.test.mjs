import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkSource } from '../check-decorative-graphics.mjs';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));
const srcDir = join(projectRoot, 'src');

function walk(dir, out = []) {
  for (const entry of readdirSync(dir).sort()) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, out);
    else if (entry.endsWith('.tsx') && !entry.includes('.test.')) out.push(full);
  }
  return out;
}

const lines = (source) => checkSource('X.tsx', source).map((f) => f.line);

describe('inline graphics', () => {
  it('accepts a decorative glyph', () => {
    expect(lines('<svg aria-hidden="true" focusable="false" viewBox="0 0 24 24"><circle /></svg>')).toEqual([]);
  });

  it('reports a glyph that neither names itself nor hides', () => {
    expect(lines('<button><svg viewBox="0 0 24 24"><circle /></svg></button>')).toEqual([1]);
  });

  it('accepts a meaningful graphic carrying role and label', () => {
    // The opposite error matters just as much: hiding a graphic that does carry
    // meaning removes information a screen-reader user was entitled to.
    expect(lines('<svg role="img" aria-label="Sort"><path /></svg>')).toEqual([]);
  });

  it('accepts a meaningful graphic carrying a <title> child', () => {
    expect(lines('<svg viewBox="0 0 24 24"><title>Trend</title><path /></svg>')).toEqual([]);
  });

  it('reads aria-hidden="false" as not hidden', () => {
    expect(lines('<svg aria-hidden="false" viewBox="0 0 24 24"><path /></svg>')).toEqual([1]);
  });

  it('does not read an attribute out of a string literal', () => {
    expect(lines("<svg data-note=\"aria-hidden='true'\" viewBox=\"0 0 24 24\"><path /></svg>")).toEqual([1]);
  });

  it('does not read aria-hidden out of a comment', () => {
    expect(lines('<svg /* aria-hidden="true" */ viewBox="0 0 24 24"><path /></svg>')).toEqual([1]);
  });

  it('reports each unmarked graphic separately', () => {
    expect(lines('<svg><path /></svg><svg><path /></svg>')).toEqual([1, 1]);
  });
});

describe('the real tree', () => {
  const files = walk(srcDir);

  it('finds tsx sources at all', () => {
    expect(files.length).toBeGreaterThan(30);
  });

  it('reports no unmarked decorative graphic', () => {
    const findings = files.flatMap((file) =>
      checkSource(file.replace(`${srcDir}/`, ''), readFileSync(file, 'utf8')));
    expect(findings.map((f) => `${f.file}:${f.line}`)).toEqual([]);
  });
});
