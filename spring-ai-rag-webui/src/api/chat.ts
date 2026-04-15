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
  apiKey?: string;
}

export const chatApi = {
  ask: (data: ChatRequest) =>
    apiClient.post<{ response: string; conversationId: string; sources?: unknown[] }>(
      '/chat',
      data
    ),

  stream: (data: ChatRequest): EventSource =>
    new EventSource(
      `/api/v1/rag/chat/stream?apiKey=${encodeURIComponent(data.apiKey ?? '')}&message=${encodeURIComponent(data.message)}&collectionId=${data.collectionId ?? ''}&conversationId=${data.conversationId ?? ''}`
    ),

  getHistory: (conversationId: string, limit = 50) =>
    apiClient.get<{ messages: ChatMessage[] }>(`/chat/history/${conversationId}?limit=${limit}`),

  clearHistory: (conversationId: string) => apiClient.delete(`/chat/history/${conversationId}`),

  exportConversation: (conversationId: string, format: 'json' | 'md') =>
    fetch(`/api/v1/rag/chat/export/${conversationId}?format=${format}`).then(res => {
      if (!res.ok) throw new Error('Export failed');
      return res.blob();
    }),
};
