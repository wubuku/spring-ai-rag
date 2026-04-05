import { apiClient } from './client';

export interface SearchResult {
  documentId: string;
  title: string;
  content: string;
  score: number;
  collectionId?: number;
}

export interface SearchResponse {
  results: SearchResult[];
  total: number;
  query: string;
}

export const searchApi = {
  search: (params: {
    query: string;
    limit?: number;
    useHybrid?: boolean;
    vectorWeight?: number;
    fulltextWeight?: number;
  }) =>
    apiClient.get<SearchResponse>('/search', { params }),
};
