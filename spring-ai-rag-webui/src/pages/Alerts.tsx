import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { alertsApi, type SloConfig, type SilenceSchedule } from '../api/alerts';
import styles from './Alerts.module.css';

type Tab = 'alerts' | 'slo-configs' | 'silence-schedules';

export function Alerts() {
  const { t } = useTranslation();
  const [tab, setTab] = useState<Tab>('alerts');
  const [showSloForm, setShowSloForm] = useState(false);
  const [showSilenceForm, setShowSilenceForm] = useState(false);

  return (
    <div>
      <h1 className="page-title">{t('alerts.title')}</h1>

      <div className={styles.tabs}>
        <button
          className={`${styles.tab} ${tab === 'alerts' ? styles.tabActive : ''}`}
          onClick={() => setTab('alerts')}
        >
          {t('alerts.active')}
        </button>
        <button
          className={`${styles.tab} ${tab === 'slo-configs' ? styles.tabActive : ''}`}
          onClick={() => setTab('slo-configs')}
        >
          {t('alerts.sloConfig')}
        </button>
        <button
          className={`${styles.tab} ${tab === 'silence-schedules' ? styles.tabActive : ''}`}
          onClick={() => setTab('silence-schedules')}
        >
          {t('alerts.silencePlans')}
        </button>
      </div>

      {tab === 'alerts' && <AlertsTab />}
      {tab === 'slo-configs' && (
        <SloConfigsTab
          showForm={showSloForm}
          onShowForm={() => setShowSloForm(true)}
          onHideForm={() => setShowSloForm(false)}
        />
      )}
      {tab === 'silence-schedules' && (
        <SilenceSchedulesTab
          showForm={showSilenceForm}
          onShowForm={() => setShowSilenceForm(true)}
          onHideForm={() => setShowSilenceForm(false)}
        />
      )}
    </div>
  );
}

// ==================== Active Alerts Tab ====================

