import { describe, expect, it } from 'vitest';
import { resolveTabParam, tabSearchParams, toTabItems } from './urlParams';

// Batch 942. Three pages each wrote their tab vocabulary out in two or three places, and
// the reason that mattered is measured rather than argued: editing only the `type Tab`
// union compiles clean and leaves the new tab unreachable, because the hand-written
// validation chain narrows to a *subset* of `Tab` and that still assigns.
//
// So these tests pin the two properties the refactor actually buys: a tab is reachable
// exactly when it has a label, and an unknown parameter falls back instead of throwing or
// rendering a tab that has no panel.

const EVALUATION_TABS = {
  report: 'evaluation.tabReport',
  history: 'evaluation.tabHistory',
  feedback: 'evaluation.tabFeedback',
  judge: 'evaluation.tabJudge',
  suites: 'evaluation.tabSuites',
  runs: 'evaluation.tabRuns',
  citations: 'evaluation.tabCitations',
} as const;

type EvaluationTab = keyof typeof EVALUATION_TABS;

const t = (key: string) => `t(${key})`;

describe('resolveTabParam', () => {
  it('accepts a parameter that names a tab', () => {
    expect(resolveTabParam('history', EVALUATION_TABS, 'report')).toBe('history');
    expect(resolveTabParam('report', EVALUATION_TABS, 'report')).toBe('report');
    expect(resolveTabParam('citations', EVALUATION_TABS, 'report')).toBe('citations');
  });

  it('falls back for anything the page does not have', () => {
    // Including the case that was silently unreachable before: a tab in the type system
    // with no label has no key here, so there is nothing to reach.
    expect(resolveTabParam('escalation-policies', EVALUATION_TABS, 'report')).toBe('report');
    expect(resolveTabParam('', EVALUATION_TABS, 'report')).toBe('report');
    expect(resolveTabParam('REPORT', EVALUATION_TABS, 'report')).toBe('report');
    expect(resolveTabParam('__proto__', EVALUATION_TABS, 'report')).toBe('report');
  });

  it('falls back for an absent parameter rather than throwing', () => {
    expect(resolveTabParam(null, EVALUATION_TABS, 'report')).toBe('report');
    expect(resolveTabParam(undefined, EVALUATION_TABS, 'report')).toBe('report');
  });

  it('does not accept an inherited Object property as a tab', () => {
    // `in` walks the prototype chain, so `constructor` and `toString` are "in" every
    // plain object. Without this check a crafted `?tab=toString` would resolve to a tab
    // id no table declares, and the page would render a panel that does not exist.
    expect(resolveTabParam('toString', EVALUATION_TABS, 'report')).toBe('report');
    expect(resolveTabParam('constructor', EVALUATION_TABS, 'report')).toBe('report');
    expect(resolveTabParam('hasOwnProperty', EVALUATION_TABS, 'report')).toBe('report');
  });

  it('keeps the declared order when it builds the tab strip', () => {
    const items = toTabItems(EVALUATION_TABS, t);
    expect(items.map(item => item.id)).toEqual([
      'report', 'history', 'feedback', 'judge', 'suites', 'runs', 'citations',
    ]);
    expect(items[1]).toEqual({ id: 'history', label: 't(evaluation.tabHistory)' });
  });
});

describe('tabSearchParams', () => {
  it('writes the default tab as no tab at all', () => {
    // The rule that was inline in `Evaluation` and nowhere else: the tab a page opens on
    // is the tab that is absent from its own URL, so a plain link to the page is a link
    // to its first tab rather than to a spelled-out default.
    expect(tabSearchParams<EvaluationTab>('report', 'report')).toEqual({});
    expect(tabSearchParams<EvaluationTab>('history', 'report')).toEqual({ tab: 'history' });
  });

  it('round-trips: whatever it writes, the resolver reads back', () => {
    for (const id of Object.keys(EVALUATION_TABS) as EvaluationTab[]) {
      const params = tabSearchParams(id, 'report');
      const resolved = resolveTabParam(params.tab ?? null, EVALUATION_TABS, 'report');
      expect(resolved).toBe(id);
    }
  });
});
