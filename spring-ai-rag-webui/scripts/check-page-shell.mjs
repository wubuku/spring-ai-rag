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
 * **Batch 877 closed three holes in this gate**, none of which was costing a
 * page today and all of which would have cost one later:
 *
 *   - it was the last of the seven frontend gates carrying its own copy of
 *     `stripComments`, and that copy was a regex one which mistook a `//`
 *     inside a string literal for a comment, blanking the rest of the line;
 *   - the description rule tested that the prop was *present*, which
 *     `description=""` and `description={undefined}` both satisfy — the first
 *     renders an empty `<p>` the `h1` still points at, the second makes the
 *     slot disappear, which is the rot Batch 817 was written to stop;
 *   - the page walk was one directory deep, so a page added in a subdirectory
 *     reached the router and the tests and no rule in here at all.
 *
 * Run:
 *   node scripts/check-page-shell.mjs
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, basename, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripComments } from './check-design-system.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const PAGES_DIR = join(projectRoot, 'src', 'pages');
const GLOBAL_CSS = join(projectRoot, 'src', 'styles', 'global.css');

/**
 * The one page that legitimately has no header: it is the unlock screen, it
 * sits outside `ProtectedRoute`, and it is a full-bleed credential prompt
 * rather than a page in the shell. Registered by name so that adding a page
 * without a header is a deliberate act that has to be written down.
 *
 * Batch 877: keys are now paths relative to `src/pages`, not bare file names.
 * The walk below is recursive, so `admin/Foo.tsx` is a page like any other and
 * `Foo.tsx` is a different page from `admin/Foo.tsx` — one map keyed on
 * `basename` could not tell them apart, and the exemption would follow the
 * wrong one.
 */
const EXEMPT_PAGES = new Map([
  [
    'Unlock.tsx',
    'the unlock screen lives outside ProtectedRoute and is a credential prompt, not a shell page',
  ],
]);

