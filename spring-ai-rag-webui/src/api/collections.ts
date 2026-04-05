import { apiClient } from './client';

export interface Collection {
  id: number;
  name: string;
  description: string;
  embeddingModel: string;
  dimensions: number;
  enabled: boolean;
  metadata: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
  documentCount: number;
}

export interface CollectionListResponse {
  collections: Collection[];
  total: number;
  page: number;
  pageSize: number;
}

export const collectionsApi = {
  list: (params?: { page?: number; size?: number }) =>
    apiClient.get<CollectionListResponse>('/collections', { params }),

  get: (id: number) => apiClient.get<Collection>(`/collections/${id}`),

  create: (data: {
    name: string;
    description?: string;
    dimensions?: number;
    embeddingModel?: string;
  }) => apiClient.post<Collection>('/collections', data),

  update: (id: number, data: { name?: string; description?: string; enabled?: boolean }) =>
    apiClient.put(`/collections/${id}`, data),

  delete: (id: number) => apiClient.delete(`/collections/${id}`),

  addDocuments: (id: number, documentIds: number[]) =>
    apiClient.post(`/collections/${id}/documents`, { documentIds }),

  removeDocuments: (id: number, documentIds: number[]) =>
    apiClient.delete(`/collections/${id}/documents`, { data: { documentIds } }),

  export: (id: number) => apiClient.get(`/collections/${id}/export`),

  importCollection: (data: { name: string; [key: string]: unknown }) =>
    apiClient.post('/collections/import', data),
};
