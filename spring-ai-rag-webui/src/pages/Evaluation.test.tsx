import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { Evaluation } from './Evaluation';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'en' },
  }),
}));

vi.mock('../api/evaluation', () => ({
  evaluationApi: {
    getReport: vi.fn().mockResolvedValue({
      data: { avgMrr: 0.5, avgNdcg: 0.4, totalEvaluations: 3 },
    }),
    getHistory: vi.fn().mockResolvedValue({ data: [] }),
    getFeedbackStats: vi.fn().mockResolvedValue({ data: { thumbsUp: 1 } }),
    getFeedbackHistory: vi.fn().mockResolvedValue({ data: [] }),
    evaluate: vi.fn(),
    answerQuality: vi.fn(),
  },
}));

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <Evaluation />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('Evaluation page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders title and report tab', async () => {
    renderPage();
    expect(screen.getByText('evaluation.title')).toBeInTheDocument();
    expect(await screen.findByText('evaluation.avgMrr')).toBeInTheDocument();
  });
});
