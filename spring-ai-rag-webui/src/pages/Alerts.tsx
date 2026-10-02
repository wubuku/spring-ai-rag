import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import {
  alertsApi,
  type Alert,
  type AlertNotificationDelivery,
  type NotificationDeliveryStatus,
  type NotificationProvider,
  type SloConfig,
  type SilenceSchedule,
} from '../api/alerts';
import { ImeSafeForm } from '../components/ImeSafeForm';
import { useToast } from '../components/Toast';
import styles from './Alerts.module.css';
import { EmptyState, PageHeader, QueryErrorBanner, Tabs } from '../components/ui';
import { ConfirmDialog } from '../components/Dialog/ConfirmDialog';

type Tab =
  | 'alerts'
  | 'slo-configs'
  | 'silence-schedules'
  | 'notification-deliveries';

export function Alerts() {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const tabParam = searchParams.get('tab');
  const tab: Tab =
    tabParam === 'slo-configs'
      || tabParam === 'silence-schedules'
      || tabParam === 'notification-deliveries'
      ? tabParam
      : 'alerts';
  const [showSloForm, setShowSloForm] = useState(false);
  const [showSilenceForm, setShowSilenceForm] = useState(false);

  return (
    <div>
      <PageHeader title={t('alerts.title')} />

      {/* The tab strip previously rendered four plain buttons with no tab
          semantics, so screen readers saw unrelated controls with no link to
          their content. <Tabs> owns the tablist and panel wiring; this page
          still owns the active id, which maps onto the ?tab= URL parameter. */}
      <Tabs
        idPrefix="alerts-tabs"
        ariaLabel={t('alerts.title')}
        activeId={tab}
        onChange={next => setSearchParams(next === 'alerts' ? {} : { tab: next })}
        items={[
          { id: 'alerts', label: t('alerts.active'), render: () => <AlertsTab /> },
          {
            id: 'slo-configs',
            label: t('alerts.sloConfig'),
            render: () => (
              <SloConfigsTab
                showForm={showSloForm}
                onShowForm={() => setShowSloForm(true)}
                onHideForm={() => setShowSloForm(false)}
              />
            ),
          },
          {
            id: 'silence-schedules',
            label: t('alerts.silencePlans'),
            render: () => (
              <SilenceSchedulesTab
                showForm={showSilenceForm}
                onShowForm={() => setShowSilenceForm(true)}
                onHideForm={() => setShowSilenceForm(false)}
              />
            ),
          },
          {
            id: 'notification-deliveries',
            label: t('alerts.deliveries'),
            render: () => <NotificationDeliveriesTab />,
          },
        ]}
      />
    </div>
  );
}

// ==================== Active Alerts Tab ====================

function AlertsTab() {
  const { t } = useTranslation();
  const { data, isPending, isError, refetch } = useQuery({
    queryKey: ['alerts'],
    queryFn: () => alertsApi.listActive(),
    refetchInterval: 30_000,
  });

  if (isPending) return <div className={styles.loading}>{t('common.loading')}</div>;

  // 过去这里对"请求失败"和"确实没有告警"一视同仁地说"暂无活跃告警"。
  // 在告警页面上，把连不上服务器讲成"一切正常"是最不该发生的一种误报：
  // 它不是让人少看到一个数字，而是让人以为没有东西在烧。
  if (isError) {
    return (
      <QueryErrorBanner onRetry={() => void refetch()} retryLabel={t('common.retry')}>
        {t('alerts.loadFailed')}
      </QueryErrorBanner>
    );
  }

  if (!data?.data?.length) {
    return <EmptyState>{t('alerts.noActiveAlerts')}</EmptyState>;
  }

  return (
    <div className={styles.list}>
      {data.data.map(alert => (
        <article
          key={alert.id}
          className={styles.item}
          data-severity={alert.severity}
          aria-label={`${alert.alertName} ${alert.severity}`}
        >
          <div className={styles.header}>
            <span className={styles.name}>{alert.alertName}</span>
            <span className={styles.severity} data-level={alert.severity}>{alert.severity}</span>
          </div>
          <div className={styles.meta}>
            <span>{t('alerts.alertType')}: {alert.alertType}</span>
            {alert.conditionState && (
              <span>{t('alerts.phase')}: {alert.conditionState}</span>
            )}
          </div>
          <div className={styles.message}>{alert.message}</div>
          {alert.alertType === 'API_PRINCIPAL_EXPIRY' && (
            <div className={styles.meta}>
              <span>{t('alerts.principal')}: {metricText(alert, 'principalId')}</span>
              <span>{t('alerts.expiresAt')}: {metricText(alert, 'expiresAt')}</span>
            </div>
          )}
          <div className={styles.time}>
            {t('alerts.triggeredAt')}: {formatAlertTime(alert.firedAt, t('alerts.timeUnavailable'))}
          </div>
        </article>
      ))}
    </div>
  );
}

