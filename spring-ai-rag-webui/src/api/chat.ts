import { apiClient } from './client';
import type { CollectionScopeMode } from '../types/api';

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

export interface ChatHistoryRecord {
  id: number;
  sessionId: string;
  userMessage: string;
  aiResponse: string;
  relatedDocumentIds?: number[];
  metadata?: Record<string, unknown>;
  createdAt: string;
}

export interface ChatRequest {
  message: string;
  collectionScopeMode?: CollectionScopeMode;
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
    apiClient.get<ChatHistoryRecord[]>(
      `/chat/history/${encodeURIComponent(conversationId)}`,
      { params: { limit } },
    ),

  clearHistory: (conversationId: string) => apiClient.delete(`/chat/history/${conversationId}`),

  exportConversation: (conversationId: string, format: 'json' | 'md') =>
    apiClient.get<Blob>(`/chat/export/${encodeURIComponent(conversationId)}`, {
      params: { format },
      responseType: 'blob',
    }).then(response => response.data),
};
