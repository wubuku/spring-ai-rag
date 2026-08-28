import { apiClient } from './client';

export interface Alert {
  id: number;
  alertType: string;
  alertName: string;
  message: string;
  severity: 'INFO' | 'WARNING' | 'ERROR' | 'CRITICAL';
  status: 'ACTIVE' | 'RESOLVED' | 'SILENCED';
  conditionState?: 'WARNING' | 'CRITICAL' | 'EXPIRED';
  metrics?: Record<string, unknown>;
  firedAt: string;
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

export type NotificationDeliveryStatus =
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'RETRY_WAIT'
  | 'DELIVERED'
  | 'FAILED'
  | 'SUPERSEDED';

export type NotificationProvider = 'EMAIL' | 'DINGTALK';

export interface AlertNotificationDelivery {
  id: string;
  alertId: number;
  notificationVersion: number;
  provider: NotificationProvider;
  status: NotificationDeliveryStatus;
  attemptCount: number;
  attemptBudget: number;
  manualRetryCount: number;
  nextAttemptAt: string;
  lastErrorCode?: string;
  lastHttpStatus?: number;
  lastAttemptAt?: string;
  deliveredAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface AlertNotificationDeliveryPage {
  notificationsEnabled: boolean;
  durableDeliveryEnabled: boolean;
  configuredProviders: NotificationProvider[];
  items: AlertNotificationDelivery[];
  limit: number;
  hasMore: boolean;
  nextCursor?: string;
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

  listNotificationDeliveries: (params?: {
    status?: NotificationDeliveryStatus;
    provider?: NotificationProvider;
    alertId?: number;
    limit?: number;
    cursor?: string;
  }) => apiClient.get<AlertNotificationDeliveryPage>(
    '/alerts/notification-deliveries',
    { params },
  ),

  retryNotificationDelivery: (deliveryId: string) =>
    apiClient.post<AlertNotificationDelivery>(
      `/alerts/notification-deliveries/${deliveryId}/retry`,
    ),
};
