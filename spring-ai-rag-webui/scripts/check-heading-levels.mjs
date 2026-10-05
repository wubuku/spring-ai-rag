#!/usr/bin/env node
/**
 * Heading-level gate: a page's headings must not skip a level.
 *
 * Screen reader users navigate a page by heading, and the level is what tells
 * them where they are: `h1` is the page, `h2` a section inside it, `h3` a
 * subsection of that. An `h1 → h3` jump claims there is a section between them
 * that does not exist, so the outline a reader builds from the page is wrong
 * before they read a word of it.
 *
 * This is a best-practice failure rather than a literal WCAG 1.3.1 violation,
 * and that distinction is why the rule is bounded the way it is. It checks the
 * levels written in a page file, in document order, counting the `h1` that
 * `PageHeader` renders as the page's first heading.
 *
 * ## What it cannot see, stated rather than implied
 *
 * The check is **per file**, so a heading that a child component renders is not
 * counted. `Metrics.tsx` writes an `h2` and then renders `MetricsCharts`, whose
 * own headings are `h3` — correct. If someone moved `MetricsCharts` above that
 * `h2`, the page file would still read `h1 → h2` and this gate would stay quiet
 * while the rendered outline skipped. That is a **miss**, never a false alarm,
 * which is the right way round: `check-page-shell`'s recursive walk has the
 * mirror-image limitation and documents it the same way.
 *
 * There is no allowlist. A page whose headings skip a level is the defect; there
 * is no reading under which skipping is the intent. `Unlock.tsx` is the one
 * screen that does not use `PageHeader`, and it carries its own `h1`, so the
 * rule needs no exception for it.
 *
 * Batch 911 measured fourteen pages: thirteen were already well-formed and
 * `Alerts.tsx` was the only one that jumped — `h1 → h3` twice, for the two
 * form cards, on a page that had no `h2` anywhere.
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { isMainModule } from './lib/is-main-module.mjs';
import { stripTsxNoise } from './lib/tsx-source.mjs';

/** Page files, sorted so output is stable. */
export function pageFiles(dir) {
  return readdirSync(dir).filter((f) => f.endsWith('.tsx') && !f.includes('.test.')).sort();
}

/**
 * Heading levels in document order. A page using `PageHeader` starts at `h1`,
 * because that is what the primitive renders; a page that does not is judged
 * from whatever it writes itself.
 *
 * @returns {Array<{level: number, line: number}>}
 */
export function headingsOf(source) {
  // Comments and string literals are stripped first, and that is not a
  // formality: `Documents.tsx` carries a Chinese comment that spells out
  // `<h2>` while explaining an `aria-labelledby` pointing at an empty one. The
  // first version of this gate reported it as a heading on the real tree.
  const code = stripTsxNoise(source);
  const found = [];
  if (/<PageHeader\b/.test(code)) found.push({ level: 1, line: 0, from: 'PageHeader' });
  for (const m of code.matchAll(/<h([1-6])\b/g)) {
    found.push({
      level: Number(m[1]),
      line: code.slice(0, m.index).split('\n').length,
      from: null,
    });
  }
  return found;
}

/**
 * Pairs of consecutive headings whose level jumps by more than one.
 *
 * @returns {Array<{from: number, fromLine: number, to: number, toLine: number}>}
 */
export function checkPage(name, source) {
  const headings = headingsOf(source);
  const skips = [];
  for (let i = 1; i < headings.length; i++) {
    const prev = headings[i - 1];
    const cur = headings[i];
    if (cur.level > prev.level + 1) {
      skips.push({
        from: prev.level,
        fromLine: prev.line,
        to: cur.level,
        toLine: cur.line,
        file: name,
      });
    }
  }
  return skips;
}

function main() {
  const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
  const pagesDir = path.join(root, 'src', 'pages');
  if (!statSync(pagesDir).isDirectory()) {
    console.error(`No pages directory at ${pagesDir}; refusing to report success.`);
    process.exit(1);
  }

  const pages = pageFiles(pagesDir);
  const findings = pages.flatMap((name) =>
    checkPage(name, readFileSync(path.join(pagesDir, name), 'utf8')));

  const withHeadings = pages.filter(
    (name) => headingsOf(readFileSync(path.join(pagesDir, name), 'utf8')).length > 0).length;
  console.log(`Pages scanned: ${pages.length}, of which ${withHeadings} carry headings.`);
  if (findings.length === 0) {
    console.log('No page skips a heading level on its way down.');
    process.exit(0);
  }

  console.error(`\n${findings.length} heading level(s) skipped:\n`);
  for (const f of findings) {
    const from = f.fromLine === 0 ? 'PageHeader' : `:${f.fromLine}`;
    console.error(`  ${f.file}  h${f.from}${from} → h${f.to} at :${f.toLine}`);
  }
  console.error('\nUse the level that actually describes the nesting. Every section a page');
  console.error('puts directly under its title is an h2, even when it renders a form.');
  process.exit(1);
}

if (isMainModule(import.meta.url)) main();
