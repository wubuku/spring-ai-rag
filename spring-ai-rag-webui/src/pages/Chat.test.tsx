import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { Chat } from './Chat';
import { useChatSSE } from '../hooks/useSSE';
import { modelsApi } from '../api/models';
import { collectionsApi } from '../api/collections';
import { chatApi } from '../api/chat';

// Mock useChatSSE at module level
const mockSend = vi.fn();
const mockClose = vi.fn();

vi.mock('../hooks/useSSE', () => ({
  useChatSSE: vi.fn(() => ({
    send: mockSend,
    close: mockClose,
    stop: mockClose,
    isConnected: false,
  })),
}));

vi.mock('../api/collections', () => ({
  collectionsApi: {
    list: vi.fn(),
  },
}));

vi.mock('../api/models', () => ({
  modelsApi: {
    list: vi.fn(),
  },
}));

vi.mock('../api/chat', () => ({
  chatApi: {
    getHistory: vi.fn(),
    exportConversation: vi.fn(),
  },
}));

vi.mock('../utils/modelPreference', () => ({
  getSelectedModel: vi.fn(() => ''),
  saveSelectedModel: vi.fn(),
}));

function renderChat(initialEntry = '/chat') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/chat" element={<Chat />} />
          <Route path="/chat/:sessionId" element={<Chat />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('Chat', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.sessionStorage.clear();
    // Reset to default mock return value
    (useChatSSE as ReturnType<typeof vi.fn>).mockReturnValue({
      send: mockSend,
      close: mockClose,
      stop: mockClose,
      isConnected: false,
    });
    (modelsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        multiModelEnabled: false,
        defaultProvider: 'openai',
        defaultModel: 'openai',
        availableProviders: ['openai'],
        fallbackChain: [],
        models: [],
      },
    });
    (collectionsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: { collections: [], total: 0 },
    });
    (chatApi.getHistory as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: [],
    });
  });

  it('renders page title', () => {
    renderChat();
    expect(screen.getByText('chat.title')).toBeInTheDocument();
  });

  it('renders empty state message when no messages', () => {
    renderChat();
    expect(screen.getByText(/chat.noMessages/)).toBeInTheDocument();
  });

  it('renders textarea and send button', () => {
    renderChat();
    expect(screen.getByPlaceholderText(/chat.placeholder/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /chat.send/ })).toBeInTheDocument();
  });

  it('send button is disabled when input is empty', () => {
    renderChat();
    const sendBtn = screen.getByRole('button', { name: /chat.send/ });
    expect(sendBtn).toBeDisabled();
  });

  it('send button is enabled when input has text', () => {
    renderChat();
    const textarea = screen.getByPlaceholderText(/chat.placeholder/);
    fireEvent.change(textarea, { target: { value: 'Hello world' } });
    const sendBtn = screen.getByRole('button', { name: /chat.send/ });
    expect(sendBtn).not.toBeDisabled();
  });

  it('restores an unsent draft after the route component is remounted', () => {
    const first = renderChat('/chat?mode=AGENT');
    fireEvent.change(screen.getByPlaceholderText(/chat.placeholder/), {
      target: { value: 'Keep this draft' },
    });
    first.unmount();

    renderChat('/chat?mode=AGENT');
    expect(screen.getByPlaceholderText(/chat.placeholder/))
      .toHaveValue('Keep this draft');
    expect(screen.getByTestId('chat-mode-select')).toHaveValue('AGENT');
  });

  it('pressing Enter submits the message', async () => {
    renderChat();
    const textarea = screen.getByPlaceholderText(/chat.placeholder/);
    fireEvent.change(textarea, { target: { value: 'Hello' } });
    await act(async () => {
      fireEvent.keyDown(textarea, { key: 'Enter', shiftKey: false });
    });
    expect(mockSend).toHaveBeenCalledWith({
      message: 'Hello',
      sessionId: undefined,
      model: undefined,
      mode: 'KNOWLEDGE',
      collectionScopeMode: 'CALLER_VISIBLE',
      collectionKeys: undefined,
    });
  });

  it('Shift+Enter does not submit', async () => {
    renderChat();
    const textarea = screen.getByPlaceholderText(/chat.placeholder/);
    fireEvent.change(textarea, { target: { value: 'Hello' } });
    await act(async () => {
      fireEvent.keyDown(textarea, { key: 'Enter', shiftKey: true });
    });
    expect(mockSend).not.toHaveBeenCalled();
  });

  it('New Chat button is not visible when no messages', () => {
    renderChat();
    expect(screen.queryByRole('button', { name: /chat.newChat/ })).not.toBeInTheDocument();
  });

  it('clicking send button submits message', async () => {
    renderChat();
    const textarea = screen.getByPlaceholderText(/chat.placeholder/);
    fireEvent.change(textarea, { target: { value: 'Test query' } });
    const sendBtn = screen.getByRole('button', { name: /chat.send/ });
    await act(async () => {
      fireEvent.click(sendBtn);
    });
    expect(mockSend).toHaveBeenCalledWith({
      message: 'Test query',
      sessionId: undefined,
      model: undefined,
      mode: 'KNOWLEDGE',
      collectionScopeMode: 'CALLER_VISIBLE',
      collectionKeys: undefined,
    });
  });

  it('send button becomes an enabled stop action when isConnected is true', () => {
    (useChatSSE as ReturnType<typeof vi.fn>).mockReturnValue({
      send: mockSend,
      close: mockClose,
      stop: mockClose,
      isConnected: true,
    });
    renderChat();
    // The active request must be cancellable without re-enabling message submission.
    const sendBtn = screen.getByRole('button', { name: /chat.stop/i });
    expect(sendBtn).toBeEnabled();
    fireEvent.click(sendBtn);
    expect(mockClose).toHaveBeenCalled();
  });

  it('passes the selected runtime model to SSE', async () => {
    (modelsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        multiModelEnabled: true,
        defaultProvider: 'minimax',
        defaultModel: 'minimax/MiniMax-M2.7',
        availableProviders: ['minimax', 'openrouter'],
        fallbackChain: ['openrouter/xiaomi/mimo-v2-pro'],
        models: [
          {
            ref: 'minimax/MiniMax-M2.7',
            provider: 'minimax',
            providerName: 'MiniMax',
            modelId: 'MiniMax-M2.7',
            name: 'MiniMax M2.7',
            apiType: 'anthropic-messages',
            available: true,
          },
          {
            ref: 'openrouter/xiaomi/mimo-v2-pro',
            provider: 'openrouter',
            providerName: 'OpenRouter',
            modelId: 'xiaomi/mimo-v2-pro',
            name: 'MiMo V2 Pro',
            apiType: 'openai-completions',
            available: true,
          },
        ],
      },
    });
    renderChat();

    const user = userEvent.setup();
    const modelSelect = await screen.findByTestId('chat-model-select');
    await screen.findByRole('option', { name: /OpenRouter/ });
    await user.selectOptions(modelSelect, 'openrouter/xiaomi/mimo-v2-pro');
    await waitFor(() => {
      expect(modelSelect).toHaveValue('openrouter/xiaomi/mimo-v2-pro');
    });
    const textarea = screen.getByPlaceholderText(/chat.placeholder/);
    fireEvent.change(textarea, { target: { value: 'Use this model' } });
    fireEvent.click(screen.getByRole('button', { name: /chat.send/ }));

    expect(mockSend).toHaveBeenCalledWith({
      message: 'Use this model',
      sessionId: undefined,
      model: 'openrouter/xiaomi/mimo-v2-pro',
      mode: 'KNOWLEDGE',
      collectionScopeMode: 'CALLER_VISIBLE',
      collectionKeys: undefined,
    });
  });

  it('passes multiple selected collection keys to SSE', async () => {
    (collectionsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        collections: [
          {
            id: 10,
            collectionKey: 'customer:manual',
            name: 'Knowledge Base',
            documentCount: 2,
          },
          {
            id: 11,
            collectionKey: 'customer:faq',
            name: 'FAQ',
            documentCount: 3,
          },
        ],
        total: 2,
      },
    });
    renderChat();

    fireEvent.click(screen.getByTestId('chat-scope-SELECTED_COLLECTIONS'));
    const manual = await screen.findByRole('checkbox', { name: /Knowledge Base/ });
    const faq = screen.getByRole('checkbox', { name: /FAQ/ });
    fireEvent.click(manual);
    fireEvent.click(faq);
    fireEvent.change(screen.getByPlaceholderText(/chat.placeholder/), {
      target: { value: 'Scoped question' },
    });
    fireEvent.click(screen.getByRole('button', { name: /chat.send/ }));

    expect(mockSend).toHaveBeenCalledWith({
      message: 'Scoped question',
      sessionId: undefined,
      model: undefined,
      mode: 'KNOWLEDGE',
      collectionScopeMode: 'SELECTED_COLLECTIONS',
      collectionKeys: ['customer:faq', 'customer:manual'],
    });
  });

  it('sends ANY_COLLECTION without selected keys', async () => {
    renderChat();
    fireEvent.click(screen.getByTestId('chat-scope-ANY_COLLECTION'));
    fireEvent.change(screen.getByPlaceholderText(/chat.placeholder/), {
      target: { value: 'Assigned documents only' },
    });
    fireEvent.click(screen.getByRole('button', { name: /chat.send/ }));

    expect(mockSend).toHaveBeenCalledWith({
      message: 'Assigned documents only',
      sessionId: undefined,
      model: undefined,
      mode: 'KNOWLEDGE',
      collectionScopeMode: 'ANY_COLLECTION',
      collectionKeys: undefined,
    });
  });

  it('omits retrieval scope when sending in PLAIN mode', async () => {
    renderChat();
    fireEvent.click(screen.getByTestId('chat-scope-SELECTED_COLLECTIONS'));
    await userEvent.selectOptions(
      screen.getByTestId('chat-mode-select'),
      'PLAIN',
    );

    expect(screen.queryByTestId('chat-scope-CALLER_VISIBLE'))
      .not.toBeInTheDocument();
    fireEvent.change(screen.getByPlaceholderText(/chat.placeholder/), {
      target: { value: 'Plain conversation' },
    });
    fireEvent.click(screen.getByRole('button', { name: /chat.send/ }));

    expect(mockSend).toHaveBeenCalledWith({
      message: 'Plain conversation',
      sessionId: undefined,
      model: undefined,
      mode: 'PLAIN',
      collectionScopeMode: undefined,
      collectionKeys: undefined,
    });
  });

  it('does not submit an empty selected collection scope', () => {
    renderChat();
    fireEvent.click(screen.getByTestId('chat-scope-SELECTED_COLLECTIONS'));
    fireEvent.change(screen.getByPlaceholderText(/chat.placeholder/), {
      target: { value: 'Blocked until a collection is selected' },
    });

    expect(screen.getByRole('button', { name: /chat.send/ })).toBeDisabled();
    expect(mockSend).not.toHaveBeenCalled();
  });

  it('loads a directly addressed chat session from history', async () => {
    (chatApi.getHistory as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: [{
        id: 9,
        sessionId: 'session-9',
        userMessage: 'Earlier question',
        aiResponse: 'Earlier answer',
        createdAt: '2026-08-17T08:00:00',
      }],
    });

    renderChat('/chat/session-9');

    expect(await screen.findByText('Earlier question')).toBeInTheDocument();
    expect(screen.getByText('Earlier answer')).toBeInTheDocument();
    expect(chatApi.getHistory).toHaveBeenCalledWith('session-9');
  });
});
