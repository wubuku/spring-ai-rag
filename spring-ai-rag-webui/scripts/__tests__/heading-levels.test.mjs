import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkPage, headingsOf, pageFiles } from '../check-heading-levels.mjs';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));
const pagesDir = join(projectRoot, 'src', 'pages');

const skips = (source, fileName = 'Page.tsx') =>
  checkPage(fileName, source).map((v) => `h${v.from}→h${v.to}`);

const withHeader = (body) => `<PageHeader title="t" description="d" />\n${body}\n`;

// A gate that cannot fail is worse than no gate. The cases below assert
// rejection, not merely that the function runs — and the real tree is checked
// last so a rule that stopped matching anything would surface as a clean run
// rather than a silent pass.
describe('heading levels', () => {
  it('accepts a page whose sections step down one level at a time', () => {
    expect(skips(withHeader('<h2>a</h2><h3>b</h3><h2>c</h2>'))).toEqual([]);
  });

  it('accepts a page with only a title', () => {
    expect(skips(withHeader('<p>text</p>'))).toEqual([]);
  });

  it('reports h1 → h3 as a skip', () => {
    expect(skips(withHeader('<h3>form card</h3>'))).toEqual(['h1→h3']);
  });

  it('reports h2 → h4 as a skip', () => {
    expect(skips(withHeader('<h2>a</h2><h4>b</h4>'))).toEqual(['h2→h4']);
  });

  it('reports each skip separately', () => {
    expect(skips(withHeader('<h3>a</h3><h2>b</h2><h4>c</h4>'))).toEqual(['h1→h3', 'h2→h4']);
  });

  it('does not report going back up — returning to a shallower level is normal', () => {
    expect(skips(withHeader('<h2>a</h2><h3>b</h3><h2>c</h2>'))).toEqual([]);
  });

  it('judges a page without PageHeader from its own first heading', () => {
    // Unlock.tsx is the one screen that does not use the shared header and
    // carries its own h1. If the gate demanded PageHeader it would need an
    // exception for that file, and an exception is a list of things this gate
    // does not check.
    expect(skips('<h1>Unlock</h1><h2>form</h2>')).toEqual([]);
    expect(skips('<h1>Unlock</h1><h3>form</h3>')).toEqual(['h1→h3']);
  });

  it('does not read a heading out of a comment', () => {
    expect(skips(withHeader('{/* <h3>planned</h3> */}\n<p>x</p>'))).toEqual([]);
  });

  it('does not read a heading out of a string', () => {
    expect(skips(withHeader("const s = '<h3>';"))).toEqual([]);
  });
});

describe('the real page tree', () => {
  const pages = pageFiles(pagesDir);

  it('covers all fourteen screens', () => {
    expect(pages.length).toBe(14);
  });

  it('reports no skipped heading level', () => {
    const findings = pages.flatMap((name) =>
      checkPage(name, readFileSync(join(pagesDir, name), 'utf8')));
    expect(findings.map((f) => `${f.file} h${f.from}→h${f.to}`)).toEqual([]);
  });

  it('still has pages with headings, so the rule is not vacuous', () => {
    const withHeadings = pages.filter((name) =>
      headingsOf(readFileSync(join(pagesDir, name), 'utf8')).length > 1);
    expect(withHeadings.length).toBeGreaterThanOrEqual(5);
  });

  it('counts PageHeader as the h1 every protected page renders', () => {
    const alerts = readFileSync(join(pagesDir, 'Alerts.tsx'), 'utf8');
    expect(headingsOf(alerts)[0]).toMatchObject({ level: 1, from: 'PageHeader' });
  });
});
