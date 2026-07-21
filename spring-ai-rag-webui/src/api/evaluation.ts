import { apiClient } from './client';

export interface EvaluationReport {
  avgMrr?: number;
  avgNdcg?: number;
  avgHitRate?: number;
  avgPrecision?: number;
  avgRecall?: number;
  totalEvaluations?: number;
  [key: string]: unknown;
}

export interface AggregatedMetrics {
  [key: string]: unknown;
}

export interface EvaluationHistoryItem {
  id?: number;
  query?: string;
  mrr?: number;
  ndcg?: number;
  hitRate?: number;
  precisionAtK?: number;
  recallAtK?: number;
  createdAt?: string;
  [key: string]: unknown;
}

export interface FeedbackStats {
  totalFeedback?: number;
  thumbsUp?: number;
  thumbsDown?: number;
  satisfactionRate?: number;
  [key: string]: unknown;
}

export interface FeedbackItem {
  id?: number;
  sessionId?: string;
  query?: string;
  feedbackType?: string;
  rating?: number;
  comment?: string;
  createdAt?: string;
  [key: string]: unknown;
}

export interface EvaluateRequest {
  query: string;
  retrievedDocIds: string[];
  relevantDocIds: string[];
  evaluationMethod?: string;
  evaluatorId?: string;
}

export interface AnswerQualityRequest {
  query: string;
  context: string;
  answer: string;
}

export interface AnswerQualityResponse {
  groundedness?: number;
  relevance?: number;
  helpfulness?: number;
  reasoning?: string;
  recommendation?: string;
  [key: string]: unknown;
}

export interface FeedbackRequest {
  sessionId?: string;
  query?: string;
  feedbackType: 'THUMBS_UP' | 'THUMBS_DOWN' | 'RATING';
  rating?: number;
  comment?: string;
  retrievedDocIds?: string[];
  selectedDocIds?: string[];
}

function defaultDateRange(days = 30): { startDate: string; endDate: string } {
  const end = new Date();
  const start = new Date(end.getTime() - days * 24 * 60 * 60 * 1000);
  return { startDate: start.toISOString(), endDate: end.toISOString() };
}

export const evaluationApi = {
  getReport: (params?: { startDate?: string; endDate?: string }) => {
    const range = { ...defaultDateRange(), ...params };
    return apiClient.get<EvaluationReport>('/evaluation/report', { params: range });
  },

  getAggregated: (params?: { startDate?: string; endDate?: string }) => {
    const range = { ...defaultDateRange(), ...params };
    return apiClient.get<AggregatedMetrics>('/evaluation/metrics/aggregated', { params: range });
  },

  getHistory: (params?: { page?: number; size?: number }) =>
    apiClient.get<EvaluationHistoryItem[]>('/evaluation/history', {
      params: { page: params?.page ?? 0, size: params?.size ?? 20 },
    }),

  evaluate: (data: EvaluateRequest) =>
    apiClient.post('/evaluation/evaluate', data),

  answerQuality: (data: AnswerQualityRequest) =>
    apiClient.post<AnswerQualityResponse>('/evaluation/answer-quality', data),

  submitFeedback: (data: FeedbackRequest) =>
    apiClient.post('/evaluation/feedback', data),

  getFeedbackStats: (params?: { startDate?: string; endDate?: string }) => {
    const range = { ...defaultDateRange(), ...params };
    return apiClient.get<FeedbackStats>('/evaluation/feedback/stats', { params: range });
  },

  getFeedbackHistory: (params?: { page?: number; size?: number }) =>
    apiClient.get<FeedbackItem[]>('/evaluation/feedback/history', {
      params: { page: params?.page ?? 0, size: params?.size ?? 20 },
    }),
};
