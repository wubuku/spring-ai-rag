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
      <PageHeader title={t('settings.title')} description={t('settings.subtitle')} />
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
      "<PageHeader title={t('settings.title')} description={t('settings.subtitle')} />",
      `<PageHeader
         title={t('collections.title')}
         description={t('collections.subtitle')}
         actions={<button onClick={create}>+ {t('collections.create')}</button>}
       />`,
    );
    expect(kinds(source, 'Collections.tsx')).toEqual([]);
  });
});

describe('a page whose header says nothing about itself', () => {
  // Batch 817. Every protected page routed its title through PageHeader, but
  // one of thirteen passed a description — the slot the component exists to
  // host and the one it links to the h1 for assistive technology. A heading
  // that reads only "Search" or "Metrics" gives a reader, and a screen reader,
  // nothing to orient by.

  const withoutDescription = good.replace(
    " description={t('settings.subtitle')}", '',
  );

  it('is rejected', () => {
    expect(kinds(withoutDescription)).toEqual([
      VIOLATION_KINDS.MISSING_PAGE_DESCRIPTION,
    ]);
  });

  it('is rejected for a multi-line tag that spreads its attributes', () => {
    const source = `
      export function Search() {
        return (
          <PageHeader
            title={t('search.title')}
            actions={<button onClick={go}>Go</button>}
          />
        );
      }
    `;
    expect(kinds(source, 'Search.tsx')).toEqual([
      VIOLATION_KINDS.MISSING_PAGE_DESCRIPTION,
    ]);
  });

  it('accepts a description that comes after a nested element in leading', () => {
    // The scan has to balance braces: this tag contains a `>` belonging to
    // <IconButton> long before the real end of the opening tag, and a naive
    // first-`>` scan would stop there and miss the description entirely.
    const source = `
      export function Chat() {
        return (
          <PageHeader
            title={t('chat.title')}
            leading={<IconButton label={t('chat.history')} onClick={open} />}
            description={t('chat.subtitle')}
          />
        );
      }
    `;
    expect(kinds(source, 'Chat.tsx')).toEqual([]);
  });

  it('accepts a description written last on a single line', () => {
    const source = good.replace(
      "description={t('settings.subtitle')}",
      "actions={<button>Save</button>} description={t('settings.subtitle')}",
    );
    expect(kinds(source)).toEqual([]);
  });

  it('does not fire on a header with children instead of attributes', () => {
    const source = `
      export function Files() {
        return <PageHeader title={t('files.title')}>body</PageHeader>;
      }
    `;
    expect(kinds(source, 'Files.tsx')).toEqual([
      VIOLATION_KINDS.MISSING_PAGE_DESCRIPTION,
    ]);
  });

  it('does not fire on an exempt page', () => {
    expect(kinds(withoutDescription, 'Unlock.tsx')).toEqual([]);
  });

  it('reports once per header, not once per missing attribute', () => {
    const source = `
      export function Metrics() {
        return (
          <div>
            <PageHeader title={t('metrics.title')} />
            <PageHeader title={t('metrics.title')} />
          </div>
        );
      }
    `;
    expect(kinds(source, 'Metrics.tsx')).toEqual([
      VIOLATION_KINDS.MISSING_PAGE_DESCRIPTION,
      VIOLATION_KINDS.MISSING_PAGE_DESCRIPTION,
    ]);
  });
});

describe('a hand-rolled page title', () => {
  const handRolled = good.replace(
    "<PageHeader title={t('settings.title')} description={t('settings.subtitle')} />",
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
    // and forgets to delete its old heading. Both signals must fire — the
    // removed class *and*, since Batch 862, the duplicate top-level heading
    // itself. The second signal is what survives a rewrite that drops the
    // class but forgets the heading.
    const source = good.replace(
      '<Tabs idPrefix="settings-tabs" items={tabs} />',
      '<h1 className="page-title">{t(\'settings.title\')}</h1>',
    );
    expect(kinds(source)).toEqual([
      VIOLATION_KINDS.HAND_ROLLED_PAGE_TITLE,
      VIOLATION_KINDS.DUPLICATE_PAGE_HEADING,
    ]);
  });

  it('is caught even when it sits next to a block comment', () => {
    const source = good.replace(
      "<PageHeader title={t('settings.title')} description={t('settings.subtitle')} />",
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

describe('a second top-level heading', () => {
  // Batch 862. `hand-rolled-page-title` is keyed on the removed `page-title`
  // class, so it only ever caught a duplicate heading that happened to carry
  // that class — the exact spelling Batch 805 found nine of, and none since.
  // The same duplicate with a CSS Module class, or with no class, was invisible.
  const withExtraHeading = (heading) => good.replace(
    '<Tabs idPrefix="settings-tabs" items={tabs} />',
    heading,
  );

  it('rejects a duplicate h1 carrying a module class', () => {
    expect(kinds(withExtraHeading('<h1 className={styles.title}>{t(\'settings.title\')}</h1>')))
      .toEqual([VIOLATION_KINDS.DUPLICATE_PAGE_HEADING]);
  });

  it('rejects a duplicate h1 carrying no class at all', () => {
    expect(kinds(withExtraHeading('<h1>Settings</h1>')))
      .toEqual([VIOLATION_KINDS.DUPLICATE_PAGE_HEADING]);
  });

  it('rejects a duplicate h1 written across two lines', () => {
    expect(kinds(withExtraHeading('<h1\n  className={styles.title}\n>Settings</h1>')))
      .toEqual([VIOLATION_KINDS.DUPLICATE_PAGE_HEADING]);
  });

  it('leaves lower headings alone', () => {
    // The defect is a second *top-level* heading. An h2 under the page h1 is
    // ordinary structure, and a rule that reported it would be crying wolf.
    expect(kinds(withExtraHeading('<h2>Advanced</h2>'))).toEqual([]);
  });

  it('leaves a heading quoted inside a comment alone', () => {
    expect(kinds(withExtraHeading("{/* the old <h1 className=\"page-title\"> */}\n      <p>body</p>")))
      .toEqual([]);
  });

  it('leaves an exempt page alone', () => {
    // The unlock screen is a full-bleed credential prompt outside ProtectedRoute
    // and is the one page whose h1 is its own.
    const source = `export function Unlock() {
  return <h1 id="unlock-title">{t('unlock.title')}</h1>;
}`;
    expect(kinds(source, 'Unlock.tsx')).toEqual([]);
    expect(kinds(source, 'NewPage.tsx')).toEqual([VIOLATION_KINDS.MISSING_PAGE_HEADER]);
  });

  it('does not fire on the compliant page it is derived from', () => {
    expect(kinds(good)).toEqual([]);
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
