/**
 * The same defect, three more costumes.
 *
 * `Alerts.write-failure.test.tsx` covers the four empty `onError` handlers the
 * mutation gate had been accepting since Batch 791. These three are the
 * siblings that are not `useMutation` at all, so no mutation gate will ever
 * reach them — a thumb, an export and a preview, each of which looks exactly
 * like it worked when it did not.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ToastProvider } from '../components/Toast';
import { Chat } from './Chat';
import { Documents } from './Documents';
import { useChatSSE } from '../hooks/useSSE';
import { evaluationApi } from '../api/evaluation';
import { chatApi } from '../api/chat';
import { documentsApi } from '../api/documents';
import { modelsApi } from '../api/models';
import { collectionsApi } from '../api/collections';

const showToast = vi.fn();

vi.mock('../components/Toast', async importOriginal => {
  const actual = await importOriginal<typeof import('../components/Toast')>();
  return { ...actual, useToast: () => ({ showToast }) };
});

vi.mock('../api/evaluation', () => ({ evaluationApi: { submitFeedback: vi.fn() } }));
vi.mock('../api/chat', () => ({
  chatApi: { exportConversation: vi.fn(), listSessions: vi.fn(), getHistory: vi.fn() },
}));
vi.mock('../api/models', () => ({ modelsApi: { list: vi.fn() } }));
vi.mock('../api/collections', () => ({ collectionsApi: { list: vi.fn() } }));
vi.mock('../hooks/useSSE', () => ({
  useChatSSE: vi.fn(() => ({
    send: vi.fn(),
    stop: vi.fn(),
    isConnected: false,
  })),
}));
vi.mock('../api/documents', () => ({
  documentsApi: { list: vi.fn(), get: vi.fn(), getEmbeddingStatus: vi.fn() },
}));
vi.mock('../api/files', () => ({ filesApi: { getRawFile: vi.fn() } }));
vi.mock('../hooks/useFileUpload', () => ({
  useFileUpload: vi.fn(() => ({ uploadFiles: vi.fn(), isUploading: false, uploads: [] })),
}));

function withClient(ui: React.ReactNode, path = '/') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        {/* Chat reads its conversation id from the route, so it has to be
            mounted under a real match rather than rendered bare. */}
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/" element={ui} />
            <Route path="/chat" element={ui} />
            <Route path="/chat/:sessionId" element={ui} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
  showToast.mockClear();
  vi.mocked(modelsApi.list).mockResolvedValue({
    data: { multiModelEnabled: false, defaultModel: 'test-model', models: [] },
  } as never);
  vi.mocked(collectionsApi.list).mockResolvedValue({ data: { collections: [] } } as never);
  vi.mocked(chatApi.listSessions).mockResolvedValue({ data: [] } as never);
  vi.mocked(chatApi.getHistory).mockResolvedValue({ data: [] } as never);
  vi.mocked(documentsApi.list).mockResolvedValue({
    data: {
      documents: [
        { id: 1, title: 'Quarterly report', content: null, status: 'ACTIVE', updatedAt: '2026-10-01' },
      ],
      total: 1,
      page: 0,
      size: 20,
    },
  } as never);
  vi.mocked(documentsApi.getEmbeddingStatus).mockResolvedValue({
    data: { hasMissing: false },
  } as never);
});

/**
 * Produces one finished assistant turn through the same callbacks a real
 * stream calls. Both the thumb controls and the export menu are gated on
 * `messages.length > 0`, and a session id, so neither exists on a fresh page.
 */
async function completeOneTurn() {
  const textarea = screen.getByPlaceholderText(/chat.placeholder/);
  await userEvent.type(textarea, 'how is the retrieval?');
  await userEvent.keyboard('{Enter}');

  const opts = vi.mocked(useChatSSE).mock.calls.at(-1)![0] as Record<string, unknown>;
  await act(async () => {
    (opts.onChunk as (c: string) => void)('Here is the answer.');
    (opts.onDone as (d: { sessionId?: string }) => void)({ sessionId: 'session-1' });
  });
}

describe('Chat: a failed thumbs-up is not a received thumbs-up', () => {
  // The user pressed a button to express a judgement. If the request fails and
  // the page stays identical, the only thing they can conclude is that the
  // system received it and did nothing — and the feedback is gone.
  it('says so when submitting feedback fails', async () => {
    vi.mocked(evaluationApi.submitFeedback).mockRejectedValue(new Error('500'));

    withClient(<Chat />, '/chat/session-1');
    await completeOneTurn();

    await userEvent.click(await screen.findByRole('button', { name: 'evaluation.thumbsUp' }));

    await waitFor(() =>
      expect(showToast).toHaveBeenCalledWith('chat.feedbackError', 'error'),
    );
  });

  it('stays quiet when the feedback is accepted', async () => {
    vi.mocked(evaluationApi.submitFeedback).mockResolvedValue({} as never);

    withClient(<Chat />, '/chat/session-1');
    await completeOneTurn();

    await userEvent.click(await screen.findByRole('button', { name: 'evaluation.thumbsUp' }));

    await waitFor(() => expect(evaluationApi.submitFeedback).toHaveBeenCalled());
    expect(showToast).not.toHaveBeenCalled();
  });

  it('says so when an export fails', async () => {
    vi.mocked(chatApi.exportConversation).mockRejectedValue(new Error('403 Forbidden'));

    withClient(<Chat />, '/chat/session-1');
    await completeOneTurn();

    await userEvent.click(screen.getByRole('button', { name: /chat.export/i }));
    await userEvent.click(screen.getByRole('button', { name: 'chat.exportJson' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('chat.exportError', 'error'));
  });

  it('stays quiet when the export succeeds', async () => {
    const blob = new Blob(['{}'], { type: 'application/json' });
    vi.mocked(chatApi.exportConversation).mockResolvedValue(blob as never);

    withClient(<Chat />, '/chat/session-1');
    await completeOneTurn();

    await userEvent.click(screen.getByRole('button', { name: /chat.export/i }));
    await userEvent.click(screen.getByRole('button', { name: 'chat.exportJson' }));

    await waitFor(() => expect(chatApi.exportConversation).toHaveBeenCalled());
    expect(showToast).not.toHaveBeenCalled();
  });
});

describe('Documents: a preview that could not load is not an empty document', () => {
  // The list endpoint does not carry the body, so the preview opens first and
  // fills in afterwards. A console line left the user looking at a document
  // that appeared to have no content, with nothing saying why.
  it('reports a failed preview fetch instead of only logging it', async () => {
    vi.mocked(documentsApi.get).mockRejectedValue(new Error('502 Bad Gateway'));

    withClient(<Documents />);

    await userEvent.click(await screen.findByText('Quarterly report'));

    await waitFor(() =>
      expect(showToast).toHaveBeenCalledWith(
        'documents.previewContentLoadError',
        'error',
      ),
    );
  });
});
