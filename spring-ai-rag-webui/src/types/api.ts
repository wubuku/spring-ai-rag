// Health API
export type CollectionScopeMode =
  | 'CALLER_VISIBLE'
  | 'ANY_COLLECTION'
  | 'SELECTED_COLLECTIONS';

export type ChatMode = 'KNOWLEDGE' | 'AGENT' | 'PLAIN';

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

export type UsageNumericValue = number | string;

export interface LlmUsageTotals {
  logicalExecutionCount: number;
  invocationCount: number;
  succeededCount: number;
  failedCount: number;
  cancelledCount: number;
  promptTokens: UsageNumericValue;
  completionTokens: UsageNumericValue;
  totalTokens: UsageNumericValue;
  usageAvailableCount: number;
  usageUnavailableCount: number;
  pricingUnavailableCount: number;
  costUnavailableCount: number;
}

export interface LlmUsageResponse {
  recordingEnabled: boolean;
  localLostEventsSinceStart: number;
  scope: {
    type: 'SELF' | 'ALL' | 'PRINCIPAL';
    principalId: string | null;
  };
  from: string;
  to: string;
  totals: LlmUsageTotals;
  costs: Array<{
    unit: string;
    configuredCost: UsageNumericValue;
    invocationCount: number;
    costAvailableCount: number;
  }>;
  byModel: Array<{
    modelRef: string;
    totals: LlmUsageTotals;
  }>;
  byPurpose: Array<{
    purpose: 'CHAT' | 'QUERY_TRANSFORM' | 'QUERY_EXPAND' | 'SUMMARY';
    totals: LlmUsageTotals;
  }>;
  byMode: Array<{
    mode: ChatMode;
    totals: LlmUsageTotals;
  }>;
  byDay: Array<{
    day: string;
    totals: LlmUsageTotals;
  }>;
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
  collectionKey?: string;
  collectionName?: string;
  chunkCount?: number;
  externalId?: string | null;
  sourceRevision?: string | null;
  sourceDeletedAt?: string | null;
  processingStatus?: string | null;
  processingError?: string | null;
  embeddingFresh?: boolean;
  enabled?: boolean;
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
  collectionKey: string;
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
  documentId: string | number;
  citationId?: string;
  chunkIndex?: number;
  title?: string;
  chunkText?: string;
  score?: number;
  vectorScore?: number;
  fulltextScore?: number;
  originalFilename?: string;
  documentType?: string;
  collectionKey?: string;
  sourceType?: string;
  metadata?: Record<string, unknown>;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  sources?: ChatSource[];
}

export interface ChatHistoryRecord {
  id: number;
  sessionId: string;
  userMessage: string;
  aiResponse: string;
  relatedDocumentIds?: number[];
  metadata?: Record<string, unknown>;
  sources?: ChatSource[];
  status?: string;
  mode?: ChatMode;
  requestedModel?: string;
  resolvedModel?: string;
  createdAt: string;
}

export interface ChatRequest {
  message: string;
  mode?: ChatMode;
  collectionScopeMode?: CollectionScopeMode;
  /** @deprecated use collectionKeys */
  collectionId?: number;
  collectionIds?: number[];
  collectionKeys?: string[];
  sessionId?: string;
  /** @deprecated use sessionId */
  conversationId?: string;
  model?: string;
  useHybridSearch?: boolean;
  useRerank?: boolean;
  maxResults?: number;
}

export interface ChatResponse {
  answer: string;
  sessionId: string;
  traceId?: string;
  mode?: ChatMode;
  requestedModel?: string;
  resolvedModel?: string;
  usage?: Record<string, unknown>;
  finishReason?: string;
  metadata?: Record<string, unknown>;
  sources?: ChatSource[];
}

export interface ChatStreamEvent {
  type: 'content' | 'tool_start' | 'tool_result' | 'done' | 'sources' | 'error';
  data?: string;
  sources?: ChatSource[];
  sessionId?: string;
  error?: string;
}

// Search API
export interface RetrievalResult {
  documentId: number;
  title: string;
  content?: string;
  chunkText?: string;
  score: number;
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

export interface SearchRequest {
  query: string;
  collectionScopeMode?: CollectionScopeMode;
  collectionKeys?: string[];
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
  sessionId: string;
}
