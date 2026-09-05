import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { Evaluation } from './Evaluation';
import { evaluationApi } from '../api/evaluation';

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
    listSuites: vi.fn().mockResolvedValue({ data: [] }),
    createSuite: vi.fn(),
    createVersion: vi.fn(),
    createRun: vi.fn(),
    getRun: vi.fn(),
    listCitationTraces: vi.fn().mockResolvedValue({ data: { items: [] } }),
  },
}));

function renderPage(path = '/') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
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

  it('renders suites tab without crashing on an empty list', async () => {
    renderPage('/?tab=suites');
    expect(await screen.findByLabelText('evaluation.tabSuites')).toBeInTheDocument();
    expect(screen.getByText('evaluation.suitesHint')).toBeInTheDocument();
  });
});

describe('Evaluation interactions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(evaluationApi.evaluate).mockResolvedValue({ data: {} } as never);
    vi.mocked(evaluationApi.answerQuality).mockResolvedValue({
      data: { score: 0.9 },
    } as never);
    vi.mocked(evaluationApi.createSuite).mockResolvedValue({
      data: { suiteKey: 's1', name: 'Suite 1' },
    } as never);
    vi.mocked(evaluationApi.createRun).mockResolvedValue({
      data: { id: 'run-1', status: 'RUNNING' },
    } as never);
  });

  function renderOnTab(tab: string) {
    return renderPage(`/?tab=${tab}`);
  }

  it('submits the manual evaluate form with parsed doc id lists', async () => {
    const user = userEvent.setup();
    renderOnTab('report');

    await user.type(await screen.findByLabelText('evaluation.query'), 'beijing weather');
    await user.type(screen.getByLabelText('evaluation.retrievedIds'), 'doc1, doc2,');
    await user.type(screen.getByLabelText('evaluation.relevantIds'), 'doc2');
    await user.click(
      screen.getByRole('button', { name: 'evaluation.runEvaluate' }),
    );

    expect(evaluationApi.evaluate).toHaveBeenCalledWith({
      query: 'beijing weather',
      retrievedDocIds: ['doc1', 'doc2'],
      relevantDocIds: ['doc2'],
    });
  });

  it('submits the judge form fields to answer quality', async () => {
    const user = userEvent.setup();
    renderOnTab('judge');

    await user.type(await screen.findByLabelText('evaluation.query'), 'q1');
    await user.type(screen.getByLabelText('evaluation.context'), 'ctx');
    await user.type(screen.getByLabelText('evaluation.answer'), 'ans');
    await user.click(screen.getByRole('button', { name: 'evaluation.runJudge' }));

    expect(evaluationApi.answerQuality).toHaveBeenCalledWith({
      query: 'q1',
      context: 'ctx',
      answer: 'ans',
    });
  });

  it('creates a suite from the suites tab form', async () => {
    const user = userEvent.setup();
    renderOnTab('suites');

    await user.type(
      await screen.findByLabelText('evaluation.suiteKey'),
      'gold-v1',
    );
    await user.type(screen.getByLabelText('evaluation.suiteName'), 'Golden');
    await user.click(
      screen.getByRole('button', { name: 'evaluation.createSuite' }),
    );

    expect(evaluationApi.createSuite).toHaveBeenCalledWith({
      suiteKey: 'gold-v1',
      name: 'Golden',
    });
  });
});

describe('Evaluation citations tab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  function renderCitations() {
    return renderPage('/?tab=citations');
  }

  it('lists citation traces with status and outcome', async () => {
    vi.mocked(evaluationApi.listCitationTraces).mockResolvedValue({
      data: {
        items: [
          { traceId: 'trace-1', citationStatus: 'GROUNDED', outcomeCode: 'OK' },
          { traceId: 'trace-2', citationStatus: null, outcomeCode: 'NO_CITATION' },
        ],
      },
    } as never);

    renderCitations();

    expect(await screen.findByText('trace-1')).toBeInTheDocument();
    expect(screen.getByText('GROUNDED')).toBeInTheDocument();
    expect(screen.getByText('OK')).toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('shows the failure alert when citation traces fail to load', async () => {
    vi.mocked(evaluationApi.listCitationTraces).mockRejectedValue(
      new Error('boom'),
    );

    renderCitations();

    expect(
      await screen.findByText('evaluation.citationsFailed'),
    ).toBeInTheDocument();
  });
});
