import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
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

describe('Alerts deliveries filters and loading state', () => {
  function renderAlertsWithProbe(path = '/alerts') {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const LocationProbe = () => {
      const location = useLocation();
      return <output data-testid="location-search">{location.search}</output>;
    };
    return render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Alerts />
          <LocationProbe />
        </MemoryRouter>
      </QueryClientProvider>,
    );
  }

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(alertsApi.listActive).mockResolvedValue({ data: [] } as never);
    vi.mocked(alertsApi.listSloConfigs).mockResolvedValue({ data: [] } as never);
    vi.mocked(alertsApi.listSilenceSchedules).mockResolvedValue({ data: [] } as never);
    vi.mocked(alertsApi.listNotificationDeliveries).mockResolvedValue({
      data: {
        notificationsEnabled: true,
        durableDeliveryEnabled: true,
        configuredProviders: [],
        items: [],
        limit: 50,
        hasMore: false,
      },
    } as never);
  });

  it('shows the loading state while the deliveries query is pending', async () => {
    const user = userEvent.setup();
    vi.mocked(alertsApi.listNotificationDeliveries).mockReturnValue(
      new Promise(() => {}) as never,
    );
    renderAlertsWithProbe('/alerts');

    await user.click(screen.getByRole('button', { name: 'alerts.deliveries' }));

    expect(await screen.findByText('common.loading')).toBeInTheDocument();
  });

  it('sets the delivery status filter through its select', async () => {
    const user = userEvent.setup();
    renderAlertsWithProbe('/alerts');

    await user.click(screen.getByRole('button', { name: 'alerts.deliveries' }));
    const select = await screen.findByLabelText('alerts.deliveryStatusFilter');
    await user.selectOptions(select, 'FAILED');
    expect(screen.getByTestId('location-search')).toHaveTextContent('status=FAILED');
  });

  it('clears the delivery status filter via the empty option', async () => {
    const user = userEvent.setup();
    // 直接以 status 参数进入；清空走 else 分支删除 URL 参数。
    renderAlertsWithProbe('/alerts?tab=notification-deliveries&status=FAILED');

    const select = await screen.findByLabelText('alerts.deliveryStatusFilter');
    await user.selectOptions(select, '');
    await waitFor(() =>
      expect(
        screen.getByTestId('location-search').textContent,
      ).not.toContain('status='),
    );
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

describe('Alerts tab navigation, delivery modes and remaining form fields', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(alertsApi.listActive).mockResolvedValue({ data: [] } as never);
    vi.mocked(alertsApi.listSloConfigs).mockResolvedValue({ data: [] } as never);
    vi.mocked(alertsApi.listSilenceSchedules).mockResolvedValue({ data: [] } as never);
    vi.mocked(alertsApi.listNotificationDeliveries).mockResolvedValue({
      data: FAILED_DELIVERY_PAGE,
    } as never);
  });

  it('switches between all four tabs via the tab buttons', async () => {
    const user = userEvent.setup();
    renderAlerts();

    await user.click(screen.getByRole('button', { name: 'alerts.silencePlans' }));
    expect(
      await screen.findByRole('button', { name: '+ alerts.createSilence' }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'alerts.deliveries' }));
    expect(
      await screen.findByRole('combobox', {
        name: 'alerts.deliveryStatusFilter',
      }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'alerts.active' }));
    expect(
      await screen.findByText('alerts.noActiveAlerts'),
    ).toBeInTheDocument();
  });

  it('shows the deliveries loading state while the ledger query is pending', async () => {
    const user = userEvent.setup();
    vi.mocked(alertsApi.listNotificationDeliveries).mockImplementation(
      () => new Promise(() => {}) as never,
    );

    renderAlerts();
    await user.click(screen.getByRole('button', { name: 'alerts.deliveries' }));

    expect(await screen.findByText('common.loading')).toBeInTheDocument();
  });

  it('shows the notifications-disabled notice for the deliveries ledger', async () => {
    vi.mocked(alertsApi.listNotificationDeliveries).mockResolvedValue({
      data: { ...FAILED_DELIVERY_PAGE, notificationsEnabled: false },
    } as never);

    renderAlerts('/alerts?tab=notification-deliveries');

    expect(
      await screen.findByText('alerts.notificationsDisabled'),
    ).toBeInTheDocument();
  });

  it('shows the no-providers notice when none are configured', async () => {
    vi.mocked(alertsApi.listNotificationDeliveries).mockResolvedValue({
      data: { ...FAILED_DELIVERY_PAGE, configuredProviders: [] },
    } as never);

    renderAlerts('/alerts?tab=notification-deliveries');

    expect(
      await screen.findByText('alerts.noDeliveryProviders'),
    ).toBeInTheDocument();
  });

  it('filters deliveries through the status and provider selects', async () => {
    const user = userEvent.setup();
    renderAlerts('/alerts?tab=notification-deliveries');
    // 回执行不渲染 id 文本，用重试按钮确认行已加载。
    await screen.findByRole('button', { name: 'alerts.retryDelivery' });

    await user.selectOptions(
      screen.getByRole('combobox', { name: 'alerts.deliveryStatusFilter' }),
      'FAILED',
    );
    await waitFor(() => {
      expect(alertsApi.listNotificationDeliveries).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'FAILED' }),
      );
    });

    await user.selectOptions(
      screen.getByRole('combobox', { name: 'alerts.deliveryProviderFilter' }),
      'EMAIL',
    );
    await waitFor(() => {
      expect(alertsApi.listNotificationDeliveries).toHaveBeenCalledWith(
        expect.objectContaining({ provider: 'EMAIL' }),
      );
    });
  });

  it('renders a dash for deliveries without a next attempt time', async () => {
    vi.mocked(alertsApi.listNotificationDeliveries).mockResolvedValue({
      data: {
        ...FAILED_DELIVERY_PAGE,
        items: [{ ...FAILED_DELIVERY_PAGE.items[0], nextAttemptAt: undefined }],
      },
    } as never);

    renderAlerts('/alerts?tab=notification-deliveries');

    await screen.findByRole('button', { name: 'alerts.retryDelivery' });
    expect(screen.getAllByText('-').length).toBeGreaterThanOrEqual(1);
  });

  it('edits the SLO target value and unit before submitting', async () => {
    const user = userEvent.setup();
    vi.mocked(alertsApi.createSloConfig).mockResolvedValue({} as never);

    renderAlerts('/alerts?tab=slo-configs');
    await user.click(
      await screen.findByRole('button', { name: '+ alerts.sloConfig' }),
    );

    await user.type(
      screen.getByPlaceholderText('alerts.sloConfigNamePlaceholder'),
      'availability-slo',
    );
    const target = screen.getByRole('spinbutton');
    await user.type(target, '99.9');
    const selects = screen.getAllByRole('combobox');
    await user.selectOptions(selects[0], 'AVAILABILITY');
    await user.selectOptions(selects[1], '%');

    await user.click(screen.getByRole('button', { name: 'common.create' }));

    await waitFor(() => {
      expect(alertsApi.createSloConfig).toHaveBeenCalledWith({
        sloName: 'availability-slo',
        sloType: 'AVAILABILITY',
        targetValue: 99.9,
        unit: '%',
        enabled: true,
      });
    });
  });

  it('edits every silence form field and deletes a schedule', async () => {
    const user = userEvent.setup();
    vi.mocked(alertsApi.createSilenceSchedule).mockResolvedValue({} as never);
    vi.mocked(alertsApi.deleteSilenceSchedule).mockResolvedValue({} as never);
    vi.mocked(alertsApi.listSilenceSchedules).mockResolvedValue({
      data: [{
        name: 'weekend-window',
        alertKey: 'API_PRINCIPAL_EXPIRY',
        silenceType: 'ONE_TIME',
        startTime: '2026-09-06T22:00',
        endTime: '2026-09-07T06:00',
        description: '维护窗口',
        enabled: true,
      }],
    } as never);

    renderAlerts('/alerts?tab=silence-schedules');
    await user.click(
      await screen.findByRole('button', { name: '+ alerts.createSilence' }),
    );

    await user.type(
      screen.getByPlaceholderText('alerts.silenceNamePlaceholder'),
      'recurring-silence',
    );
    await user.type(
      screen.getByPlaceholderText('alerts.silenceDescriptionPlaceholder'),
      'API_PRINCIPAL_EXPIRY',
    );
    const silenceTypeSelect = screen.getAllByRole('combobox').at(-1)!;
    await user.selectOptions(silenceTypeSelect, 'RECURRING');
    await user.type(
      screen.getByPlaceholderText('collections.descriptionPlaceholder'),
      '每日静默',
    );
    const times = document.querySelectorAll('input[type="datetime-local"]');
    await user.type(times[0], '2026-09-06T22:00');
    await user.type(times[1], '2026-09-07T06:00');

    await user.click(screen.getByRole('button', { name: 'common.create' }));
    await waitFor(() => {
      expect(alertsApi.createSilenceSchedule).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'recurring-silence',
          silenceType: 'RECURRING',
          description: '每日静默',
        }),
      );
    });

    const deleteButton = await screen.findByRole('button', {
      name: 'alerts.deleteSilence',
    });
    await user.click(deleteButton);
    await waitFor(() => {
      expect(alertsApi.deleteSilenceSchedule).toHaveBeenCalledWith(
        'weekend-window',
      );
    });
  });
});
