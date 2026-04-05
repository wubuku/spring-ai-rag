import { apiClient } from './client';

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  sources?: Array<{
    documentId: number;
    title: string;
    score: number;
    chunkContent?: string;
  }>;
}

export interface ChatRequest {
  message: string;
  collectionId?: number;
  conversationId?: string;
  useHybridSearch?: boolean;
}

export const chatApi = {
  ask: (data: ChatRequest) =>
    apiClient.post<{ response: string; conversationId: string; sources?: unknown[] }>(
      '/chat',
      data
    ),

  stream: (data: ChatRequest): EventSource =>
    new EventSource(
      `/api/v1/rag/chat/stream?message=${encodeURIComponent(data.message)}&collectionId=${data.collectionId ?? ''}&conversationId=${data.conversationId ?? ''}`
    ),

  getHistory: (conversationId: string, limit = 50) =>
    apiClient.get<{ messages: ChatMessage[] }>(`/chat/history/${conversationId}?limit=${limit}`),

  clearHistory: (conversationId: string) => apiClient.delete(`/chat/history/${conversationId}`),
};