export const VIOLATION_KINDS = Object.freeze({
  MISSING_PAGE_HEADER: 'missing-page-header',
  HAND_ROLLED_PAGE_TITLE: 'hand-rolled-page-title',
  DUPLICATE_PAGE_HEADING: 'duplicate-page-heading',
  MISSING_PAGE_DESCRIPTION: 'missing-page-description',
  EMPTY_PAGE_DESCRIPTION: 'page-description-empty',
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

/**
 * Read one attribute's raw value off an opening tag, balancing braces and
 * quotes so that `description={cond ? <b>{a}</b> : <i>{b}</i>}` is read whole.
 *
 * The returned value keeps its delimiters — `""`, `''`, `{t('x')}` — so that
 * "a literal that is empty" and "an expression that could be empty" stay
 * distinguishable. Handing back the unquoted contents instead, which is what the
 * first version did, made `description=""` arrive as the empty string and read
 * as an expression nobody could evaluate, so the rule passed it. The first
 * self-test to run caught exactly that.
 *
 * Batch 877. The attribute name has to be preceded by whitespace or a brace:
 * anchoring on a word boundary let `data-description` satisfy the rule, which is
 * the same `data-*` mirror mistake `check-a11y-forms` stopped making in Batch
 * 876, and both directions of it are fail-open — here the page renders no
 * description at all and the gate says it does.
 */
function attributeValue(tag, name) {
  const at = new RegExp(`(?:^|[\\s{])${name}(?=\\s*=)`).exec(tag);
  if (!at) return { present: false, raw: null };
  let i = at.index + at[0].length;
  while (i < tag.length && /\s/.test(tag[i])) i += 1;
  if (tag[i] !== '=') return { present: false, raw: null };
  i += 1;
  while (i < tag.length && /\s/.test(tag[i])) i += 1;

  if (tag[i] === '"' || tag[i] === "'") {
    const quote = tag[i];
    const end = tag.indexOf(quote, i + 1);
    return { present: true, raw: end === -1 ? tag.slice(i) : tag.slice(i, end + 1) };
  }
  if (tag[i] === '{') {
    const start = i;
    let depth = 0;
    let quote = null;
    for (; i < tag.length; i += 1) {
      const ch = tag[i];
      if (quote) { if (ch === quote) quote = null; continue; }
      if (ch === '"' || ch === "'" || ch === '`') { quote = ch; continue; }
      if (ch === '{') depth += 1;
      else if (ch === '}') { depth -= 1; if (depth === 0) return { present: true, raw: tag.slice(start, i + 1) }; }
    }
    return { present: true, raw: null };
  }
  const end = tag.slice(i).search(/[\s/>]/);
  return { present: true, raw: end === -1 ? tag.slice(i) : tag.slice(i, i + end) };
}

/**
 * Whether a description prop is *provably* nameless.
 *
 * Batch 877. Requiring the prop was Batch 817's fix, and mandatory is not the
 * same as present — `description=""` and `description={undefined}` both satisfy
 * a presence test. Both are the defect, and `PageHeader` is what makes them
 * defects: it guards with `description !== undefined && description !== null`,
 * so an empty string takes the *rendering* branch with nothing to render, an
 * empty `<p>` that the h1 still points at through `aria-describedby`, while
 * `undefined` takes the other branch and the slot disappears entirely, which is
 * precisely the rot Batch 817 wrote the rule to stop. The sibling a11y gate
 * reached the same conclusion about a dialog title, down to the note that
 * exempting the empty case is the most direct way to manufacture it.
 *
 * An expression is not reported. `{t('x')}` cannot be evaluated statically, and
 * reporting it would be reporting thirteen correct pages.
 */
function isEmptyDescription(raw) {
  if (raw === null) return false;
  const trimmed = raw.trim();
  const literal = /^(["'`])([\s\S]*)\1$/.exec(trimmed);
  if (literal) return literal[2].trim().length === 0;
  const expression = trimmed.replace(/^\{/, '').replace(/\}$/, '').trim();
  if (expression === 'undefined' || expression === 'null') return true;
  const inner = /^(["'`])([\s\S]*)\1$/.exec(expression);
  return inner !== null && inner[2].trim().length === 0;
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
      const description = attributeValue(tag, 'description');
      if (!description.present) {
        violations.push({
          kind: VIOLATION_KINDS.MISSING_PAGE_DESCRIPTION,
          detail:
            `${fileName} renders <PageHeader> without a description. Give it`
            + ' description={t(\'…\')} so the heading says what the page is for;'
            + ' PageHeader links it to the h1 for assistive technology.',
        });
      } else if (isEmptyDescription(description.raw)) {
        violations.push({
          kind: VIOLATION_KINDS.EMPTY_PAGE_DESCRIPTION,
          detail:
            `${fileName} passes a description that is empty. PageHeader renders it`
            + ' whenever it is not undefined, so an empty string produces an empty'
            + ' <p> that the h1 still points at with aria-describedby, and'
            + ' undefined drops the slot altogether. Either one is the page the'
            + ' Batch 817 rule was written to stop; give it real copy.',
        });
      }
    }
  }

  return violations;
}

/**
 * Every page source under `src/pages`, as paths relative to that directory.
 *
 * Batch 877. This walked one directory level, so a page added in a
 * subdirectory — `pages/admin/Audit.tsx` — reached the router, reached the test
 * suite, and reached no rule in this gate at all, not even the one that exists
 * to demand a `PageHeader`. The whole point of the gate is "every page", and a
 * path nobody can reach is the same as no gate.
 */
export function pageFiles(root = PAGES_DIR) {
  const found = [];
  const walk = dir => {
    for (const entry of readdirSync(dir)) {
      const full = join(dir, entry);
      if (statSync(full).isDirectory()) walk(full);
      else if (entry.endsWith('.tsx') && !entry.includes('.test.') && !entry.includes('.spec.')) {
        found.push(relative(root, full));
      }
    }
  };
  walk(root);
  return found.sort();
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

if (isMainModule(import.meta.url)) {
  main();
}