function metricText(alert: Alert, key: string): string {
  const value = alert.metrics?.[key];
  return typeof value === 'string' || typeof value === 'number'
    ? String(value)
    : '-';
}

function formatAlertTime(value: string, fallback: string): string {
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp)
    ? new Date(timestamp).toLocaleString()
    : fallback;
}

// ==================== Notification Deliveries Tab ====================

const DELIVERY_STATUSES: NotificationDeliveryStatus[] = [
  'PENDING',
  'IN_PROGRESS',
  'RETRY_WAIT',
  'DELIVERED',
  'FAILED',
  'SUPERSEDED',
];

const DELIVERY_PROVIDERS: NotificationProvider[] = ['EMAIL', 'DINGTALK'];

function NotificationDeliveriesTab() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const statusValue = searchParams.get('status');
  const providerValue = searchParams.get('provider');
  const status = DELIVERY_STATUSES.includes(statusValue as NotificationDeliveryStatus)
    ? statusValue as NotificationDeliveryStatus
    : undefined;
  const provider = DELIVERY_PROVIDERS.includes(providerValue as NotificationProvider)
    ? providerValue as NotificationProvider
    : undefined;

  const query = useQuery({
    queryKey: ['alert-notification-deliveries', status, provider],
    queryFn: () => alertsApi.listNotificationDeliveries({
      status,
      provider,
      limit: 50,
    }),
    refetchInterval: 30_000,
  });
  const retryMutation = useMutation({
    mutationFn: (deliveryId: string) =>
      alertsApi.retryNotificationDelivery(deliveryId),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['alert-notification-deliveries'],
      });
    },
  });

  const setFilter = (
    name: 'status' | 'provider',
    value: string,
  ) => {
    const next = new URLSearchParams(searchParams);
    next.set('tab', 'notification-deliveries');
    if (value) next.set(name, value);
    else next.delete(name);
    setSearchParams(next);
  };

  if (query.isPending) {
    return (
      <div className={styles.loading} aria-live="polite">
        {t('common.loading')}
      </div>
    );
  }

  if (query.isError || !query.data?.data) {
    return (
      <QueryErrorBanner
        onRetry={() => void query.refetch()}
        retryLabel={t('common.retry')}
      >
        {t('alerts.deliveryLoadFailed')}
      </QueryErrorBanner>
    );
  }

  const envelope = query.data.data;
  const modeMessage = !envelope.notificationsEnabled
    ? t('alerts.notificationsDisabled')
    : !envelope.durableDeliveryEnabled
      ? t('alerts.directDeliveryMode')
      : envelope.configuredProviders.length === 0
        ? t('alerts.noDeliveryProviders')
        : null;

  return (
    <section aria-label={t('alerts.deliveries')}>
      {modeMessage && (
        <div className={styles.modeNotice} role="status">
          {modeMessage}
        </div>
      )}

      <div className={styles.filterBar}>
        <label>
          <span>{t('alerts.status')}</span>
          <select
            aria-label={t('alerts.deliveryStatusFilter')}
            className={styles.select}
            value={status ?? ''}
            onChange={event => setFilter('status', event.target.value)}
          >
            <option value="">{t('alerts.all')}</option>
            {DELIVERY_STATUSES.map(value => (
              <option key={value} value={value}>{value}</option>
            ))}
          </select>
        </label>
        <label>
          <span>{t('alerts.provider')}</span>
          <select
            aria-label={t('alerts.deliveryProviderFilter')}
            className={styles.select}
            value={provider ?? ''}
            onChange={event => setFilter('provider', event.target.value)}
          >
            <option value="">{t('alerts.all')}</option>
            {DELIVERY_PROVIDERS.map(value => (
              <option key={value} value={value}>{value}</option>
            ))}
          </select>
        </label>
      </div>

      {retryMutation.isError && (
        <div className={styles.errorState} role="alert">
          {t('alerts.deliveryRetryFailed')}
        </div>
      )}

      {envelope.items.length === 0 ? (
        <EmptyState>{t('alerts.noDeliveries')}</EmptyState>
      ) : (
        <div className={styles.deliveryTable} role="table">
          <div className={styles.deliveryHeader} role="row">
            <span role="columnheader">{t('alerts.alertId')}</span>
            <span role="columnheader">{t('alerts.provider')}</span>
            <span role="columnheader">{t('alerts.status')}</span>
            <span role="columnheader">{t('alerts.attempts')}</span>
            <span role="columnheader">{t('alerts.nextAttempt')}</span>
            <span role="columnheader">{t('alerts.lastError')}</span>
            <span role="columnheader">{t('alerts.updatedAt')}</span>
            <span role="columnheader">{t('alerts.actions')}</span>
          </div>
          {envelope.items.map(delivery => (
            <NotificationDeliveryRow
              key={delivery.id}
              delivery={delivery}
              retrying={
                retryMutation.isPending
                  && retryMutation.variables === delivery.id
              }
              onRetry={() => retryMutation.mutate(delivery.id)}
            />
          ))}
        </div>
      )}
    </section>
  );
}

