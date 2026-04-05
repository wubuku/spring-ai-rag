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

export const alertsApi = {
  listActive: () =>
    apiClient.get<Alert[]>('/alerts/active'),

  listHistory: (params?: { limit?: number }) =>
    apiClient.get<Alert[]>('/alerts/history', { params }),

  fire: (data: {
    alertType: string;
    alertName: string;
    message: string;
    severity?: string;
    metrics?: Record<string, unknown>;
  }) =>
    apiClient.post<{ alertId: number; message: string }>('/alerts/fire', data),

  resolve: (id: number, resolution: string) =>
    apiClient.post(`/alerts/${id}/resolve`, { resolution }),

  silence: (alertKey: string, durationMinutes: number) =>
    apiClient.post('/alerts/silence', { alertKey, durationMinutes }),
};
