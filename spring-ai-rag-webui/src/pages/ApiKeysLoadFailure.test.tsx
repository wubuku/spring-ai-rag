import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import { ApiKeys } from './ApiKeys';

/**
 * Batch 874. The API key list rendered a failed request as
 * `<EmptyState>{t('common.error')}</EmptyState>` — the same primitive, the same
 * box and the same weight as the "no keys yet" branch three lines below it.
 * A user whose credential store was down could not tell that from a user who had
 * not created a key, was told nothing about what went wrong, and had no way to
 * try again without reloading the page.
 *
 * These tests are about the *behaviour* — what appears, whether it announces
 * itself, and whether the failed read can be repeated. The source-level rule
 * that keeps new call sites honest is `empty-state-on-error` in
 * `scripts/check-query-errors.mjs`; this file is the last mile, and it is the
 * part a gate cannot do: a gate reads source, and whether the thing on screen
 * is legible to a person is a question about the screen.
 */

const mockUseQuery = vi.fn();
const mockUseQueryClient = vi.fn(() => ({ invalidateQueries: vi.fn() }));
const mockMutateFn = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
  useMutation: () => ({ mutate: mockMutateFn, isPending: false }),
  useQueryClient: (...args: unknown[]) => mockUseQueryClient(...args),
}));

vi.mock('../components/Toast', () => ({
  useToast: () => ({ showToast: vi.fn() }),
}));

vi.mock('../api/apikeys', () => ({
  apiKeysApi: {
    listPrincipals: vi.fn(),
    createKey: vi.fn(),
    revokeKey: vi.fn(),
    rotateKey: vi.fn(),
    prepareRotation: vi.fn(),
    completeRotation: vi.fn(),
    cancelRotation: vi.fn(),
    listPrincipalsPolicy: vi.fn(),
    updatePrincipalPolicy: vi.fn(),
  },
}));

vi.mock('../api/collections', () => ({
  collectionsApi: { list: vi.fn() },
}));

/** Principals query fails; the collections query is irrelevant here. */
function keysQueryFails(options: { queryKey: unknown[] }) {
  if (options.queryKey[0] === 'api-principals') {
    return {
      data: undefined,
      isPending: false,
      isError: true,
      error: new Error('credential store unreachable'),
      refetch: mockRefetch,
    };
  }
  return { data: { data: { collections: [] } }, isPending: false, isError: false };
}

const mockRefetch = vi.fn();

/** Principals query succeeds but returns nothing. */
function keysQueryEmpty(options: { queryKey: unknown[] }) {
  if (options.queryKey[0] === 'api-principals') {
    return {
      data: { data: [] },
      isPending: false,
      isError: false,
      error: null,
      refetch: mockRefetch,
    };
  }
  return { data: { data: { collections: [] } }, isPending: false, isError: false };
}

function renderPage() {
  return render(
    <BrowserRouter>
      <ApiKeys />
    </BrowserRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

/**
 * A stable handle on the shared EmptyState.
 *
 * The primitive always sets `data-align` (that is how `align` reaches the
 * stylesheet), so this finds it without depending on a hashed CSS-module class
 * name. Asserting on a class name would make these tests quietly vacuous: a
 * selector that matches nothing passes a "does not render" assertion forever,
 * which is the failure mode this batch is about, wearing a test's clothes.
 * `findsTheEmptyStateWhenThereIsOne` below is the positive control.
 */
const emptyState = (container: HTMLElement) =>
  container.querySelector('[data-align]');

describe('a failed key listing is reported as a failure', () => {
  it('does not render the empty state', () => {
    mockUseQuery.mockImplementation(keysQueryFails);
    const { container } = renderPage();

    // The whole defect was that these two look the same. Assert on the
    // primitive's own marker rather than on copy, so this cannot pass by
    // rewording.
    expect(emptyState(container)).toBeNull();
  });

  it('says what failed', () => {
    mockUseQuery.mockImplementation(keysQueryFails);
    renderPage();

    expect(screen.getByText('apiKeys.loadFailed')).toBeTruthy();
  });

  it('announces itself to assistive technology', () => {
    mockUseQuery.mockImplementation(keysQueryFails);
    const { container } = renderPage();

    // role="alert" rather than role="status": this appears without user action
    // and reports a loss of function, which is assertive by the ARIA definition.
    expect(container.querySelector('[role="alert"]')).not.toBeNull();
  });

  it('offers a retry, and the retry re-runs the failed read', () => {
    mockUseQuery.mockImplementation(keysQueryFails);
    renderPage();

    const retry = screen.getByText('common.retry');
    expect(mockRefetch).not.toHaveBeenCalled();

    fireEvent.click(retry);

    // Before Batch 874 `refetch` was not even destructured, because the only
    // failure display was a word with no action attached to it: a failed read
    // could not be repeated without a full page reload.
    expect(mockRefetch).toHaveBeenCalled();
  });

  it('keeps a transport-level string out of the screen', () => {
    // usableReason's job, exercised through a call site rather than in isolation:
    // a response that carried no reason reaches the browser as this string, and
    // printing it under a perfectly good sentence is noise.
    mockUseQuery.mockImplementation(options =>
      options.queryKey[0] === 'api-principals'
        ? {
          data: undefined,
          isPending: false,
          isError: true,
          error: new Error('Request failed with status code 503'),
          refetch: mockRefetch,
        }
        : keysQueryEmpty(options));
    const { container } = renderPage();

    expect(screen.getByText('apiKeys.loadFailed')).toBeTruthy();
    expect(container.textContent).not.toMatch(/status code 503/);
  });
});

describe('a failed listing and an empty listing stay distinguishable', () => {
  it('findsTheEmptyStateWhenThereIsOne', () => {
    // The positive control for every "does not render" assertion above. If the
    // selector in `emptyState` stopped matching, those would all pass for the
    // wrong reason and this is the one that would notice.
    mockUseQuery.mockImplementation(keysQueryEmpty);
    const { container } = renderPage();

    const node = emptyState(container);
    expect(node).not.toBeNull();
    expect(node?.textContent).toContain('apiKeys.noKeys');
  });

  it('the two states do not render the same thing', () => {
    mockUseQuery.mockImplementation(keysQueryFails);
    const failed = renderPage();
    const failedText = failed.container.querySelector('[role="alert"]')?.textContent ?? '';

    // A second render in one test lands in the same document, so every lookup
    // is scoped to the container it came from.
    mockUseQuery.mockImplementation(keysQueryEmpty);
    const empty = renderPage();
    const emptyNode = emptyState(empty.container);
    const emptyText = emptyNode?.textContent ?? '';

    expect(failedText).not.toBe('');
    expect(emptyText).not.toBe('');
    expect(failedText).not.toBe(emptyText);
  });

  it('an empty listing still offers the create-first action', () => {
    mockUseQuery.mockImplementation(keysQueryEmpty);
    renderPage();

    // The empty state is not a degraded error report; it is an invitation, and
    // it must keep the affordance the failure branch deliberately lacks.
    expect(screen.getByText('apiKeys.createFirst')).toBeTruthy();
  });
});
