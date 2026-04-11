import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { apiKeysApi, type ApiKeyResponse, type ApiKeyCreateRequest } from '../api/apikeys';
import { useToast } from '../components/Toast';
import styles from './ApiKeys.module.css';

export function ApiKeys() {
  const { t } = useTranslation();
  return (
    <div>
      <h1 className="page-title">{t('apiKeys.title')}</h1>
      <KeyList />
    </div>
  );
}

// ==================== Key List ====================

function KeyList() {
  const { t } = useTranslation();
  const [showCreate, setShowCreate] = useState(false);
  const [showRotate, setShowRotate] = useState<string | null>(null);
  const { data, isPending, isError } = useQuery({
    queryKey: ['apikeys'],
    queryFn: () => apiKeysApi.listKeys(),
  });

  return (
    <div>
      <div className={styles.toolbar}>
        <button className={styles.btnPrimary} onClick={() => setShowCreate(true)}>
          {t('apiKeys.createKey')}
        </button>
      </div>

      {isPending ? (
        <div className={styles.loading}>{t('common.loading')}</div>
      ) : isError ? (
        <div className={styles.empty}>{t('common.error')}</div>
      ) : !data?.data?.length ? (
        <div className={styles.empty}>
          <span>{t('apiKeys.noKeys')}</span>
          <button className={styles.btnPrimary} onClick={() => setShowCreate(true)}>
            {t('apiKeys.createFirst')}
          </button>
        </div>
      ) : (
        <div className={styles.table}>
          <div className={styles.tableHead}>
            <span>{t('apiKeys.name')}</span>
            <span>{t('apiKeys.keyId')}</span>
            <span>{t('apiKeys.created')}</span>
            <span>{t('apiKeys.lastUsed')}</span>
            <span>{t('apiKeys.status')}</span>
            <span>{t('apiKeys.expires')}</span>
          </div>
          {data.data.map(key => (
            <KeyRow
              key={key.keyId}
              keyItem={key}
              onRotate={() => setShowRotate(key.keyId)}
            />
          ))}
        </div>
      )}

      {showCreate && (
        <CreateKeyModal onClose={() => setShowCreate(false)} />
      )}
      {showRotate && (
        <RotateKeyModal
          keyId={showRotate}
          onClose={() => setShowRotate(null)}
        />
      )}
    </div>
  );
}

function KeyRow({ keyItem, onRotate }: { keyItem: ApiKeyResponse; onRotate: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { showToast } = useToast();

  const revokeMutation = useMutation({
    mutationFn: () => apiKeysApi.revokeKey(keyItem.keyId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['apikeys'] });
      showToast(t('apiKeys.revoked'), 'success');
    },
    onError: () => {
      showToast(t('apiKeys.revokeError'), 'error');
    },
  });

  const statusBadge = getStatusBadge(keyItem, t);

  const formatDate = (dateStr?: string) => {
    if (!dateStr) return '—';
    try {
      return new Date(dateStr).toLocaleString();
    } catch {
      return dateStr;
    }
  };

  return (
    <div className={styles.tableRow}>
      <span className={styles.name}>{keyItem.name}</span>
      <span className={styles.keyId} title={keyItem.keyId}>{keyItem.keyId}</span>
      <span className={styles.date}>{formatDate(keyItem.createdAt)}</span>
      <span className={styles.date}>{formatDate(keyItem.lastUsedAt)}</span>
      <span>{statusBadge}</span>
      <span className={styles.date}>{formatDate(keyItem.expiresAt)}</span>
      <span>
        <button
          className={styles.btnLink}
          onClick={onRotate}
          disabled={!keyItem.enabled}
          title={t('apiKeys.rotateTooltip')}
        >
          {t('apiKeys.rotate')}
        </button>
        <span style={{ margin: '0 0.25rem', color: 'var(--color-border)' }}>·</span>
        <button
          className={styles.btnLink}
          onClick={() => revokeMutation.mutate()}
          disabled={revokeMutation.isPending}
          style={{ color: '#ef4444' }}
        >
          {t('apiKeys.revoke')}
        </button>
      </span>
    </div>
  );
}

function getStatusBadge(key: ApiKeyResponse, t: (key: string) => string) {
  if (!key.enabled) {
    return <span className={`${styles.badge} ${styles.badgeDisabled}`}>{t('apiKeys.revoked')}</span>;
  }
  if (key.expiresAt) {
    const expired = new Date(key.expiresAt) < new Date();
    if (expired) {
      return <span className={`${styles.badge} ${styles.badgeExpired}`}>{t('apiKeys.expired')}</span>;
    }
  }
  return <span className={`${styles.badge} ${styles.badgeActive}`}>{t('apiKeys.active')}</span>;
}

// ==================== Create Key Modal ====================

function CreateKeyModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [expiresAt, setExpiresAt] = useState('');
  const [createdKey, setCreatedKey] = useState<{ keyId: string; rawKey: string; name: string; expiresAt?: string; warning: string } | null>(null);
  const { showToast } = useToast();
  const queryClient = useQueryClient();

  const createMutation = useMutation({
    mutationFn: (data: ApiKeyCreateRequest) => apiKeysApi.createKey(data),
    onSuccess: (response) => {
      setCreatedKey(response.data);
      queryClient.invalidateQueries({ queryKey: ['apikeys'] });
    },
    onError: () => {
      showToast(t('apiKeys.createError'), 'error');
    },
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;
    const data: ApiKeyCreateRequest = { name: name.trim() };
    if (expiresAt) {
      data.expiresAt = new Date(expiresAt).toISOString();
    }
    createMutation.mutate(data);
  };

  const handleClose = () => {
    onClose();
  };

  return (
    <div className={styles.modal} onClick={(e) => e.target === e.currentTarget && handleClose()}>
      <div className={styles.modalContent}>
        <div className={styles.modalHeader}>
          <h2 className={styles.modalTitle}>{t('apiKeys.createKey')}</h2>
          <button className={styles.modalClose} onClick={handleClose}>✕</button>
        </div>

        {!createdKey ? (
          <form onSubmit={handleSubmit}>
            <div className={styles.formGroup}>
              <label className={styles.label}>{t('apiKeys.name')} *</label>
              <input
                type="text"
                className={styles.input}
                value={name}
                onChange={e => setName(e.target.value)}
                placeholder={t('apiKeys.namePlaceholder')}
                required
                maxLength={255}
              />
            </div>
            <div className={styles.formGroup}>
              <label className={styles.label}>{t('apiKeys.expiresAt')} {t('common.optional')}</label>
              <input
                type="datetime-local"
                className={styles.input}
                value={expiresAt}
                onChange={e => setExpiresAt(e.target.value)}
              />
              <div className={styles.hint}>{t('apiKeys.expiresAtHint')}</div>
            </div>
            <div className={styles.modalActions}>
              <button type="button" className={styles.btnSecondary} onClick={handleClose}>
                {t('common.cancel')}
              </button>
              <button
                type="submit"
                className={styles.btnPrimary}
                disabled={createMutation.isPending || !name.trim()}
              >
                {createMutation.isPending ? t('common.loading') : t('apiKeys.create')}
              </button>
            </div>
          </form>
        ) : (
          <div>
            <p style={{ marginBottom: '0.75rem', color: 'var(--color-text-secondary)', fontSize: '0.875rem' }}>
              {t('apiKeys.keyCreated')}
            </p>
            <div className={styles.rawKeyBox}>
              <div className={styles.rawKeyLabel}>{t('apiKeys.name')}</div>
              <div className={styles.name}>{createdKey.name}</div>
              <div className={styles.rawKeyLabel} style={{ marginTop: '0.75rem' }}>{t('apiKeys.keyId')}</div>
              <div className={styles.mono}>{createdKey.keyId}</div>
              <div className={styles.rawKeyLabel} style={{ marginTop: '0.75rem' }}>{t('apiKeys.rawKey')}</div>
              <div className={styles.rawKey}>{createdKey.rawKey}</div>
              <div className={styles.warning}>{createdKey.warning}</div>
            </div>
            <div className={styles.modalActions}>
              <button className={styles.btnPrimary} onClick={handleClose}>
                {t('common.close')}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

// ==================== Rotate Key Modal ====================

function RotateKeyModal({ keyId, onClose }: { keyId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const [rotatedKey, setRotatedKey] = useState<{ keyId: string; rawKey: string; name: string; expiresAt?: string; warning: string } | null>(null);
  const { showToast } = useToast();
  const queryClient = useQueryClient();

  const rotateMutation = useMutation({
    mutationFn: () => apiKeysApi.rotateKey(keyId),
    onSuccess: (response) => {
      setRotatedKey(response.data);
      queryClient.invalidateQueries({ queryKey: ['apikeys'] });
    },
    onError: () => {
      showToast(t('apiKeys.rotateError'), 'error');
    },
  });

  const handleClose = () => {
    onClose();
  };

  return (
    <div className={styles.modal} onClick={(e) => e.target === e.currentTarget && handleClose()}>
      <div className={styles.modalContent}>
        <div className={styles.modalHeader}>
          <h2 className={styles.modalTitle}>{t('apiKeys.rotateKey')}</h2>
          <button className={styles.modalClose} onClick={handleClose}>✕</button>
        </div>

        {!rotatedKey ? (
          <div>
            <div className={styles.rotateInfo}>
              {t('apiKeys.rotateInfo')}
            </div>
            <div className={styles.formGroup}>
              <label className={styles.label}>{t('apiKeys.keyId')}</label>
              <div className={styles.mono} style={{ fontSize: '0.8rem' }}>{keyId}</div>
            </div>
            <div className={styles.modalActions}>
              <button type="button" className={styles.btnSecondary} onClick={handleClose}>
                {t('common.cancel')}
              </button>
              <button
                className={styles.btnPrimary}
                onClick={() => rotateMutation.mutate()}
                disabled={rotateMutation.isPending}
              >
                {rotateMutation.isPending ? t('common.loading') : t('apiKeys.rotateConfirm')}
              </button>
            </div>
          </div>
        ) : (
          <div>
            <p style={{ marginBottom: '0.75rem', color: 'var(--color-text-secondary)', fontSize: '0.875rem' }}>
              {t('apiKeys.keyRotated')}
            </p>
            <div className={styles.rawKeyBox}>
              <div className={styles.rawKeyLabel}>{t('apiKeys.name')}</div>
              <div className={styles.name}>{rotatedKey.name}</div>
              <div className={styles.rawKeyLabel} style={{ marginTop: '0.75rem' }}>{t('apiKeys.keyId')}</div>
              <div className={styles.mono}>{rotatedKey.keyId}</div>
              <div className={styles.rawKeyLabel} style={{ marginTop: '0.75rem' }}>{t('apiKeys.rawKey')}</div>
              <div className={styles.rawKey}>{rotatedKey.rawKey}</div>
              <div className={styles.warning}>{rotatedKey.warning}</div>
            </div>
            <div className={styles.modalActions}>
              <button className={styles.btnPrimary} onClick={handleClose}>
                {t('common.close')}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
