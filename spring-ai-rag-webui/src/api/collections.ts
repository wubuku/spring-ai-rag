import { apiClient } from './client';

export interface Collection {
  id: number;
  collectionKey: string;
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
  offset: number;
  limit: number;
}

export const collectionsApi = {
  list: (params?: { page?: number; size?: number; query?: string }) =>
    apiClient.get<CollectionListResponse>('/collections', {
      params: {
        offset: (params?.page ?? 0) * (params?.size ?? 20),
        limit: params?.size ?? 20,
        query: params?.query || undefined,
      },
    }),

  get: (id: number) => apiClient.get<Collection>(`/collections/${id}`),
  getByKey: (collectionKey: string) =>
    apiClient.get<Collection>('/collections/by-key', { params: { collectionKey } }),

  create: (data: {
    name: string;
    collectionKey: string;
    description?: string;
    dimensions?: number;
    embeddingModel?: string;
  }) => apiClient.post<Collection>('/collections', data),

  update: (id: number, data: { name?: string; description?: string; enabled?: boolean }) =>
    apiClient.put(`/collections/${id}`, data),
  updateByKey: (collectionKey: string, data: { name?: string; description?: string; enabled?: boolean }) =>
    apiClient.put('/collections/by-key', data, { params: { collectionKey } }),

  delete: (id: number) => apiClient.delete(`/collections/${id}`),
  deleteByKey: (collectionKey: string) =>
    apiClient.delete('/collections/by-key', { params: { collectionKey } }),

  addDocuments: (id: number, documentIds: number[]) =>
    apiClient.post(`/collections/${id}/documents`, { documentIds }),

  removeDocuments: (id: number, documentIds: number[]) =>
    apiClient.delete(`/collections/${id}/documents`, { data: { documentIds } }),

  export: (id: number) => apiClient.get(`/collections/${id}/export`),

  importCollection: (data: { name: string; [key: string]: unknown }) =>
    apiClient.post('/collections/import', data),
};
