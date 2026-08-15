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
  /** @deprecated use collectionKeys */
  collectionId?: number;
  collectionIds?: number[];
  collectionKeys?: string[];
  conversationId?: string;
  useHybridSearch?: boolean;
}

export const chatApi = {
  ask: (data: ChatRequest) =>
    apiClient.post<{ response: string; conversationId: string; sources?: unknown[] }>(
      '/chat',
      data
    ),

  getHistory: (conversationId: string, limit = 50) =>
    apiClient.get<{ messages: ChatMessage[] }>(`/chat/history/${conversationId}?limit=${limit}`),

  clearHistory: (conversationId: string) => apiClient.delete(`/chat/history/${conversationId}`),

  exportConversation: (conversationId: string, format: 'json' | 'md') =>
    apiClient.get<Blob>(`/chat/export/${encodeURIComponent(conversationId)}`, {
      params: { format },
      responseType: 'blob',
    }).then(response => response.data),
};
