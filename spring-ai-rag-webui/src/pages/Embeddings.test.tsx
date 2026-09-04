import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { Embeddings } from './Embeddings';

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