function NotificationDeliveryRow({
  delivery,
  retrying,
  onRetry,
}: {
  delivery: AlertNotificationDelivery;
  retrying: boolean;
  onRetry: () => void;
}) {
  const { t } = useTranslation();
  return (
    <div
      className={styles.deliveryRow}
      role="row"
      aria-label={`${delivery.provider} ${delivery.status} ${delivery.alertId}`}
    >
      <span role="cell">{delivery.alertId}</span>
      <span role="cell">{delivery.provider}</span>
      <span role="cell">
        <span className={styles.deliveryStatus} data-status={delivery.status}>
          {delivery.status}
        </span>
      </span>
      <span role="cell">
        {delivery.attemptCount}/{delivery.attemptBudget}
      </span>
      <span role="cell">
        {formatOptionalTime(delivery.nextAttemptAt)}
      </span>
      <span role="cell">{delivery.lastErrorCode ?? '-'}</span>
      <span role="cell">{formatOptionalTime(delivery.updatedAt)}</span>
      <span role="cell">
        {delivery.status === 'FAILED' ? (
          <button
            type="button"
            className={styles.retryBtn}
            onClick={onRetry}
            disabled={retrying}
            aria-label={t('alerts.retryDelivery', { id: delivery.id })}
          >
            {retrying ? t('common.loading') : t('alerts.retry')}
          </button>
        ) : '-'}
      </span>
    </div>
  );
}

function formatOptionalTime(value?: string): string {
  if (!value) return '-';
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp)
    ? new Date(timestamp).toLocaleString()
    : '-';
}

// ==================== SLO Configs Tab ====================

interface SloFormData {
  sloName: string;
  sloType: string;
  targetValue: string;
  unit: string;
  description: string;
  enabled: boolean;
}

