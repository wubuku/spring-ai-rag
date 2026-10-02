import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkPage, VIOLATION_KINDS } from '../check-page-shell.mjs';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));
const pagesDir = join(projectRoot, 'src', 'pages');

/** Collect the kinds reported for a source snippet, for terse assertions. */
const kinds = (source, fileName = 'Settings.tsx') =>
  checkPage(fileName, source).map(violation => violation.kind);

const good = `
import { PageHeader } from '../components/ui';
export function Settings() {
  return (
    <div>
      <PageHeader title={t('settings.title')} />
      <Tabs idPrefix="settings-tabs" items={tabs} />
    </div>
  );
}
`;

// A gate that cannot fail is worse than no gate, and this repository has a
// documented habit of producing one — Batch 768 shipped a document claiming
// "ten classes" while the checker enforced eleven, Batch 790's first switch
// reconciler walked zero files, and Batch 804's e2e collector lost every spec
// after the first on a shared command line while its self-test only exercised
// the pure function. So the cases below assert rejection, not merely that the
// function runs, and the real tree is checked last so that a rule which
// stopped matching anything would show up here as a clean run rather than a
// silent pass.

describe('a page that uses the shared component', () => {
  it('is accepted', () => {
    expect(kinds(good)).toEqual([]);
  });

  it('is accepted with a description and page actions', () => {
    const source = good.replace(
      "<PageHeader title={t('settings.title')} />",
      `<PageHeader
         title={t('collections.title')}
         description={t('collections.description')}
         actions={<button onClick={create}>+ {t('collections.create')}</button>}
       />`,
    );
    expect(kinds(source, 'Collections.tsx')).toEqual([]);
  });
});

describe('a hand-rolled page title', () => {
  const handRolled = good.replace(
    "<PageHeader title={t('settings.title')} />",
    "<h1 className=\"page-title\">{t('settings.title')}</h1>",
  );

  it('is rejected, and so is the now-missing component', () => {
    // Two violations, not one: the page both keeps the removed class *and*
    // stops routing its title through the shared component. The case where the
    // component survives alongside the stray heading is separate below.
    expect(kinds(handRolled)).toEqual([
      VIOLATION_KINDS.HAND_ROLLED_PAGE_TITLE,
      VIOLATION_KINDS.MISSING_PAGE_HEADER,
    ]);
  });

  it('is rejected even when PageHeader is still imported and used', () => {
    // This is the state the gate exists to stop: a page adopts the component
    // and forgets to delete its old heading. Both signals must fire.
    const source = good.replace(
      '<Tabs idPrefix="settings-tabs" items={tabs} />',
      '<h1 className="page-title">{t(\'settings.title\')}</h1>',
    );
    expect(kinds(source)).toEqual([VIOLATION_KINDS.HAND_ROLLED_PAGE_TITLE]);
  });

  it('is caught even when it sits next to a block comment', () => {
    const source = good.replace(
      "<PageHeader title={t('settings.title')} />",
      `/* header note */
       <h1 className="page-title">{t('settings.title')}</h1>`,
    );
    expect(kinds(source)).toEqual([
      VIOLATION_KINDS.HAND_ROLLED_PAGE_TITLE,
      VIOLATION_KINDS.MISSING_PAGE_HEADER,
    ]);
  });
});

describe('comments', () => {
  it('do not trip the removed-class rule', () => {
    // Batch 797's gate had this class of bug in reverse: prose satisfying a
    // rule. Here the risk is a page documenting the old pattern and being
    // rejected for it.
    const source = good.replace(
      '<Tabs idPrefix="settings-tabs" items={tabs} />',
      "{/* Replaced the old h1.page-title here in Batch 805. */}\n      <p>body</p>",
    );
    expect(kinds(source)).toEqual([]);
  });
});

describe('a page with no header at all', () => {
  const bare = `
export function Metrics() {
  return <div className={styles.container}>content</div>;
}
`;

  it('is rejected', () => {
    expect(kinds(bare, 'Metrics.tsx')).toEqual([VIOLATION_KINDS.MISSING_PAGE_HEADER]);
  });

  it('is accepted under a registered exemption and rejected under any other name', () => {
    expect(kinds(bare, 'Unlock.tsx')).toEqual([]);
    expect(kinds(bare, 'NewPage.tsx')).toEqual([VIOLATION_KINDS.MISSING_PAGE_HEADER]);
  });
});

describe('the real page tree', () => {
  const pages = readdirSync(pagesDir)
    .filter(name => name.endsWith('.tsx') && !name.includes('.test.'))
    .sort();

  it('contains the thirteen protected pages plus the unlock screen', () => {
    // If a page is renamed out from under the gate, this is the assertion that
    // notices rather than the suite quietly checking four files.
    expect(pages.length).toBe(14);
  });

  it('reports no violations', () => {
    const violations = pages.flatMap(name =>
      checkPage(name, readFileSync(join(pagesDir, name), 'utf8')));
    expect(violations.map(v => v.detail)).toEqual([]);
  });
});
