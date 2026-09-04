import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { chatApi } from './chat';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('chatApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('posts the chat request body to /chat', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);
    const request = { message: 'hi', sessionId: 's1', mode: 'KNOWLEDGE' };
    await chatApi.ask(request as never);
    expect(apiClient.post).toHaveBeenCalledWith('/chat', request);
  });

  it('URL-encodes the conversation id and defaults the history limit', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: [] } as never);
    await chatApi.getHistory('session/with slash');
    expect(apiClient.get).toHaveBeenCalledWith(
      '/chat/history/session%2Fwith%20slash',
      { params: { limit: 50 } },
    );
  });

  it('exports conversations as a blob download with the requested format', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: new Blob() } as never);
    await chatApi.exportConversation('c1', 'md');
    expect(apiClient.get).toHaveBeenCalledWith('/chat/export/c1', {
      params: { format: 'md' },
      responseType: 'blob',
    });
  });

  it('clears history through a delete', async () => {
    vi.mocked(apiClient.delete).mockResolvedValue({ data: {} } as never);
    await chatApi.clearHistory('c1');
    expect(apiClient.delete).toHaveBeenCalledWith('/chat/history/c1');
  });
});
