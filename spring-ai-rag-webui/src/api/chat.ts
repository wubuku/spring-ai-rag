import { apiClient } from './client';
import type {
  ChatHistoryRecord as ApiChatHistoryRecord,
  ChatRequest as ApiChatRequest,
  ChatResponse,
} from '../types/api';

export type ChatHistoryRecord = ApiChatHistoryRecord;
export type ChatRequest = ApiChatRequest;

export const chatApi = {
  ask: (data: ChatRequest) =>
    apiClient.post<ChatResponse>(
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
