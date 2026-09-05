import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { Embeddings } from './Embeddings';
import { embeddingsApi } from '../api/embeddings';

function renderEmbeddings(initialEntry = '/embeddings?collectionKey=wiki') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Embeddings />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'en' },
  }),
}));

vi.mock('../api/embeddings', () => ({
  embeddingsApi: {
    listJobs: vi.fn().mockResolvedValue({
      data: {
        items: [{
          id: '11111111-1111-1111-1111-111111111111',
          status: 'QUEUED',
          origin: 'API',
          documentId: 9,
          attemptCount: 0,
          maxAttempts: 3,
        }],
        page: 0,
        size: 50,
        totalElements: 1,
        totalPages: 1,
      },
    }),
    getJob: vi.fn(),
    cancelJob: vi.fn(),
    retryJob: vi.fn(),
    readiness: vi.fn().mockResolvedValue({
      data: {
        freshDocuments: 5,
        queuedDocuments: 2,
        runningDocuments: 1,
        failedDocuments: 0,
        staleOrMissingDocuments: 0,
      },
    }),
    derivationReadiness: vi.fn().mockResolvedValue({
      data: {
        readyDocuments: 8,
        keywordOnlyDocuments: 1,
        indexingDocuments: 0,
        localUnavailableDocuments: 0,
        corruptDocuments: 0,
        vectorRepairNeededDocuments: 2,
      },
    }),
    previewRepair: vi.fn().mockResolvedValue({
      data: {
        repairId: 'repair-1',
        collectionKey: 'wiki',
        previewFingerprint: 'fp-1',
        previewToken: 'token-1',
        expiresAt: '2026-09-07T00:00:00Z',
        items: [
          { documentId: 9, action: 'REBUILD_LOCAL', reasonCode: 'LOCAL_STALE' },
          { documentId: 10, action: 'QUEUE_VECTOR', reasonCode: 'VECTOR_STALE' },
        ],
        actionCounts: { REBUILD_LOCAL: 1, QUEUE_VECTOR: 1 },
        skippedDocuments: 0,
      },
    }),
    applyRepair: vi.fn().mockResolvedValue({
      data: { repairId: 'repair-1', processed: 2, failed: 0 },
    }),
  },
}));

function renderPage(initialEntry = '/embeddings') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Embeddings />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('Embeddings page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders job list from JSON', async () => {
    renderPage();
    expect(screen.getByText('embeddings.title')).toBeInTheDocument();
    expect(await screen.findByText('QUEUED')).toBeInTheDocument();
    expect(screen.getByText('API')).toBeInTheDocument();
  });

  it('renders readiness stats on the shared card skin', async () => {
    const { container } = renderPage('/embeddings?collectionKey=default');

    // Regression guard: the stat cards used to borrow another page's CSS
    // module, so the card skin silently disappeared when that class moved.
    expect(await screen.findByText('embeddings.fresh')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    // The hashed card skin class is "_card_<hash>"; the five readiness stats
    // each render inside one.
    expect(
      container.querySelectorAll('[class*="_card_"]').length,
    ).toBeGreaterThanOrEqual(5);
    expect(
      screen.getByText('embeddings.fresh').closest('[class*="_card_"]'),
    ).not.toBeNull();
  });
});

describe('Embeddings interactions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });


  function renderEmbeddings(initialEntry = '/embeddings?collectionKey=wiki') {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    return render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[initialEntry]}>
          <Embeddings />
        </MemoryRouter>
      </QueryClientProvider>
    );
  }

  it('renders six readiness stat cards and the repair preview trigger', async () => {
    renderEmbeddings();

    for (const label of [
      'embeddings.readyDocuments',
      'embeddings.keywordOnlyDocuments',
      'embeddings.indexingDocuments',
      'embeddings.localUnavailableDocuments',
      'embeddings.corruptDocuments',
      'embeddings.vectorRepairNeededDocuments',
    ]) {
      expect(await screen.findByText(label)).toBeInTheDocument();
    }
    expect(
      screen.getByRole('button', { name: 'embeddings.previewRepair' }),
    ).toBeInTheDocument();
  });

  it('cancels and retries a job through its row actions', async () => {
    const user = userEvent.setup();
    renderEmbeddings();

    expect(await screen.findByText('QUEUED')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'embeddings.cancel' }));
    expect(embeddingsApi.cancelJob).toHaveBeenCalledWith(
      '11111111-1111-1111-1111-111111111111',
    );

    await user.click(screen.getByRole('button', { name: 'embeddings.retry' }));
    expect(embeddingsApi.retryJob).toHaveBeenCalledWith(
      '11111111-1111-1111-1111-111111111111',
    );
  });
});

describe('Embeddings derivation repair flow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('opens the repair preview dialog and applies it', async () => {
    const user = userEvent.setup();
    renderEmbeddings('/embeddings?collectionKey=wiki');

    await user.click(
      await screen.findByRole('button', { name: 'embeddings.previewRepair' }),
    );

    const dialog = await screen.findByRole('dialog', {
      name: 'embeddings.repairPreview',
    });
    expect(dialog).toHaveTextContent('embeddings.repairDocuments');
    expect(screen.getByText('REBUILD_LOCAL')).toBeInTheDocument();

    await user.click(
      screen.getByRole('button', { name: 'embeddings.applyRepair' }),
    );
    expect(embeddingsApi.applyRepair).toHaveBeenCalledTimes(1);
  });
});
