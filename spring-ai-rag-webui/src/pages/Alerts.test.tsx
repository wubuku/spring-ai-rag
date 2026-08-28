import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Alerts } from './Alerts';

const mockUseQuery = vi.fn();
const mockMutate = vi.fn();
const mockInvalidateQueries = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
  useMutation: () => ({
    mutate: mockMutate,
    isPending: false,
    isError: false,
  }),
  useQueryClient: () => ({
    invalidateQueries: mockInvalidateQueries,
  }),
}));

describe('Alerts', () => {
  const renderAlerts = (path = '/alerts') => render(
    <MemoryRouter initialEntries={[path]}>
      <Alerts />
    </MemoryRouter>,
  );

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders page title', () => {
    mockUseQuery.mockReturnValue({
      data: { data: [] },
      isPending: false,
    });

    renderAlerts();
    const h1 = document.querySelector('h1');
    expect(h1).toBeInTheDocument();
    expect(h1).toHaveTextContent('alerts.title');
  });

  it('shows loading state when pending', () => {
    mockUseQuery.mockReturnValue({
      data: undefined,
      isPending: true,
    });

    renderAlerts();
    expect(screen.getByText('common.loading')).toBeInTheDocument();
  });

  it('shows empty state when no alerts', () => {
    mockUseQuery.mockReturnValue({
      data: { data: [] },
      isPending: false,
    });

    renderAlerts();
    expect(screen.getByText('alerts.noActiveAlerts')).toBeInTheDocument();
  });

  it('shows alert items when alerts exist', () => {
    mockUseQuery.mockReturnValue({
      data: {
        data: [
          {
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
          },
        ],
      },
      isPending: false,
    });

    renderAlerts();
    expect(screen.getByText('High Latency')).toBeInTheDocument();
    expect(screen.getByText('WARNING')).toBeInTheDocument();
    expect(screen.getByText('Average latency exceeded 1s')).toBeInTheDocument();
    expect(screen.getByText('alerts.phase: WARNING')).toBeInTheDocument();
    expect(screen.getByText('alerts.principal: rag_k_test')).toBeInTheDocument();
  });

  it('uses a stable fallback for an invalid firedAt value', () => {
    mockUseQuery.mockReturnValue({
      data: {
        data: [{
          id: 2,
          alertType: 'THRESHOLD_HIGH',
          alertName: 'Invalid time fixture',
          severity: 'CRITICAL',
          message: 'Fixture',
          firedAt: 'not-a-date',
        }],
      },
      isPending: false,
    });

    renderAlerts();
    expect(screen.getByText('alerts.triggeredAt: alerts.timeUnavailable')).toBeInTheDocument();
    expect(screen.queryByText(/Invalid Date/)).not.toBeInTheDocument();
  });

  it('shows durable delivery mode, filters, receipts and retry action', () => {
    mockUseQuery.mockReturnValue({
      data: {
        data: {
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
        },
      },
      isPending: false,
      isError: false,
    });

    renderAlerts(
      '/alerts?tab=notification-deliveries&status=FAILED&provider=DINGTALK',
    );

    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(screen.getByRole('combobox', {
      name: 'alerts.deliveryStatusFilter',
    })).toHaveValue('FAILED');
    expect(screen.getByRole('combobox', {
      name: 'alerts.deliveryProviderFilter',
    })).toHaveValue('DINGTALK');
    expect(screen.getByText('TRANSIENT_PROVIDER_5XX')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', {
      name: 'alerts.retryDelivery',
    }));
    expect(mockMutate).toHaveBeenCalledWith('delivery-1');
    expect(screen.queryByText(/payload/i)).not.toBeInTheDocument();
  });

  it('distinguishes direct compatibility mode from an empty durable ledger', () => {
    mockUseQuery.mockReturnValue({
      data: {
        data: {
          notificationsEnabled: true,
          durableDeliveryEnabled: false,
          configuredProviders: ['DINGTALK'],
          items: [],
          limit: 50,
          hasMore: false,
        },
      },
      isPending: false,
      isError: false,
    });

    renderAlerts('/alerts?tab=notification-deliveries');

    expect(screen.getByRole('status')).toHaveTextContent(
      'alerts.directDeliveryMode',
    );
    expect(screen.getByText('alerts.noDeliveries')).toBeInTheDocument();
  });
});
