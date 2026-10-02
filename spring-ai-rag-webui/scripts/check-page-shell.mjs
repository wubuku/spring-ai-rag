#!/usr/bin/env node
/**
 * Page-shell gate: every protected page renders its title through the shared
 * `PageHeader`.
 *
 * `PageHeader` was introduced because four pages had grown their own header row
 * with different flex rules and different spacing, and the title alone was
 * repeated as a bare `h1.page-title` everywhere else. Batch 805 surveyed the
 * thirteen protected routes and found the migration had stopped half done:
 *
 *   PageHeader        Chat, Collections, Embeddings, Files
 *   hand-rolled h1    ABTest, Alerts, ApiKeys, Dashboard, Documents,
 *                     Evaluation, Metrics, Search, Settings
 *
 * Nine of thirteen. Nothing in the test suite noticed, because the tests ask
 * for `getByRole('heading', { name })` and both shapes produce an `h1` — the
 * duplication is invisible to behaviour tests and obvious to a survey, which is
 * the same failure mode Batch 797 found in the query layer.
 *
 * Two things made the split real rather than cosmetic. The two conventions did
 * not render the same — the global `.page-title` used `margin-bottom: 20` while
 * the component uses `24` plus a tighter title line-height, so nine pages sat
 * on a different vertical rhythm from the other four. And `Files.module.css`
 * still carried `.header :global(.page-title)` and `.title` overrides for a
 * header row that no longer existed, which is what a half-finished migration
 * leaves behind.
 *
 * Run:
 *   node scripts/check-page-shell.mjs
 */

import { readFileSync, readdirSync } from 'node:fs';
import { join, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const PAGES_DIR = join(projectRoot, 'src', 'pages');
const GLOBAL_CSS = join(projectRoot, 'src', 'styles', 'global.css');

/**
 * The one page that legitimately has no header: it is the unlock screen, it
 * sits outside `ProtectedRoute`, and it is a full-bleed credential prompt
 * rather than a page in the shell. Registered by name so that adding a page
 * without a header is a deliberate act that has to be written down.
 */
const EXEMPT_PAGES = new Map([
  [
    'Unlock.tsx',
    'the unlock screen lives outside ProtectedRoute and is a credential prompt, not a shell page',
  ],
]);

/** Strips comments so prose cannot satisfy, or trip, a rule. */
export function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ');
}

export const VIOLATION_KINDS = Object.freeze({
  MISSING_PAGE_HEADER: 'missing-page-header',
  HAND_ROLLED_PAGE_TITLE: 'hand-rolled-page-title',
});

export function checkPage(fileName, source, { exempt = EXEMPT_PAGES } = {}) {
  const code = stripComments(source);
  const violations = [];

  // The class is what the rule is about, and it is matched anywhere rather
  // than only inside an h1: a page that keeps the class on a wrapper is still
  // carrying a second, hand-rolled heading convention.
  if (code.includes('page-title')) {
    violations.push({
      kind: VIOLATION_KINDS.HAND_ROLLED_PAGE_TITLE,
      detail:
        `${fileName} still references the removed global page-title class.`
        + ' Use <PageHeader title={…} /> from components/ui.',
    });
  }

  if (!code.includes('<PageHeader') && !exempt.has(fileName)) {
    violations.push({
      kind: VIOLATION_KINDS.MISSING_PAGE_HEADER,
      detail:
        `${fileName} does not render <PageHeader>. Every protected page routes its`
        + ' title through the shared component so the hierarchy and spacing stay'
        + ' consistent; register an exemption in EXEMPT_PAGES with a reason if this'
        + ' page is genuinely not a shell page.',
    });
  }

  return violations;
}

function pageFiles(dir) {
  return readdirSync(dir)
    .filter((name) => name.endsWith('.tsx') && !name.includes('.test.'))
    .sort();
}

function main() {
  const files = pageFiles(PAGES_DIR);
  const violations = files.flatMap((name) => {
    const source = readFileSync(join(PAGES_DIR, name), 'utf8');
    return checkPage(name, source);
  });

  // The class must be gone from the global sheet too, or the next contributor
  // will reach for it and the split starts again.
  const globalCss = readFileSync(GLOBAL_CSS, 'utf8');
  if (/\.page-title\b/.test(globalCss)) {
    violations.push({
      kind: VIOLATION_KINDS.HAND_ROLLED_PAGE_TITLE,
      detail:
        'src/styles/global.css still defines .page-title; the class is unused now'
        + ' that every page goes through PageHeader.',
    });
  }

  if (violations.length > 0) {
    console.error('Page-shell violations:');
    for (const v of violations) console.error(`- [${v.kind}] ${v.detail}`);
    console.error(
      '\nThe page header is a shared component for a reason: two conventions is\n' +
        'how the vertical rhythm drifted in the first place.',
    );
    process.exitCode = 1;
    return;
  }

  const exempt = files.filter((n) => EXEMPT_PAGES.has(n)).length;
  console.log(
    `Page-shell check passed; ${files.length} page(s) checked, all routing their title`
    + ` through PageHeader (${exempt} exempt: ${[...EXEMPT_PAGES.keys()].join(', ')}).`,
  );
}

if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  main();
}
