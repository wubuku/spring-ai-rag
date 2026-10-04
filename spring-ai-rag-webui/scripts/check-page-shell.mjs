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
 * **Batch 817 added the second rule to this gate.** By then every protected page
 * did route its title through `PageHeader`, but only one of thirteen passed a
 * `description` — the slot the component exists to host, the one line that says
 * what the page is for, and the one `PageHeader` links to the `h1` so a screen
 * reader announces the heading and its meaning together. Twelve headings said
 * only "Search" or "Metrics". The title rule and the description rule are the
 * same class of mistake at different severities, so they belong in one gate:
 * a convention that is only half-adopted looks adopted from a distance.
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
  DUPLICATE_PAGE_HEADING: 'duplicate-page-heading',
  MISSING_PAGE_DESCRIPTION: 'missing-page-description',
});

/**
 * Extract the text of each `<PageHeader …>` opening tag.
 *
 * A `>` can appear inside a prop value — `<leading={…}>` with a nested element,
 * or a `title={t('a > b')}` — so the scan tracks brace depth and quotes rather
 * than stopping at the first `>`. Getting this wrong makes the new rule report
 * a page that does have a description, and a rule that cries wolf is a rule
 * somebody disables.
 */
export function pageHeaderTags(code) {
  const tags = [];
  const pattern = /<PageHeader\b/g;
  let m;
  while ((m = pattern.exec(code)) !== null) {
    let i = m.index;
    let depth = 0;
    let quote = null;
    for (; i < code.length; i += 1) {
      const ch = code[i];
      if (quote) {
        if (ch === quote) quote = null;
      } else if (ch === '"' || ch === "'") {
        quote = ch;
      } else if (ch === '{' || ch === '(' || ch === '[') {
        depth += 1;
      } else if (ch === '}' || ch === ')' || ch === ']') {
        depth -= 1;
      } else if (ch === '>' && depth === 0) {
        break;
      }
    }
    if (i >= code.length) break;
    tags.push(code.slice(m.index, i + 1));
    pattern.lastIndex = i + 1;
  }
  return tags;
}

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

  // Batch 862. The rule above is keyed on a **class name**, so it only ever
  // caught a duplicate heading that happened to carry the removed class — which
  // is the shape Batch 805 found nine of, and none since. The same duplicate
  // written with a CSS Module class, or with no class at all, sailed past: the
  // page rendered `<PageHeader>` for the gate to find and a hand-rolled `<h1>`
  // right underneath it. `PageHeader` renders the page's h1 itself, so that page
  // had two top-level headings and the accessibility tree announced the same
  // title twice — the exact duplication this gate exists to prevent, restored
  // through the one spelling the gate did not look for.
  if (!exempt.has(fileName) && /<h1[\s/>]/.test(code) && code.includes('<PageHeader')) {
    violations.push({
      kind: VIOLATION_KINDS.DUPLICATE_PAGE_HEADING,
      detail:
        `${fileName} renders <PageHeader>, which already emits the page h1, and`
        + ' also writes its own <h1>. Two top-level headings mean the same title'
        + ' is announced twice. Put the wording in the PageHeader title prop;'
        + ' if this page genuinely is not a shell page, register it in'
        + ' EXEMPT_PAGES with a reason.',
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
  } else if (!exempt.has(fileName)) {
    // Batch 817. The description slot existed for this exact reason — it is the
    // one piece of the page that tells a reader what the page is for, and
    // PageHeader links it to the h1 so a screen reader announces the heading
    // and its meaning together. One page of thirteen was using it, so twelve
    // headings said only "Search" or "Metrics" with nothing to orient by.
    //
    // Requiring it is the point: a slot that almost nobody fills rots back to
    // unused, and nothing in the test suite noticed for the whole time it sat
    // there.
    const tags = pageHeaderTags(code);
    for (const tag of tags) {
      if (!/\bdescription\s*=/.test(tag)) {
        violations.push({
          kind: VIOLATION_KINDS.MISSING_PAGE_DESCRIPTION,
          detail:
            `${fileName} renders <PageHeader> without a description. Give it`
            + ' description={t(\'…\')} so the heading says what the page is for;'
            + ' PageHeader links it to the h1 for assistive technology.',
        });
      }
    }
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
    + ` through PageHeader with a description (${exempt} exempt:`
    + ` ${[...EXEMPT_PAGES.keys()].join(', ')}).`,
  );
}

if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  main();
}