function SloConfigsTab({ showForm, onShowForm, onHideForm }: { showForm: boolean; onShowForm: () => void; onHideForm: () => void }) {
  const { t } = useTranslation();
  const { showToast } = useToast();
  // One click used to delete the threshold outright. Documents and ReembedAll
  // have asked for confirmation since Batch 770; these two lists had never been
  // brought in line, and the tests pinned the unsafe behaviour by clicking the
  // button once. Batch 812.
  const [pendingSloDelete, setPendingSloDelete] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const { data, isPending, isError, refetch } = useQuery({
    queryKey: ['slo-configs'],
    queryFn: () => alertsApi.listSloConfigs(),
  });
  const deleteMutation = useMutation({
    mutationFn: (sloName: string) => alertsApi.deleteSloConfig(sloName),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['slo-configs'] });
    },
    // 这四处曾经是 `onError: () => {}`。而 onSuccess 会 onHideForm()、清空表单——
    // 也就是说创建失败时表单自己关掉了、字段自己清空了，看起来就像保存成功了，
    // 用户唯一能观察到的现象只是"新 SLO 不在列表里"。
    onError: () => showToast(t('alerts.sloConfigDeleteError'), 'error'),
  });
  const [form, setForm] = useState<SloFormData>({
    sloName: '', sloType: 'LATENCY', targetValue: '', unit: 'ms', description: '', enabled: true,
  });
  const createMutation = useMutation({
    mutationFn: (data: Parameters<typeof alertsApi.createSloConfig>[0]) =>
      alertsApi.createSloConfig(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['slo-configs'] });
      setForm({ sloName: '', sloType: 'LATENCY', targetValue: '', unit: 'ms', description: '', enabled: true });
      onHideForm();
    },
    onError: () => showToast(t('alerts.sloConfigCreateError'), 'error'),
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    createMutation.mutate({
      sloName: form.sloName,
      sloType: form.sloType,
      targetValue: parseFloat(form.targetValue),
      unit: form.unit,
      description: form.description || undefined,
      enabled: form.enabled,
    });
  };

  return (
    <div>
      <div className={styles.toolbar}>
        <button className={styles.addBtn} onClick={onShowForm}>+ {t('alerts.sloConfig')}</button>
      </div>

      {showForm && (
        <div className={styles.formCard}>
          <h3>{t('alerts.sloConfig')}</h3>
          <ImeSafeForm onSubmit={handleSubmit} className={styles.form}>
            <div className={styles.formRow}>
              <label className={styles.label} htmlFor="alerts-sloname">{t('alerts.sloConfig')}</label>
              <input id="alerts-sloname"
                className={styles.input}
                value={form.sloName}
                onChange={e => setForm({ ...form, sloName: e.target.value })}
                placeholder={t('alerts.sloConfigNamePlaceholder')}
                required
              />
            </div>
            <div className={styles.formRow}>
              <label className={styles.label} htmlFor="alerts-slotype">{t('alerts.alertType')}</label>
              <select id="alerts-slotype" className={styles.select} value={form.sloType} onChange={e => setForm({ ...form, sloType: e.target.value })}>
                <option value="LATENCY">{t('alerts.latency')}</option>
                <option value="AVAILABILITY">{t('alerts.availability')}</option>
                <option value="QUALITY">{t('alerts.quality')}</option>
                <option value="ERROR_RATE">ERROR_RATE</option>
              </select>
            </div>
            <div className={styles.formRow}>
              <label className={styles.label} htmlFor="alerts-targetvalue">{t('alerts.targetValue')}</label>
              <input id="alerts-targetvalue"
                className={styles.input}
                type="number"
                step="any"
                value={form.targetValue}
                onChange={e => setForm({ ...form, targetValue: e.target.value })}
                required
              />
              <select
                className={styles.select}
                aria-label={t('alerts.unit')}
                value={form.unit}
                onChange={e => setForm({ ...form, unit: e.target.value })}
              >
                <option value="ms">ms</option>
                <option value="%">%</option>
                <option value="score">score</option>
              </select>
            </div>
            <div className={styles.formRow}>
              <label className={styles.label} htmlFor="alerts-description">{t('collections.description')}</label>
              <input id="alerts-description"
                className={styles.input}
                value={form.description}
                onChange={e => setForm({ ...form, description: e.target.value })}
                placeholder={t('collections.descriptionPlaceholder')}
              />
            </div>
            <div className={styles.formActions}>
              <button type="submit" className={styles.saveBtn} disabled={createMutation.isPending}>
                {createMutation.isPending ? t('common.loading') : t('common.create')}
              </button>
              <button type="button" className={styles.cancelBtn} onClick={onHideForm}>{t('common.cancel')}</button>
            </div>
          </ImeSafeForm>
        </div>
      )}

      {isPending ? (
        <div className={styles.loading}>{t('common.loading')}</div>
      ) : isError ? (
        <QueryErrorBanner onRetry={() => void refetch()} retryLabel={t('common.retry')}>
          {t('alerts.sloConfigLoadFailed')}
        </QueryErrorBanner>
      ) : !data?.data?.length ? (
        <EmptyState>{t('common.noData')}</EmptyState>
      ) : (
        <div className={styles.table}>
          <div className={styles.tableHeader}>
            <span>{t('alerts.sloConfig')}</span>
            <span>{t('alerts.alertType')}</span>
            <span>{t('alerts.target')}</span>
            <span>{t('alerts.unit')}</span>
            <span>{t('alerts.status')}</span>
            <span>{t('alerts.actions')}</span>
          </div>
          {data.data.map((slo: SloConfig) => (
            <div key={slo.id} className={styles.tableRow}>
              <span className={styles.sloName}>{slo.sloName}</span>
              <span>{slo.sloType}</span>
              <span>{slo.targetValue}</span>
              <span>{slo.unit}</span>
              <span className={slo.enabled ? styles.enabled : styles.disabled}>
                {slo.enabled ? t('common.yes') : t('common.no')}
              </span>
              <button
                className={styles.deleteBtn}
                onClick={() => setPendingSloDelete(slo.sloName)}
                disabled={deleteMutation.isPending}
              >
                {t('alerts.delete')}
              </button>
            </div>
          ))}
        </div>
      )}
      <ConfirmDialog
        open={pendingSloDelete !== null}
        title={t('alerts.delete')}
        description={t('alerts.deleteSloConfirm', { name: pendingSloDelete ?? '' })}
        confirmLabel={t('common.delete')}
        cancelLabel={t('common.cancel')}
        danger
        pending={deleteMutation.isPending}
        onConfirm={() => {
          if (pendingSloDelete !== null) deleteMutation.mutate(pendingSloDelete);
          setPendingSloDelete(null);
        }}
        onClose={() => setPendingSloDelete(null)}
      />
    </div>
  );
}

