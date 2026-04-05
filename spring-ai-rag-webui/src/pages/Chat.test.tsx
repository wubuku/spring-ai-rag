import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { Chat } from './Chat';
import { useChatSSE } from '../hooks/useSSE';

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

describe('Chat', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Reset to default mock return value
    (useChatSSE as ReturnType<typeof vi.fn>).mockReturnValue({
      send: mockSend,
      close: mockClose,
      isConnected: false,
    });
  });

  it('renders page title', () => {
    render(<Chat />);
    expect(screen.getByText('chat.title')).toBeInTheDocument();
  });

  it('renders empty state message when no messages', () => {
    render(<Chat />);
    expect(screen.getByText(/chat.noMessages/)).toBeInTheDocument();
  });

  it('renders textarea and send button', () => {
    render(<Chat />);
    expect(screen.getByPlaceholderText(/chat.placeholder/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /chat.send/ })).toBeInTheDocument();
  });

  it('send button is disabled when input is empty', () => {
    render(<Chat />);
    const sendBtn = screen.getByRole('button', { name: /chat.send/ });
    expect(sendBtn).toBeDisabled();
  });

  it('send button is enabled when input has text', () => {
    render(<Chat />);
    const textarea = screen.getByPlaceholderText(/chat.placeholder/);
    fireEvent.change(textarea, { target: { value: 'Hello world' } });
    const sendBtn = screen.getByRole('button', { name: /chat.send/ });
    expect(sendBtn).not.toBeDisabled();
  });

  it('pressing Enter submits the message', async () => {
    render(<Chat />);
    const textarea = screen.getByPlaceholderText(/chat.placeholder/);
    fireEvent.change(textarea, { target: { value: 'Hello' } });
    await act(async () => {
      fireEvent.keyDown(textarea, { key: 'Enter', shiftKey: false });
    });
    expect(mockSend).toHaveBeenCalledWith('Hello', undefined, undefined);
  });

  it('Shift+Enter does not submit', async () => {
    render(<Chat />);
    const textarea = screen.getByPlaceholderText(/chat.placeholder/);
    fireEvent.change(textarea, { target: { value: 'Hello' } });
    await act(async () => {
      fireEvent.keyDown(textarea, { key: 'Enter', shiftKey: true });
    });
    expect(mockSend).not.toHaveBeenCalled();
  });

  it('New Chat button is not visible when no messages', () => {
    render(<Chat />);
    expect(screen.queryByRole('button', { name: /chat.newChat/ })).not.toBeInTheDocument();
  });

  it('clicking send button submits message', async () => {
    render(<Chat />);
    const textarea = screen.getByPlaceholderText(/chat.placeholder/);
    fireEvent.change(textarea, { target: { value: 'Test query' } });
    const sendBtn = screen.getByRole('button', { name: /chat.send/ });
    await act(async () => {
      fireEvent.click(sendBtn);
    });
    expect(mockSend).toHaveBeenCalledWith('Test query', undefined, undefined);
  });

  it('send button is disabled and shows ... when isConnected is true', () => {
    (useChatSSE as ReturnType<typeof vi.fn>).mockReturnValue({
      send: mockSend,
      close: mockClose,
      isConnected: true,
    });
    render(<Chat />);
    // When connected, button should show "..." and be disabled
    const sendBtn = screen.getByRole('button', { name: /\.\.\./i });
    expect(sendBtn).toBeDisabled();
  });
});
