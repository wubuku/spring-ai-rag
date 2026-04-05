// Health API
export interface ComponentHealth {
  database?: string;
  pgvector?: string;
  cache?: string;
  llmCircuitBreaker?: string;
}

export interface HealthResponse {
  status: string;
  components?: ComponentHealth;
  timestamp?: number;
}

// Metrics API
export interface RagMetricsResponse {
  totalRetrievals?: number;
  totalLlmCalls?: number;
  totalLlmTokens?: number;
  avgRetrievalLatencyMs?: number;
  cacheHitRate?: number;
  activeConversations?: number;
}

export interface ModelMetrics {
  provider: string;
  totalCalls: number;
  totalTokens: number;
  avgLatencyMs: number;
}

export interface ModelMetricsResponse {
  models: ModelMetrics[];
  multiModelEnabled: boolean;
}

// Documents API
export interface Document {
  id: number;
  title: string;
  content: string;
  source?: string;
  contentHash: string;
  documentType?: string;
  metadata?: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
  collectionId?: number;
}

export interface DocumentListResponse {
  documents: Document[];
  total: number;
  offset: number;
  limit: number;
}

export interface BatchCreateResponse {
  documentIds: number[];
  failed: number;
}

// Collections API
export interface Collection {
  id: number;
  name: string;
  description?: string;
  documentCount: number;
  vectorDimension: number;
  createdAt: string;
  updatedAt: string;
}

export interface CollectionListResponse {
  collections: Collection[];
  total: number;
  page: number;
  pageSize: number;
}

// Chat API
export interface ChatSource {
  documentId: number;
  title: string;
  score: number;
  chunkContent?: string;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  sources?: ChatSource[];
}

export interface ChatRequest {
  message: string;
  collectionId?: number;
  conversationId?: string;
  useHybridSearch?: boolean;
}

export interface ChatResponse {
  response: string;
  conversationId: string;
  sources?: ChatSource[];
}

export interface ChatStreamEvent {
  type: 'chunk' | 'done' | 'sources' | 'error';
  data?: string;
  sources?: ChatSource[];
  conversationId?: string;
  error?: string;
}

// Search API
export interface RetrievalResult {
  documentId: number;
  title: string;
  content: string;
  score: number;
  collectionId?: number;
}

export interface SearchRequest {
  query: string;
  collectionId?: number;
  topK?: number;
  vectorWeight?: number;
  fulltextWeight?: number;
  useHybridSearch?: boolean;
}

export interface SearchResponse {
  results: RetrievalResult[];
  query: string;
  totalResults: number;
}

// Alerts API
export interface Alert {
  id: number;
  alertType: string;
  severity: string;
  message: string;
  firedAt: string;
  resolvedAt?: string;
  silencedUntil?: string;
}

export interface AlertListResponse {
  alerts: Alert[];
  total: number;
}

// SSE Event Types
export interface EmbeddingProgressEvent {
  type: 'embedding_progress';
  documentId: number;
  progress: number;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
}

export interface ChatStreamChunkEvent {
  type: 'chunk';
  content: string;
  done: boolean;
}

export interface ChatStreamSourcesEvent {
  type: 'sources';
  sources: ChatSource[];
  conversationId: string;
}
