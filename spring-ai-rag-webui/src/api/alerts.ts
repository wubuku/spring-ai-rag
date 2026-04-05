import { apiClient } from './client';

export interface Alert {
  id: number;
  alertType: string;
  alertName: string;
  message: string;
  severity: 'INFO' | 'WARNING' | 'ERROR' | 'CRITICAL';
  status: 'ACTIVE' | 'RESOLVED' | 'SILENCED';
  triggeredAt: string;
  resolvedAt?: string;
}

export interface SloConfig {
  id: number;
  sloName: string;
  sloType: string;
  targetValue: number;
  unit: string;
  description?: string;
  enabled: boolean;
  metadata?: Record<string, unknown>;
  createdAt: string;
  updatedAt?: string;
}

export interface SilenceSchedule {
  id: number;
  name: string;
  alertKey?: string;
  silenceType: 'ONE_TIME' | 'RECURRING';
  startTime: string;
  endTime: string;
  description?: string;
  enabled: boolean;
  metadata?: Record<string, unknown>;
  createdAt: string;
  updatedAt?: string;
}

export const alertsApi = {
  // Active alerts
  listActive: () => apiClient.get<Alert[]>('/alerts/active'),

  listHistory: (params?: { limit?: number }) =>
    apiClient.get<Alert[]>('/alerts/history', { params }),

  fire: (data: {
    alertType: string;
    alertName: string;
    message: string;
    severity?: string;
    metrics?: Record<string, unknown>;
  }) => apiClient.post<{ alertId: number; message: string }>('/alerts/fire', data),

  resolve: (id: number, resolution: string) =>
    apiClient.post(`/alerts/${id}/resolve`, { resolution }),

  silence: (alertKey: string, durationMinutes: number) =>
    apiClient.post('/alerts/silence', { alertKey, durationMinutes }),

  // SLO Config CRUD
  listSloConfigs: () => apiClient.get<SloConfig[]>('/alerts/slos/configs'),

  createSloConfig: (data: {
    sloName: string;
    sloType: string;
    targetValue: number;
    unit: string;
    description?: string;
    enabled?: boolean;
    metadata?: Record<string, unknown>;
  }) => apiClient.post<SloConfig>('/alerts/slos', data),

  getSloConfig: (sloName: string) =>
    apiClient.get<SloConfig>(`/alerts/slos/configs/${sloName}`),

  updateSloConfig: (sloName: string, data: {
    sloType: string;
    targetValue: number;
    unit: string;
    description?: string;
    enabled?: boolean;
    metadata?: Record<string, unknown>;
  }) => apiClient.put<SloConfig>(`/alerts/slos/configs/${sloName}`, data),

  deleteSloConfig: (sloName: string) =>
    apiClient.delete(`/alerts/slos/configs/${sloName}`),

  // Silence Schedule CRUD
  listSilenceSchedules: () => apiClient.get<SilenceSchedule[]>('/alerts/silence-schedules'),

  createSilenceSchedule: (data: {
    name: string;
    alertKey?: string;
    silenceType: string;
    startTime: string;
    endTime: string;
    description?: string;
    enabled?: boolean;
    metadata?: Record<string, unknown>;
  }) => apiClient.post<SilenceSchedule>('/alerts/silence-schedules', data),

  getSilenceSchedule: (name: string) =>
    apiClient.get<SilenceSchedule>(`/alerts/silence-schedules/${name}`),

  updateSilenceSchedule: (name: string, data: {
    alertKey?: string;
    silenceType: string;
    startTime: string;
    endTime: string;
    description?: string;
    enabled?: boolean;
    metadata?: Record<string, unknown>;
  }) => apiClient.put<SilenceSchedule>(`/alerts/silence-schedules/${name}`, data),

  deleteSilenceSchedule: (name: string) =>
    apiClient.delete(`/alerts/silence-schedules/${name}`),
};
