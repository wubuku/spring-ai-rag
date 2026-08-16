import { apiClient } from './client';
import type { CollectionScopeMode } from '../types/api';

export interface SearchResult {
  documentId: string;
  title?: string;
  content?: string;
  chunkText?: string;
  score?: number;
  vectorScore?: number;
  fulltextScore?: number;
  collectionId?: number;
  collectionKey?: string;
  source?: string;
  originalFilename?: string;
  fileDirectoryPath?: string;
  indexedFilePath?: string;
  originalFilePath?: string;
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
    collectionScopeMode?: CollectionScopeMode;
    collectionKeys?: string[];
  }) => apiClient.get<SearchResponse>('/search', {
    params,
    paramsSerializer: { indexes: null },
  }),
};
