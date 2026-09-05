import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Alerts } from './Alerts';
import { alertsApi } from '../api/alerts';

vi.mock('../api/alerts', () => ({
  alertsApi: {
    listActive: vi.fn(),
    listNotificationDeliveries: vi.fn(),
    retryNotificationDelivery: vi.fn(),
    listSloConfigs: vi.fn(),
    deleteSloConfig: vi.fn(),
    listSilenceSchedules: vi.fn(),
    createSilenceSchedule: vi.fn(),
    deleteSilenceSchedule: vi.fn(),
    fire: vi.fn(),
    resolve: vi.fn(),
    silence: vi.fn(),
    listSlo: vi.fn(),
    listHistory: vi.fn(),
    listSloConfigs: vi.fn(),
    createSloConfig: vi.fn(),
  },
}));

function renderAlerts(path = '/alerts') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Alerts />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const FAILED_DELIVERY_PAGE = {
  notificationsEnabled: true,
  durableDeliveryEnabled: true,
  configuredProviders: ['DINGTALK'],
  items: [{
    id: 'delivery-1',
    alertId: 42,
    notificationVersion: 1,
    provider: 'DINGTALK',
    status: 'FAILED',
    attemptCount: 8,
    attemptBudget: 8,
    manualRetryCount: 0,
    nextAttemptAt: '2026-08-28T08:00:00Z',
    lastErrorCode: 'TRANSIENT_PROVIDER_5XX',
    createdAt: '2026-08-28T08:00:00Z',
    updatedAt: '2026-08-28T08:01:00Z',
  }],
  limit: 50,
  hasMore: false,
};

describe('Alerts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(alertsApi.listActive).mockResolvedValue({ data: [] } as never);
    vi.mocked(alertsApi.listSloConfigs).mockResolvedValue({ data: [] } as never);
    vi.mocked(alertsApi.listSilenceSchedules).mockResolvedValue({ data: [] } as never);
    vi.mocked(alertsApi.listNotificationDeliveries).mockResolvedValue({
      data: FAILED_DELIVERY_PAGE,
    } as never);
  });

  it('renders page title', async () => {
    renderAlerts();
    const h1 = document.querySelector('h1');
    expect(h1).toBeInTheDocument();
    expect(h1).toHaveTextContent('alerts.title');
  });

  it('shows loading state when pending', async () => {
    vi.mocked(alertsApi.listActive).mockImplementation(
      () => new Promise(() => {}) as never);
    renderAlerts();
    expect(await screen.findByText('common.loading')).toBeInTheDocument();
  });

  it('shows empty state when no alerts', async () => {
    renderAlerts();
    expect(await screen.findByText('alerts.noActiveAlerts')).toBeInTheDocument();
  });

  it('shows alert items when alerts exist', async () => {
    vi.mocked(alertsApi.listActive).mockResolvedValue({
      data: [{
        id: 1,
        alertType: 'API_PRINCIPAL_EXPIRY',
        alertName: 'High Latency',
        severity: 'WARNING',
        message: 'Average latency exceeded 1s',
        conditionState: 'WARNING',
        firedAt: '2024-01-01T12:00:00Z',
        metrics: {
          principalId: 'rag_k_test',
          expiresAt: '2026-09-01T12:00:00+08:00[Asia/Shanghai]',
        },
      }],
    } as never);

    renderAlerts();
    expect(await screen.findByText('High Latency')).toBeInTheDocument();
    expect(screen.getByText('WARNING')).toBeInTheDocument();
    expect(screen.getByText('Average latency exceeded 1s')).toBeInTheDocument();
    expect(screen.getByText('alerts.phase: WARNING')).toBeInTheDocument();
    expect(screen.getByText('alerts.principal: rag_k_test')).toBeInTheDocument();
  });

  it('uses a stable fallback for an invalid firedAt value', async () => {
    vi.mocked(alertsApi.listActive).mockResolvedValue({
      data: [{
        id: 2,
        alertType: 'THRESHOLD_HIGH',
        alertName: 'Invalid time fixture',
        severity: 'CRITICAL',
        message: 'Fixture',
        firedAt: 'not-a-date',
      }],
    } as never);

    renderAlerts();
    expect(
      await screen.findByText('alerts.triggeredAt: alerts.timeUnavailable'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Invalid Date/)).not.toBeInTheDocument();
  });

  it('shows durable delivery mode, filters, receipts and retry action', async () => {
    renderAlerts(
      '/alerts?tab=notification-deliveries&status=FAILED&provider=DINGTALK',
    );

    await waitFor(() => {
      expect(alertsApi.listNotificationDeliveries).toHaveBeenCalled();
    });
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(screen.getByRole('combobox', {
      name: 'alerts.deliveryStatusFilter',
    })).toHaveValue('FAILED');
    expect(screen.getByRole('combobox', {
      name: 'alerts.deliveryProviderFilter',
    })).toHaveValue('DINGTALK');
    expect(screen.getByText('TRANSIENT_PROVIDER_5XX')).toBeInTheDocument();

    await userEvent.click(
      screen.getByRole('button', { name: 'alerts.retryDelivery' }),
    );
    expect(alertsApi.retryNotificationDelivery).toHaveBeenCalledWith('delivery-1');
    expect(screen.queryByText(/payload/i)).not.toBeInTheDocument();
  });

  it('distinguishes direct compatibility mode from an empty durable ledger', async () => {
    vi.mocked(alertsApi.listNotificationDeliveries).mockResolvedValue({
      data: {
        notificationsEnabled: true,
        durableDeliveryEnabled: false,
        configuredProviders: [],
        items: [],
        limit: 50,
        hasMore: false,
      },
    } as never);

    renderAlerts('/alerts?tab=notification-deliveries');
    expect(
      await screen.findByText('alerts.directDeliveryMode'),
    ).toBeInTheDocument();
  });

  it('switches to the SLO configs tab and lists configurations', async () => {
    const user = userEvent.setup();
    vi.mocked(alertsApi.listSloConfigs).mockResolvedValue({
      data: [{
        id: 7,
        sloName: 'latency-p99',
        sloType: 'LATENCY',
        targetValue: 800,
        unit: 'ms',
        enabled: true,
      }],
    } as never);

    renderAlerts();
    await user.click(screen.getByRole('button', { name: 'alerts.sloConfig' }));

    expect(await screen.findByText('latency-p99')).toBeInTheDocument();
    expect(screen.getByText('alerts.alertType')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '+ alerts.sloConfig' }),
    ).toBeInTheDocument();
  });

  it('deletes an SLO configuration from the table', async () => {
    const user = userEvent.setup();
    vi.mocked(alertsApi.listSloConfigs).mockResolvedValue({
      data: [{
        id: 7,
        sloName: 'latency-p99',
        sloType: 'LATENCY',
        targetValue: 800,
        unit: 'ms',
        enabled: true,
      }],
    } as never);
    vi.mocked(alertsApi.deleteSloConfig).mockResolvedValue({} as never);

    renderAlerts();
    await user.click(screen.getByRole('button', { name: 'alerts.sloConfig' }));
    await user.click(await screen.findByText('latency-p99'));

    const deleteButton = screen.getByRole('button', {
      name: 'alerts.deleteSilence',
    });
    await user.click(deleteButton);

    expect(alertsApi.deleteSloConfig).toHaveBeenCalledWith('latency-p99');
  });

  it('lists silence schedules on their tab', async () => {
    vi.mocked(alertsApi.listSilenceSchedules).mockResolvedValue({
      data: [{
        id: 3,
        name: 'weekend-maintenance',
        alertKey: 'k',
        silenceType: 'WINDOW',
        startTime: '2026-09-05T00:00:00Z',
        endTime: '2026-09-06T00:00:00Z',
        enabled: true,
      }],
    } as never);

    renderAlerts('/alerts?tab=silence-schedules');
    expect(
      await screen.findByText('weekend-maintenance'),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '+ alerts.createSilence' }),
    ).toBeInTheDocument();
  });
});