function AlertsTab() {
  const { t } = useTranslation();
  const { data, isPending } = useQuery({
    queryKey: ['alerts'],
    queryFn: () => alertsApi.listActive(),
    refetchInterval: 30_000,
  });

  if (isPending) return <div className={styles.loading}>{t('common.loading')}</div>;

  if (!data?.data?.length) {
    return <div className={styles.empty}>{t('alerts.noActiveAlerts')}</div>;
  }

  return (
    <div className={styles.list}>
      {data.data.map(alert => (
        <div key={alert.id} className={styles.item} data-severity={alert.severity}>
          <div className={styles.header}>
            <span className={styles.name}>{alert.alertName}</span>
            <span className={styles.severity} data-level={alert.severity}>{alert.severity}</span>
          </div>
          <div className={styles.message}>{alert.message}</div>
          <div className={styles.time}>
            {t('alerts.triggeredAt')}: {new Date(alert.triggeredAt).toLocaleString()}
          </div>
        </div>
      ))}
    </div>
  );
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
  const queryClient = useQueryClient();
  const { data, isPending } = useQuery({
    queryKey: ['slo-configs'],
    queryFn: () => alertsApi.listSloConfigs(),
  });
  const deleteMutation = useMutation({
    mutationFn: (sloName: string) => alertsApi.deleteSloConfig(sloName),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['slo-configs'] });
    },
    onError: () => {},
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
    onError: () => {},
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
          <form onSubmit={handleSubmit} className={styles.form}>
            <div className={styles.formRow}>
              <label className={styles.label}>{t('alerts.sloConfig')}</label>
              <input
                className={styles.input}
                value={form.sloName}
                onChange={e => setForm({ ...form, sloName: e.target.value })}
                placeholder="e.g. latency_p99"
                required
              />
            </div>
            <div className={styles.formRow}>
              <label className={styles.label}>{t('alerts.alertType')}</label>
              <select className={styles.select} value={form.sloType} onChange={e => setForm({ ...form, sloType: e.target.value })}>
                <option value="LATENCY">{t('alerts.latency')}</option>
                <option value="AVAILABILITY">{t('alerts.availability')}</option>
                <option value="QUALITY">{t('alerts.quality')}</option>
                <option value="ERROR_RATE">ERROR_RATE</option>
              </select>
            </div>
            <div className={styles.formRow}>
              <label className={styles.label}>Target Value</label>
              <input
                className={styles.input}
                type="number"
                step="any"
                value={form.targetValue}
                onChange={e => setForm({ ...form, targetValue: e.target.value })}
                required
              />
              <select className={styles.select} value={form.unit} onChange={e => setForm({ ...form, unit: e.target.value })}>
                <option value="ms">ms</option>
                <option value="%">%</option>
                <option value="score">score</option>
              </select>
            </div>
            <div className={styles.formRow}>
              <label className={styles.label}>{t('collections.description')}</label>
              <input
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
          </form>
        </div>
      )}

      {isPending ? (
        <div className={styles.loading}>{t('common.loading')}</div>
      ) : !data?.data?.length ? (
        <div className={styles.empty}>{t('common.noData')}</div>
      ) : (
        <div className={styles.table}>
          <div className={styles.tableHeader}>
            <span>{t('alerts.sloConfig')}</span>
            <span>{t('alerts.alertType')}</span>
            <span>Target</span>
            <span>Unit</span>
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
                {slo.enabled ? 'Yes' : 'No'}
              </span>
              <button
                className={styles.deleteBtn}
                onClick={() => deleteMutation.mutate(slo.sloName)}
                disabled={deleteMutation.isPending}
              >
                {t('alerts.deleteSilence')}
              </button>
            </div>
          ))}
        </div>
      )}
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
  const queryClient = useQueryClient();
  const { data, isPending } = useQuery({
    queryKey: ['silence-schedules'],
    queryFn: () => alertsApi.listSilenceSchedules(),
  });
  const deleteMutation = useMutation({
    mutationFn: (name: string) => alertsApi.deleteSilenceSchedule(name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['silence-schedules'] });
    },
    onError: () => {},
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
    onError: () => {},
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
          <form onSubmit={handleSubmit} className={styles.form}>
            <div className={styles.formRow}>
              <label className={styles.label}>{t('alerts.silencePlans')}</label>
              <input
                className={styles.input}
                value={form.name}
                onChange={e => setForm({ ...form, name: e.target.value })}
                placeholder="e.g. weekend-maintenance"
                required
              />
            </div>
            <div className={styles.formRow}>
              <label className={styles.label}>Alert Key</label>
              <input
                className={styles.input}
                value={form.alertKey}
                onChange={e => setForm({ ...form, alertKey: e.target.value })}
                placeholder="Leave empty to silence all alerts"
              />
            </div>
            <div className={styles.formRow}>
              <label className={styles.label}>{t('alerts.alertType')}</label>
              <select className={styles.select} value={form.silenceType} onChange={e => setForm({ ...form, silenceType: e.target.value })}>
                <option value="ONE_TIME">ONE_TIME</option>
                <option value="RECURRING">RECURRING</option>
              </select>
            </div>
            <div className={styles.formRow}>
              <label className={styles.label}>{t('alerts.triggeredAt')}</label>
              <input
                className={styles.input}
                type="datetime-local"
                value={form.startTime}
                onChange={e => setForm({ ...form, startTime: e.target.value })}
                required
              />
            </div>
            <div className={styles.formRow}>
              <label className={styles.label}>{t('alerts.resolvedAt')}</label>
              <input
                className={styles.input}
                type="datetime-local"
                value={form.endTime}
                onChange={e => setForm({ ...form, endTime: e.target.value })}
                required
              />
            </div>
            <div className={styles.formRow}>
              <label className={styles.label}>{t('collections.description')}</label>
              <input
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
          </form>
        </div>
      )}

      {isPending ? (
        <div className={styles.loading}>{t('common.loading')}</div>
      ) : !data?.data?.length ? (
        <div className={styles.empty}>{t('alerts.noSilencePlans')}</div>
      ) : (
        <div className={styles.table}>
          <div className={styles.tableHeader}>
            <span>{t('alerts.silencePlans')}</span>
            <span>Alert Key</span>
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
                {schedule.enabled ? 'Yes' : 'No'}
              </span>
              <button
                className={styles.deleteBtn}
                onClick={() => deleteMutation.mutate(schedule.name)}
                disabled={deleteMutation.isPending}
              >
                {t('alerts.deleteSilence')}
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