// ==================== Silence Schedules Tab ====================

interface SilenceFormData {
  name: string;
  alertKey: string;
  silenceType: string;
  startTime: string;
  endTime: string;
  description: string;
  enabled: boolean;
}

function SilenceSchedulesTab({ showForm, onShowForm, onHideForm }: { showForm: boolean; onShowForm: () => void; onHideForm: () => void }) {
  const { t } = useTranslation();
  const { showToast } = useToast();
  const [pendingScheduleDelete, setPendingScheduleDelete] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const { data, isPending, isError, refetch } = useQuery({
    queryKey: ['silence-schedules'],
    queryFn: () => alertsApi.listSilenceSchedules(),
  });
  const deleteMutation = useMutation({
    mutationFn: (name: string) => alertsApi.deleteSilenceSchedule(name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['silence-schedules'] });
    },
    // `alerts.deleteError` 的文案一直躺在 en.json / zh-CN.json 里，从未被任何一处
    // 引用过——正是为这个 onError 写的，只是没接上。
    onError: () => showToast(t('alerts.deleteError'), 'error'),
  });
  const [form, setForm] = useState<SilenceFormData>({
    name: '', alertKey: '', silenceType: 'ONE_TIME', startTime: '', endTime: '', description: '', enabled: true,
  });
  const createMutation = useMutation({
    mutationFn: (data: Parameters<typeof alertsApi.createSilenceSchedule>[0]) =>
      alertsApi.createSilenceSchedule(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['silence-schedules'] });
      setForm({ name: '', alertKey: '', silenceType: 'ONE_TIME', startTime: '', endTime: '', description: '', enabled: true });
      onHideForm();
    },
    onError: () => showToast(t('alerts.createError'), 'error'),
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    createMutation.mutate({
      name: form.name,
      alertKey: form.alertKey || undefined,
      silenceType: form.silenceType,
      startTime: form.startTime,
      endTime: form.endTime,
      description: form.description || undefined,
      enabled: form.enabled,
    });
  };

  return (
    <div>
      <div className={styles.toolbar}>
        <button className={styles.addBtn} onClick={onShowForm}>+ {t('alerts.createSilence')}</button>
      </div>

      {showForm && (
        <div className={styles.formCard}>
          <h3>{t('alerts.createSilence')}</h3>
          <ImeSafeForm onSubmit={handleSubmit} className={styles.form}>
            <div className={styles.formRow}>
              <label className={styles.label} htmlFor="alerts-name">{t('alerts.silencePlans')}</label>
              <input id="alerts-name"
                className={styles.input}
                value={form.name}
                onChange={e => setForm({ ...form, name: e.target.value })}
                placeholder={t('alerts.silenceNamePlaceholder')}
                required
              />
            </div>
            <div className={styles.formRow}>
              <label className={styles.label} htmlFor="alerts-alertkey">{t('alerts.alertKey')}</label>
              <input id="alerts-alertkey"
                className={styles.input}
                value={form.alertKey}
                onChange={e => setForm({ ...form, alertKey: e.target.value })}
                placeholder={t('alerts.silenceDescriptionPlaceholder')}
              />
            </div>
            <div className={styles.formRow}>
              <label className={styles.label} htmlFor="alerts-silencetype">{t('alerts.alertType')}</label>
              <select id="alerts-silencetype" className={styles.select} value={form.silenceType} onChange={e => setForm({ ...form, silenceType: e.target.value })}>
                <option value="ONE_TIME">ONE_TIME</option>
                <option value="RECURRING">RECURRING</option>
              </select>
            </div>
            <div className={styles.formRow}>
              <label className={styles.label} htmlFor="alerts-starttime">{t('alerts.triggeredAt')}</label>
              <input id="alerts-starttime"
                className={styles.input}
                type="datetime-local"
                value={form.startTime}
                onChange={e => setForm({ ...form, startTime: e.target.value })}
                required
              />
            </div>
            <div className={styles.formRow}>
              <label className={styles.label} htmlFor="alerts-endtime">{t('alerts.resolvedAt')}</label>
              <input id="alerts-endtime"
                className={styles.input}
                type="datetime-local"
                value={form.endTime}
                onChange={e => setForm({ ...form, endTime: e.target.value })}
                required
              />
            </div>
            <div className={styles.formRow}>
              <label className={styles.label} htmlFor="alerts-description">{t('collections.description')}</label>
              <input id="alerts-description"
                className={styles.input}
                value={form.description}
                onChange={e => setForm({ ...form, description: e.target.value })}
                placeholder={t('collections.descriptionPlaceholder')}
              />
            </div>
            <div className={styles.formActions}>
              <button type="submit" className={styles.saveBtn} disabled={createMutation.isPending}>
                {createMutation.isPending ? t('common.loading') : t('common.create')}
              </button>
              <button type="button" className={styles.cancelBtn} onClick={onHideForm}>{t('common.cancel')}</button>
            </div>
          </ImeSafeForm>
        </div>
      )}

      {isPending ? (
        <div className={styles.loading}>{t('common.loading')}</div>
      ) : isError ? (
        <QueryErrorBanner onRetry={() => void refetch()} retryLabel={t('common.retry')}>
          {t('alerts.silenceLoadFailed')}
        </QueryErrorBanner>
      ) : !data?.data?.length ? (
        <EmptyState>{t('alerts.noSilencePlans')}</EmptyState>
      ) : (
        <div className={styles.table}>
          <div className={styles.tableHeader}>
            <span>{t('alerts.silencePlans')}</span>
            <span>{t('alerts.alertKey')}</span>
            <span>{t('alerts.alertType')}</span>
            <span>{t('alerts.triggeredAt')}</span>
            <span>{t('alerts.resolvedAt')}</span>
            <span>{t('alerts.status')}</span>
            <span>{t('alerts.actions')}</span>
          </div>
          {data.data.map((schedule: SilenceSchedule) => (
            <div key={schedule.id} className={styles.tableRow}>
              <span className={styles.sloName}>{schedule.name}</span>
              <span>{schedule.alertKey ?? '—'}</span>
              <span>{schedule.silenceType}</span>
              <span>{schedule.startTime}</span>
              <span>{schedule.endTime}</span>
              <span className={schedule.enabled ? styles.enabled : styles.disabled}>
                {schedule.enabled ? t('common.yes') : t('common.no')}
              </span>
              <button
                className={styles.deleteBtn}
                onClick={() => setPendingScheduleDelete(schedule.name)}
                disabled={deleteMutation.isPending}
              >
                {t('alerts.delete')}
              </button>
            </div>
          ))}
        </div>
      )}
      <ConfirmDialog
        open={pendingScheduleDelete !== null}
        title={t('alerts.delete')}
        description={t('alerts.deleteSilenceConfirm', { name: pendingScheduleDelete ?? '' })}
        confirmLabel={t('common.delete')}
        cancelLabel={t('common.cancel')}
        danger
        pending={deleteMutation.isPending}
        onConfirm={() => {
          if (pendingScheduleDelete !== null) deleteMutation.mutate(pendingScheduleDelete);
          setPendingScheduleDelete(null);
        }}
        onClose={() => setPendingScheduleDelete(null)}
      />
    </div>
  );
}