describe('Alerts create form flows', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(alertsApi.listActive).mockResolvedValue({ data: [] } as never);
    vi.mocked(alertsApi.listSloConfigs).mockResolvedValue({ data: [] } as never);
    vi.mocked(alertsApi.listSilenceSchedules).mockResolvedValue({ data: [] } as never);
    vi.mocked(alertsApi.listNotificationDeliveries).mockResolvedValue({
      data: {
        notificationsEnabled: false,
        durableDeliveryEnabled: false,
        configuredProviders: [],
        items: [],
        limit: 50,
        hasMore: false,
      },
    } as never);
  });

  it('submits the SLO create form with parsed target value', async () => {
    const user = userEvent.setup();
    vi.mocked(alertsApi.createSloConfig).mockResolvedValue({} as never);

    renderAlerts('/alerts?tab=slo-configs');
    await user.click(
      await screen.findByRole('button', { name: '+ alerts.sloConfig' }),
    );

    const sloName = screen.getByPlaceholderText('alerts.sloConfigNamePlaceholder');
    await user.type(sloName, 'latency-p99');
    const target = screen.getByRole('spinbutton');
    await user.type(target, '250');
    const selects = screen.getAllByRole('combobox');
    await user.selectOptions(selects[0], 'AVAILABILITY');

    await user.click(
      screen.getByRole('button', { name: 'common.create' }),
    );

    await waitFor(() => {
      expect(alertsApi.createSloConfig).toHaveBeenCalledWith({
        sloName: 'latency-p99',
        sloType: 'AVAILABILITY',
        targetValue: 250,
        unit: 'ms',
        enabled: true,
      });
    });
    // 成功后表单收起（onHideForm）
    await waitFor(() => {
      expect(
        screen.queryByRole('button', { name: 'common.create' }),
      ).not.toBeInTheDocument();
    });
  });

  it('submits the silence schedule form with start and end times', async () => {
    const user = userEvent.setup();
    vi.mocked(alertsApi.createSilenceSchedule).mockResolvedValue({} as never);

    renderAlerts('/alerts?tab=silence-schedules');
    await user.click(
      await screen.findByRole('button', { name: '+ alerts.createSilence' }),
    );

    const name = screen.getByPlaceholderText('alerts.silenceNamePlaceholder');
    await user.type(name, 'weekend-window');
    const times = document.querySelectorAll('input[type="datetime-local"]');
    expect(times.length).toBe(2);
    await user.type(times[0], '2026-09-06T22:00');
    await user.type(times[1], '2026-09-07T06:00');

    await user.click(screen.getByRole('button', { name: 'common.create' }));

    await waitFor(() => {
      expect(alertsApi.createSilenceSchedule).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'weekend-window',
          silenceType: 'ONE_TIME',
        }),
      );
    });
  });
});
