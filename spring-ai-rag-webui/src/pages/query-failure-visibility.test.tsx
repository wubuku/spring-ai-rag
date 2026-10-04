/**
 * A failed read must not be allowed to look like a fact.
 *
 * Batch 797 fixed 21 reads whose failure was invisible, and
 * `scripts/check-query-errors.mjs` now stops new ones. That gate is
 * file-scoped and cannot tell which sub-component rendered a banner, so it
 * misses the case where one sibling renders its error and another does not.
 * These cases are the last mile: each one asserts a specific sentence the page
 * used to show *when the request had failed*, which is the only way to catch
 * that class of miss.
 *
 * Three of them report a negative — "no active alerts", "not found", a search
 * with no results — and those are the ones worth reading first. An operator
 * acting on a false "no alerts" checks the wrong thing.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Alerts } from './Alerts';
import { ABTest } from './ABTest';
import { Search } from './Search';
import { Collections } from './Collections';
import { Evaluation } from './Evaluation';
import { ReembedAllButton } from '../components/ReembedAllButton/ReembedAllButton';
import { ToastProvider } from '../components/Toast';
import { alertsApi } from '../api/alerts';
import { abtestApi } from '../api/abtest';
import { searchApi } from '../api/search';
import { collectionsApi } from '../api/collections';
import { evaluationApi } from '../api/evaluation';
import { documentsApi } from '../api/documents';

vi.mock('../api/alerts', () => ({
  alertsApi: {
    listActive: vi.fn(),
    listNotificationDeliveries: vi.fn(),
    retryNotificationDelivery: vi.fn(),
    listSloConfigs: vi.fn(),
    createSloConfig: vi.fn(),
    deleteSloConfig: vi.fn(),
    listSilenceSchedules: vi.fn(),
    createSilenceSchedule: vi.fn(),
    deleteSilenceSchedule: vi.fn(),
  },
}));

vi.mock('../api/abtest', () => ({
  abtestApi: {
    listExperiments: vi.fn(),
    getExperiment: vi.fn(),
    getAnalysis: vi.fn(),
    startExperiment: vi.fn(),
    pauseExperiment: vi.fn(),
    stopExperiment: vi.fn(),
    createExperiment: vi.fn(),
  },
}));

vi.mock('../api/search', () => ({ searchApi: { search: vi.fn() } }));

vi.mock('../api/files', () => ({ filesApi: { getRawFile: vi.fn() } }));

vi.mock('../api/collections', () => ({
  collectionsApi: { list: vi.fn(), integrationCapabilities: vi.fn(), deleteByKey: vi.fn() },
}));

vi.mock('../api/evaluation', () => ({
  evaluationApi: {
    getReport: vi.fn(),
    getHistory: vi.fn(),
    getFeedbackStats: vi.fn(),
    getFeedbackHistory: vi.fn(),
    listSuites: vi.fn(),
    listCitationTraces: vi.fn(),
  },
}));

vi.mock('../api/documents', () => ({
  documentsApi: { getEmbeddingStatus: vi.fn(), reembedMissing: vi.fn() },
}));

vi.mock('../auth/ApiKeyAuthContext', () => ({
  useApiKeyAuth: () => ({
    identity: {
      principalType: 'ENVIRONMENT_ROOT',
      principalId: 'environment-root',
      capabilities: ['RAG_READ', 'RAG_WRITE', 'API_KEY_MANAGE'],
    },
  }),
}));

function withClient(ui: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <MemoryRouter initialEntries={['/']}>{ui}</MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  // `resetAllMocks`, not `clearAllMocks`: several of these pages mount a
  // collection scope selector that issues its own `collectionsApi.list`, so a
  // one-shot rejection gets consumed by a call the test never thought about and
  // the second call falls through to a bare `vi.fn()` returning `undefined` —
  // which is neither data nor an error, and renders the wrong thing.
  vi.resetAllMocks();
  vi.mocked(alertsApi.listSloConfigs).mockResolvedValue({ data: [] } as never);
  vi.mocked(alertsApi.listSilenceSchedules).mockResolvedValue({ data: [] } as never);
  vi.mocked(abtestApi.listExperiments).mockResolvedValue({ data: [] } as never);
  vi.mocked(collectionsApi.integrationCapabilities).mockResolvedValue({
    data: { features: { optional: { collectionPurge: false } } },
  } as never);
  vi.mocked(evaluationApi.getHistory).mockResolvedValue({ data: [] } as never);
  vi.mocked(evaluationApi.getFeedbackStats).mockResolvedValue({ data: {} } as never);
  vi.mocked(evaluationApi.getFeedbackHistory).mockResolvedValue({ data: [] } as never);
  vi.mocked(evaluationApi.listSuites).mockResolvedValue({ data: [] } as never);
  vi.mocked(evaluationApi.listCitationTraces).mockResolvedValue({ data: { items: [] } } as never);
});

describe('Alerts: a failure is not "no active alerts"', () => {
  // The exact shape this page shipped. `if (isPending) … ; if (!data?.data?.length)`
  // cannot tell a failed request from an empty one, so a network error told an
  // operator that nothing was on fire. Its own AlertDetail, a hundred lines
  // below, already handled this correctly.
  it('reports the failure instead of rendering the empty state', async () => {
    vi.mocked(alertsApi.listActive).mockRejectedValue(new Error('503 Service Unavailable'));

    withClient(<Alerts />);

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('alert')).toHaveTextContent('alerts.loadFailed');
    expect(screen.queryByText('alerts.noActiveAlerts')).toBeNull();
  });

  it('still says "no active alerts" when the server genuinely reports none', async () => {
    vi.mocked(alertsApi.listActive).mockResolvedValue({ data: [] } as never);

    withClient(<Alerts />);

    await waitFor(() => expect(screen.getByText('alerts.noActiveAlerts')).toBeInTheDocument());
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('offers a retry that re-issues the request', async () => {
    vi.mocked(alertsApi.listActive)
      .mockRejectedValueOnce(new Error('503'))
      .mockResolvedValue({ data: [] } as never);

    withClient(<Alerts />);

    const retry = await screen.findByRole('button', { name: 'common.retry' });
    await userEvent.click(retry);

    await waitFor(() => expect(screen.getByText('alerts.noActiveAlerts')).toBeInTheDocument());
    expect(vi.mocked(alertsApi.listActive).mock.calls.length).toBeGreaterThanOrEqual(2);
  });
});

describe('ABTest: a failure is not "this experiment does not exist"', () => {
  // `if (!exp) return <EmptyState>Not found</EmptyState>` reports a network
  // error as a missing record. That sends someone to check a deletion audit
  // for an experiment that is still there.
  it('does not reach the "not found" empty state when the read fails', async () => {
    vi.mocked(abtestApi.getExperiment).mockRejectedValue(new Error('502 Bad Gateway'));

    withClient(<ABTest />);

    await waitFor(() => expect(screen.getByText('abtest.noExperiments')).toBeInTheDocument());
    expect(screen.queryByText('abtest.notFound')).toBeNull();
  });
});

describe('Search: a failure is not a search that found nothing', () => {
  // `isPending` flips false and `data` stays undefined, so nothing rendered
  // below the form. The user pressed Search and the page offered no
  // explanation at all.
  it('reports the failure rather than leaving the page blank', async () => {
    vi.mocked(collectionsApi.list).mockResolvedValue({
      data: { collections: [] },
    } as never);
    vi.mocked(searchApi.search).mockRejectedValue(new Error('504 Gateway Timeout'));

    withClient(<Search />);

    const input = screen.getByLabelText('search.inputLabel');
    await userEvent.type(input, 'quarterly revenue');
    await userEvent.click(screen.getByRole('button', { name: 'search.searchButton' }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('alert')).toHaveTextContent('search.loadFailed');
  });
});

describe('Collections: a failure is not an absent list', () => {
  // The empty state tested `data?.data?.collections?.length === 0`, which is
  // false when `data` is undefined — so a failure rendered neither the list
  // nor the empty state, leaving a page with a header and nothing else.
  it('reports the failure instead of rendering nothing', async () => {
    vi.mocked(collectionsApi.list).mockRejectedValue(new Error('500 Internal Server Error'));

    withClient(<Collections />);

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('alert')).toHaveTextContent('collections.loadFailed');
  });

  it('survives a capability response that is missing its envelope', () => {
    // `capabilityData?.data.features.optional.collectionPurge` guarded only its
    // first step, so a 200 carrying an unexpected body threw on `.data.features`
    // and took the whole page down over a flag that merely controls whether a
    // destructive button is shown.
    vi.mocked(collectionsApi.list).mockResolvedValue({
      data: { collections: [], total: 0, page: 0, size: 20 },
    } as never);
    vi.mocked(collectionsApi.integrationCapabilities).mockResolvedValue({} as never);

    expect(() => withClient(<Collections />)).not.toThrow();
  });

  it('explains a missing purge button rather than hiding it in silence', async () => {
    // Fail-closed is the right direction — a destructive action stays hidden.
    // But the page should still say why, instead of leaving the button
    // mysteriously absent.
    vi.mocked(collectionsApi.list).mockResolvedValue({
      data: { collections: [], total: 0, page: 0, size: 20 },
    } as never);
    vi.mocked(collectionsApi.integrationCapabilities).mockRejectedValue(new Error('403'));

    withClient(<Collections />);

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('alert')).toHaveTextContent('collections.capabilityLoadFailed');
  });
});

describe('Evaluation: a failure is not a report of dashes', () => {
  // The worst of the named-form cases. `isPending ? loading : cards` with the
  // cards reading `data ?? {}` produced a complete, normal-looking report in
  // which every figure was "—". It did not look like an error page; it looked
  // measured.
  it('reports the failure rather than a report full of dashes', async () => {
    vi.mocked(evaluationApi.getReport).mockRejectedValue(new Error('500 Internal Server Error'));

    withClient(<Evaluation />);

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('evaluation.reportLoadFailed'),
    );
  });
});

describe('ReembedAllButton: a failure is not a skeleton that never resolves', () => {
  // `if (isLoading || !status)` is permanent: after retries are exhausted
  // `isLoading` is false and `status` is still undefined, so the block sat on
  // a grey skeleton with no count and no explanation.
  it('reports the failure instead of a permanent skeleton', async () => {
    vi.mocked(documentsApi.getEmbeddingStatus).mockRejectedValue(new Error('503'));

    withClient(<ReembedAllButton />);

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('alert')).toHaveTextContent(
      'documents.missingEmbeddingsLoadFailed',
    );
  });
});
