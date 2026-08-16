import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Chat } from './Chat';
import { useChatSSE } from '../hooks/useSSE';
import { modelsApi } from '../api/models';
import { collectionsApi } from '../api/collections';

// Mock useChatSSE at module level
const mockSend = vi.fn();
const mockClose = vi.fn();

vi.mock('../hooks/useSSE', () => ({
  useChatSSE: vi.fn(() => ({
    send: mockSend,
    close: mockClose,
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

vi.mock('../utils/modelPreference', () => ({
  getSelectedModel: vi.fn(() => ''),
  saveSelectedModel: vi.fn(),
}));

function renderChat(ui = <Chat />) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>{ui}</QueryClientProvider>
  );
}

describe('Chat', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Reset to default mock return value
    (useChatSSE as ReturnType<typeof vi.fn>).mockReturnValue({
      send: mockSend,
      close: mockClose,
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

  it('pressing Enter submits the message', async () => {
    renderChat();
    const textarea = screen.getByPlaceholderText(/chat.placeholder/);
    fireEvent.change(textarea, { target: { value: 'Hello' } });
    await act(async () => {
      fireEvent.keyDown(textarea, { key: 'Enter', shiftKey: false });
    });
    expect(mockSend).toHaveBeenCalledWith({
      message: 'Hello',
      conversationId: undefined,
      model: undefined,
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
      conversationId: undefined,
      model: undefined,
      collectionScopeMode: 'CALLER_VISIBLE',
      collectionKeys: undefined,
    });
  });

  it('send button is disabled and shows ... when isConnected is true', () => {
    (useChatSSE as ReturnType<typeof vi.fn>).mockReturnValue({
      send: mockSend,
      close: mockClose,
      isConnected: true,
    });
    renderChat();
    // When connected, button should show "..." and be disabled
    const sendBtn = screen.getByRole('button', { name: /\.\.\./i });
    expect(sendBtn).toBeDisabled();
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
      conversationId: undefined,
      model: 'openrouter/xiaomi/mimo-v2-pro',
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
      conversationId: undefined,
      model: undefined,
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
      conversationId: undefined,
      model: undefined,
      collectionScopeMode: 'ANY_COLLECTION',
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
});
