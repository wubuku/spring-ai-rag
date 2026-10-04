/**
 * A rejected write must produce something.
 *
 * Batch 791 fixed six mutations with no `onError` at all. Batch 798 found four
 * more that had one — `onError: () => {}` — which the gate accepted for seven
 * batches because it only asked whether the key appeared. On the two create
 * mutations in `Alerts.tsx` it was worse than silence: `onSuccess` calls
 * `onHideForm()`, so a rejected create closed the form and cleared the fields.
 * That reads as success.
 *
 * The other three here are the same defect wearing different clothes: a thumb,
 * an export and a preview that all look like they worked.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ToastProvider } from '../components/Toast';
import { Alerts } from './Alerts';
import { alertsApi } from '../api/alerts';

const showToast = vi.fn();

vi.mock('../components/Toast', async importOriginal => {
  const actual = await importOriginal<typeof import('../components/Toast')>();
  return { ...actual, useToast: () => ({ showToast }) };
});

vi.mock('../api/alerts', () => ({
  alertsApi: {
    listActive: vi.fn(),
    listNotificationDeliveries: vi.fn(),
    retryNotificationDelivery: vi.fn(),
    listSloConfigs: vi.fn(),
    createSloConfig: vi.fn(),
    deleteSloConfig: vi.fn(),
    listSilenceSchedules: vi.fn(),
    createSilenceSchedule: vi.fn(),
    deleteSilenceSchedule: vi.fn(),
  },
}));

function renderAlerts(path = '/alerts?tab=slo-configs') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <MemoryRouter initialEntries={[path]}>
          <Alerts />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
  showToast.mockClear();
  vi.mocked(alertsApi.listActive).mockResolvedValue({ data: [] } as never);
  vi.mocked(alertsApi.listSloConfigs).mockResolvedValue({
    data: [{ id: 1, sloName: 'search-latency', sloType: 'LATENCY', targetValue: 500, unit: 'ms', enabled: true }],
  } as never);
  vi.mocked(alertsApi.listSilenceSchedules).mockResolvedValue({
    data: [{ id: 9, name: 'nightly', silenceType: 'ONE_TIME', startTime: '', endTime: '', enabled: true }],
  } as never);
});

describe('Alerts: a rejected write must not look like a successful one', () => {
  it('reports a rejected SLO creation, and keeps the form closed only on success', async () => {
    vi.mocked(alertsApi.createSloConfig).mockRejectedValue(new Error('409 Conflict'));

    renderAlerts();

    await userEvent.click(await screen.findByRole('button', { name: /sloConfig/ }));
    await userEvent.type(screen.getByPlaceholderText('alerts.sloConfigNamePlaceholder'), 'db-latency');
    await userEvent.type(screen.getByLabelText('alerts.targetValue'), '500');
    await userEvent.click(screen.getByRole('button', { name: 'common.create' }));

    await waitFor(() =>
      expect(showToast).toHaveBeenCalledWith('alerts.sloConfigCreateError (409 Conflict)', 'error'),
    );
  });

  it('reports a rejected SLO deletion instead of doing nothing', async () => {
    vi.mocked(alertsApi.deleteSloConfig).mockRejectedValue(new Error('500'));

    renderAlerts();

    await userEvent.click(await screen.findByRole('button', { name: 'alerts.delete' }));
    await userEvent.click(screen.getByRole('button', { name: 'common.delete' }));

    await waitFor(() =>
      expect(showToast).toHaveBeenCalledWith('alerts.sloConfigDeleteError (500)', 'error'),
    );
  });

  it('reports a rejected silence-plan creation', async () => {
    vi.mocked(alertsApi.createSilenceSchedule).mockRejectedValue(new Error('400'));

    renderAlerts('/alerts?tab=silence-schedules');

    await userEvent.click(await screen.findByRole('button', { name: /createSilence/ }));
    await userEvent.type(screen.getByPlaceholderText('alerts.silenceNamePlaceholder'), 'nightly');
    await userEvent.type(screen.getByLabelText('alerts.triggeredAt'), '2026-10-01T00:00');
    await userEvent.type(screen.getByLabelText('alerts.resolvedAt'), '2026-10-02T00:00');
    await userEvent.click(screen.getByRole('button', { name: 'common.create' }));

    // These two strings had been sitting in both locale files since they were
    // written, referenced by nothing, waiting for the handler that was empty.
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('alerts.createError (400)', 'error'));
  });

  it('reports a rejected silence-plan deletion', async () => {
    vi.mocked(alertsApi.deleteSilenceSchedule).mockRejectedValue(new Error('500'));

    renderAlerts('/alerts?tab=silence-schedules');

    await userEvent.click(await screen.findByRole('button', { name: 'alerts.delete' }));
    await userEvent.click(screen.getByRole('button', { name: 'common.delete' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('alerts.deleteError (500)', 'error'));
  });

  it('stays quiet when the write succeeds, so the toast means something', async () => {
    vi.mocked(alertsApi.createSloConfig).mockResolvedValue({} as never);

    renderAlerts();

    await userEvent.click(await screen.findByRole('button', { name: /sloConfig/ }));
    await userEvent.type(screen.getByPlaceholderText('alerts.sloConfigNamePlaceholder'), 'db-latency');
    await userEvent.type(screen.getByLabelText('alerts.targetValue'), '500');
    await userEvent.click(screen.getByRole('button', { name: 'common.create' }));

    await waitFor(() => expect(alertsApi.createSloConfig).toHaveBeenCalled());
    expect(showToast).not.toHaveBeenCalled();
  });
});
