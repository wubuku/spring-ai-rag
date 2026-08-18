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
    readiness: vi.fn(),
  },
}));

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
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
});
