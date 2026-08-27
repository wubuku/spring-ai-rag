import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  apiKeysApi,
  type ApiKeyCreatedResponse,
  type ApiKeyCreateRequest,
  type ApiKeyRotationResponse,
  type ApiPrincipalPolicyUpdateRequest,
  type ApiPrincipalResponse,
} from '../api/apikeys';
import { collectionsApi } from '../api/collections';
import { useToast } from '../components/Toast';
import styles from './ApiKeys.module.css';

const DEFAULT_EXPIRY_DAYS = 365;
const READ_ONLY_CAPABILITIES = ['RAG_READ'];
const FULL_CAPABILITIES = ['RAG_READ', 'RAG_WRITE'];
const API_PRINCIPALS_QUERY_KEY = ['api-principals'] as const;

type ApiPrincipalQueryData = Awaited<
  ReturnType<typeof apiKeysApi.listPrincipals>
>;

function normalizedCapabilities(capabilities?: string[]): string[] {
  return capabilities?.length ? capabilities : FULL_CAPABILITIES;
}

function toLocalDateTimeInput(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return [
    date.getFullYear(),
    '-',
    pad(date.getMonth() + 1),
    '-',
    pad(date.getDate()),
    'T',
    pad(date.getHours()),
    ':',
    pad(date.getMinutes()),
  ].join('');
}

function createExpiryDefaults() {
  const now = new Date();
  const minimum = new Date(now.getTime() + 5 * 60 * 1000);
  const suggested = new Date(
    now.getTime() + DEFAULT_EXPIRY_DAYS * 24 * 60 * 60 * 1000,
  );
  return {
    minimum: toLocalDateTimeInput(minimum),
    suggested: toLocalDateTimeInput(suggested),
  };
}

function formatMutationError(fallback: string, error: unknown): string {
  if (!(error instanceof Error) || !error.message.trim()) {
    return fallback;
  }
  return `${fallback}: ${error.message}`;
}

function formatDateTime(dateStr?: string): string {
  if (!dateStr) return '—';
  try {
    return new Date(dateStr).toLocaleString();
  } catch {
    return dateStr;
  }
}

function principalFromCreatedKey(
  created: ApiKeyCreatedResponse,
): ApiPrincipalResponse {
  const now = new Date().toISOString();
  return {
    principalId: created.principalId,
    name: created.name,
    role: 'NORMAL',
    capabilities: normalizedCapabilities(created.capabilities),
    allowedCollectionKeys: created.allowedCollectionKeys,
    expiresAt: created.expiresAt,
    requestsPerMinute: created.requestsPerMinute,
    policyVersion: created.policyVersion,
    status: 'ACTIVE',
    currentCredentialId: created.keyId,
    currentCredentialVersion: created.credentialVersion,
    rotationPending: false,
    createdAt: now,
    updatedAt: now,
  };
}

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
  const [showRotate, setShowRotate] = useState<ApiPrincipalResponse | null>(null);
  const [showEdit, setShowEdit] = useState<ApiPrincipalResponse | null>(null);
  const { data, isPending, isError } = useQuery({
    queryKey: API_PRINCIPALS_QUERY_KEY,
    queryFn: () => apiKeysApi.listPrincipals(),
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
            <span>{t('apiKeys.principalId')}</span>
            <span>{t('apiKeys.credential')}</span>
            <span>{t('apiKeys.profile')}</span>
            <span>{t('apiKeys.capabilities')}</span>
            <span>{t('apiKeys.collectionAccess')}</span>
            <span>{t('apiKeys.quota')}</span>
            <span>{t('apiKeys.lastUsed')}</span>
            <span>{t('apiKeys.status')}</span>
            <span>{t('apiKeys.expires')}</span>
            <span>{t('common.actions')}</span>
          </div>
          {data.data.map(principal => (
            <PrincipalRow
              key={principal.principalId}
              principal={principal}
              onEdit={() => setShowEdit(principal)}
              onRotate={() => setShowRotate(principal)}
            />
          ))}
        </div>
      )}

      {showCreate && (
        <CreateKeyModal onClose={() => setShowCreate(false)} />
      )}
      {showRotate && (
        <RotateKeyModal
          principal={showRotate}
          onClose={() => setShowRotate(null)}
        />
      )}
      {showEdit && (
        <EditPolicyModal
          principal={showEdit}
          onClose={() => setShowEdit(null)}
        />
      )}
    </div>
  );
}

function PrincipalRow({
  principal,
  onEdit,
  onRotate,
}: {
  principal: ApiPrincipalResponse;
  onEdit: () => void;
  onRotate: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { showToast } = useToast();

  const revokeMutation = useMutation({
    mutationFn: () => apiKeysApi.revokeKey(principal.currentCredentialId!),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['api-principals'] });
      showToast(t('apiKeys.revoked'), 'success');
    },
    onError: () => {
      showToast(t('apiKeys.revokeError'), 'error');
    },
  });

  const completeRotationMutation = useMutation({
    mutationKey: ['complete-api-key-rotation', principal.pendingRotationId],
    mutationFn: () => apiKeysApi.completeRotation(principal.pendingRotationId!),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['api-principals'] });
      showToast(t('apiKeys.rotationCompleted'), 'success');
    },
    onError: (error) => {
      showToast(
        formatMutationError(t('apiKeys.rotationCompleteError'), error),
        'error',
      );
    },
  });

  const cancelRotationMutation = useMutation({
    mutationKey: ['cancel-api-key-rotation', principal.pendingRotationId],
    mutationFn: () => apiKeysApi.cancelRotation(principal.pendingRotationId!),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['api-principals'] });
      showToast(t('apiKeys.rotationCanceled'), 'success');
    },
    onError: (error) => {
      showToast(
        formatMutationError(t('apiKeys.rotationCancelError'), error),
        'error',
      );
    },
  });

  const statusBadge = getStatusBadge(principal.status, t);
  const rotationPending = Boolean(
    principal.rotationPending && principal.pendingRotationId,
  );

  return (
    <div className={styles.tableRow}>
      <span className={styles.name}>{principal.name}</span>
      <span className={styles.keyId} title={principal.principalId}>{principal.principalId}</span>
      <span className={styles.credential}>
        {principal.currentCredentialId ? (
          <>
            <small>{t('apiKeys.currentCredential')}</small>
            <code title={principal.currentCredentialId}>
              {principal.currentCredentialId}
            </code>
            <small>v{principal.currentCredentialVersion}</small>
            {rotationPending && principal.retiringCredentialId && (
              <span className={styles.retiringCredential}>
                <small>{t('apiKeys.retiringCredential')}</small>
                <code title={principal.retiringCredentialId}>
                  {principal.retiringCredentialId}
                </code>
                <small>v{principal.retiringCredentialVersion}</small>
              </span>
            )}
          </>
        ) : '—'}
      </span>
      <span>{getRoleBadge(principal.role, t)}</span>
      <span className={styles.capabilities}>
        {normalizedCapabilities(principal.capabilities).map(capability => (
          <code key={capability}>{capability}</code>
        ))}
      </span>
      <span className={styles.scope}>
        {principal.allowedCollectionKeys?.length
          ? principal.allowedCollectionKeys.join(', ')
          : t('apiKeys.allCollections')}
      </span>
      <span>{principal.requestsPerMinute ?? t('apiKeys.defaultQuota')}</span>
      <span className={styles.date}>{formatDateTime(principal.lastUsedAt)}</span>
      <span className={styles.statusStack}>
        {statusBadge}
        {rotationPending && (
          <>
            <span className={`${styles.badge} ${styles.badgePending}`}>
              {t('apiKeys.rotationPending')}
            </span>
            <small>
              {t('apiKeys.rotationDeadline', {
                deadline: formatDateTime(principal.rotationExpiresAt),
              })}
            </small>
          </>
        )}
      </span>
      <span className={styles.date}>{formatDateTime(principal.expiresAt)}</span>
      <span className={styles.rowActions}>
        <button
          className={styles.btnLink}
          onClick={onEdit}
          disabled={principal.status !== 'ACTIVE'}
        >
          {t('apiKeys.editPolicy')}
        </button>
        <button
          className={styles.btnLink}
          onClick={onRotate}
          disabled={
            principal.status !== 'ACTIVE'
            || !principal.currentCredentialId
            || rotationPending
          }
          title={
            rotationPending
              ? t('apiKeys.rotationAlreadyPending')
              : t('apiKeys.rotateTooltip')
          }
        >
          {t('apiKeys.rotate')}
        </button>
        {rotationPending && (
          <>
            <button
              className={styles.btnLink}
              onClick={() => completeRotationMutation.mutate()}
              disabled={
                completeRotationMutation.isPending
                || cancelRotationMutation.isPending
              }
            >
              {t('apiKeys.completeRotation')}
            </button>
            <button
              className={styles.btnLink}
              onClick={() => cancelRotationMutation.mutate()}
              disabled={
                completeRotationMutation.isPending
                || cancelRotationMutation.isPending
              }
            >
              {t('apiKeys.cancelRotation')}
            </button>
          </>
        )}
        <button
          className={styles.btnLink}
          onClick={() => revokeMutation.mutate()}
          disabled={
            revokeMutation.isPending
            || principal.status !== 'ACTIVE'
            || !principal.currentCredentialId
          }
          style={{ color: '#ef4444' }}
        >
          {t('apiKeys.revoke')}
        </button>
      </span>
    </div>
  );
}

function getRoleBadge(role: string | undefined, t: (key: string) => string) {
  if (role === 'ADMIN') {
    return <span className={`${styles.badge} ${styles.badgeAdmin}`}>{t('apiKeys.admin')}</span>;
  }
  if (role === 'NORMAL') {
    return <span className={`${styles.badge} ${styles.badgeNormal}`}>{t('apiKeys.normal')}</span>;
  }
  return <span className={`${styles.badge} ${styles.badgeNormal}`}>—</span>;
}

function getStatusBadge(status: ApiPrincipalResponse['status'], t: (key: string) => string) {
  if (status === 'REVOKED') {
    return <span className={`${styles.badge} ${styles.badgeDisabled}`}>{t('apiKeys.revoked')}</span>;
  }
  if (status === 'EXPIRED') {
    return <span className={`${styles.badge} ${styles.badgeExpired}`}>{t('apiKeys.expired')}</span>;
  }
  return <span className={`${styles.badge} ${styles.badgeActive}`}>{t('apiKeys.active')}</span>;
}

// ==================== Create Key Modal ====================

function CreateKeyModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [expiryDefaults] = useState(createExpiryDefaults);
  const [capabilities, setCapabilities] = useState<string[]>(FULL_CAPABILITIES);
  const [restrictCollections, setRestrictCollections] = useState(false);
  const [selectedCollectionKeys, setSelectedCollectionKeys] = useState<string[]>([]);
  const [createdKey, setCreatedKey] = useState<ApiKeyCreatedResponse | null>(null);
  const { showToast } = useToast();
  const queryClient = useQueryClient();
  const collectionsQuery = useQuery({
    queryKey: ['collections', 'api-key-scope'],
    queryFn: () => collectionsApi.list({ page: 0, size: 200 }),
  });

  const createMutation = useMutation({
    mutationFn: (data: ApiKeyCreateRequest) => apiKeysApi.createKey(data),
    onMutate: () => queryClient.cancelQueries({
      queryKey: API_PRINCIPALS_QUERY_KEY,
    }),
    onSuccess: (response) => {
      const created = response.data;
      const createdPrincipal = principalFromCreatedKey(created);
      setCreatedKey(created);
      queryClient.setQueryData<ApiPrincipalQueryData>(
        API_PRINCIPALS_QUERY_KEY,
        current => current ? ({
          ...current,
          data: [
            createdPrincipal,
            ...current.data.filter(
              principal => principal.principalId !== created.principalId,
            ),
          ],
        }) : current,
      );
      void queryClient.invalidateQueries({
        queryKey: API_PRINCIPALS_QUERY_KEY,
      });
    },
    onError: (error) => {
      void queryClient.invalidateQueries({
        queryKey: API_PRINCIPALS_QUERY_KEY,
      });
      showToast(formatMutationError(t('apiKeys.createError'), error), 'error');
    },
  });

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const expiresAtValue = new FormData(e.currentTarget).get('expiresAt');
    const quotaValue = new FormData(e.currentTarget).get('requestsPerMinute');
    if (!name.trim()
        || typeof expiresAtValue !== 'string'
        || !expiresAtValue) {
      return;
    }
    const data: ApiKeyCreateRequest = {
      name: name.trim(),
      expiresAt: expiresAtValue.length === 16
        ? `${expiresAtValue}:00`
        : expiresAtValue,
      capabilities,
    };
    if (restrictCollections) {
      data.allowedCollectionKeys = selectedCollectionKeys;
    }
    if (typeof quotaValue === 'string' && quotaValue.trim()) {
      data.requestsPerMinute = Number(quotaValue);
    }
    createMutation.mutate(data);
  };

  const toggleCollection = (collectionKey: string) => {
    setSelectedCollectionKeys(current =>
      current.includes(collectionKey)
        ? current.filter(key => key !== collectionKey)
        : [...current, collectionKey],
    );
  };

  const handleClose = () => {
    setCreatedKey(null);
    onClose();
  };

  const copyRawKey = async () => {
    if (!createdKey) return;
    await navigator.clipboard.writeText(createdKey.rawKey);
    showToast(t('apiKeys.copied'), 'success');
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
              <label className={styles.label}>{t('apiKeys.expiresAt')} {t('common.required')}</label>
              <input
                type="datetime-local"
                name="expiresAt"
                className={styles.input}
                defaultValue={expiryDefaults.suggested}
                min={expiryDefaults.minimum}
                required
              />
              <div className={styles.hint}>{t('apiKeys.expiresAtHint')}</div>
            </div>
            <div className={styles.formGroup}>
              <span className={styles.label}>{t('apiKeys.profile')}</span>
              <span className={`${styles.badge} ${styles.badgeNormal}`}>
                {t('apiKeys.normal')}
              </span>
              <div className={styles.hint}>{t('apiKeys.normalHint')}</div>
            </div>
            <CapabilitySelector
              capabilities={capabilities}
              onChange={setCapabilities}
            />
            <div className={styles.formGroup}>
              <label className={styles.label} htmlFor="create-key-quota">
                {t('apiKeys.quota')}
              </label>
              <input
                id="create-key-quota"
                type="number"
                name="requestsPerMinute"
                className={styles.input}
                min="1"
                max="1000000"
                placeholder={t('apiKeys.quotaPlaceholder')}
              />
              <div className={styles.hint}>{t('apiKeys.quotaHint')}</div>
            </div>
            <fieldset className={styles.scopeFieldset}>
              <legend className={styles.label}>{t('apiKeys.collectionAccess')}</legend>
              <label className={styles.scopeOption}>
                <input
                  type="radio"
                  name="collectionScope"
                  checked={!restrictCollections}
                  onChange={() => setRestrictCollections(false)}
                />
                <span>
                  <strong>{t('apiKeys.allCollections')}</strong>
                  <small>{t('apiKeys.allCollectionsHint')}</small>
                </span>
              </label>
              <label className={styles.scopeOption}>
                <input
                  type="radio"
                  name="collectionScope"
                  checked={restrictCollections}
                  onChange={() => setRestrictCollections(true)}
                />
                <span>
                  <strong>{t('apiKeys.selectedCollections')}</strong>
                  <small>{t('apiKeys.selectedCollectionsHint')}</small>
                </span>
              </label>
              {restrictCollections && (
                <div className={styles.collectionSelector}>
                  {collectionsQuery.isPending ? (
                    <div className={styles.hint}>{t('common.loading')}</div>
                  ) : collectionsQuery.isError ? (
                    <div className={styles.scopeError}>{t('apiKeys.collectionsLoadError')}</div>
                  ) : !collectionsQuery.data?.data?.collections?.length ? (
                    <div className={styles.hint}>{t('collections.noCollections')}</div>
                  ) : (
                    collectionsQuery.data.data.collections.map(collection => (
                      <label className={styles.collectionOption} key={collection.collectionKey}>
                        <input
                          type="checkbox"
                          checked={selectedCollectionKeys.includes(collection.collectionKey)}
                          onChange={() => toggleCollection(collection.collectionKey)}
                        />
                        <span>{collection.name}</span>
                        <code>{collection.collectionKey}</code>
                      </label>
                    ))
                  )}
                </div>
              )}
            </fieldset>
            <div className={styles.modalActions}>
              <button type="button" className={styles.btnSecondary} onClick={handleClose}>
                {t('common.cancel')}
              </button>
              <button
                type="submit"
                className={styles.btnPrimary}
                disabled={
                  createMutation.isPending
                  || !name.trim()
                  || (restrictCollections && selectedCollectionKeys.length === 0)
                }
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
              <div className={styles.rawKeyRow}>
                <div className={styles.rawKey}>{createdKey.rawKey}</div>
                <button type="button" className={styles.copyBtn} onClick={copyRawKey}>
                  {t('apiKeys.copy')}
                </button>
              </div>
              <div className={styles.rawKeyLabel} style={{ marginTop: '0.75rem' }}>
                {t('apiKeys.capabilities')}
              </div>
              <div className={styles.capabilities}>
                {normalizedCapabilities(createdKey.capabilities).map(capability => (
                  <code key={capability}>{capability}</code>
                ))}
              </div>
              <div className={styles.rawKeyLabel} style={{ marginTop: '0.75rem' }}>
                {t('apiKeys.collectionAccess')}
              </div>
              <div className={styles.scope}>
                {createdKey.allowedCollectionKeys?.length
                  ? createdKey.allowedCollectionKeys.join(', ')
                  : t('apiKeys.allCollections')}
              </div>
              <div className={styles.rawKeyLabel} style={{ marginTop: '0.75rem' }}>
                {t('apiKeys.quota')}
              </div>
              <div>{createdKey.requestsPerMinute ?? t('apiKeys.defaultQuota')}</div>
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

// ==================== Edit Principal Policy Modal ====================

function EditPolicyModal({
  principal,
  onClose,
}: {
  principal: ApiPrincipalResponse;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [name, setName] = useState(principal.name);
  const [expiryDefaults] = useState(createExpiryDefaults);
  const [expiresAt, setExpiresAt] = useState(principal.expiresAt?.slice(0, 16) ?? '');
  const [restrictCollections, setRestrictCollections] = useState(
    Boolean(principal.allowedCollectionKeys?.length),
  );
  const [selectedCollectionKeys, setSelectedCollectionKeys] = useState<string[]>(
    principal.allowedCollectionKeys ?? [],
  );
  const [requestsPerMinute, setRequestsPerMinute] = useState(
    principal.requestsPerMinute?.toString() ?? '',
  );
  const [capabilities, setCapabilities] = useState<string[]>(
    normalizedCapabilities(principal.capabilities),
  );
  const queryClient = useQueryClient();
  const { showToast } = useToast();
  const collectionsQuery = useQuery({
    queryKey: ['collections', 'api-key-scope'],
    queryFn: () => collectionsApi.list({ page: 0, size: 200 }),
  });

  const updateMutation = useMutation({
    mutationFn: (data: ApiPrincipalPolicyUpdateRequest) =>
      apiKeysApi.updatePolicy(principal.principalId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['api-principals'] });
      showToast(t('apiKeys.policyUpdated'), 'success');
      onClose();
    },
    onError: (error) => {
      showToast(formatMutationError(t('apiKeys.policyUpdateError'), error), 'error');
    },
  });

  const toggleCollection = (collectionKey: string) => {
    setSelectedCollectionKeys(current =>
      current.includes(collectionKey)
        ? current.filter(key => key !== collectionKey)
        : [...current, collectionKey],
    );
  };

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!name.trim() || !expiresAt
        || (restrictCollections && selectedCollectionKeys.length === 0)) {
      return;
    }
    const policy: ApiPrincipalPolicyUpdateRequest = {
      expectedPolicyVersion: principal.policyVersion,
      name: name.trim(),
      expiresAt: expiresAt.length === 16 ? `${expiresAt}:00` : expiresAt,
      capabilities,
    };
    if (restrictCollections) {
      policy.allowedCollectionKeys = selectedCollectionKeys;
    }
    if (requestsPerMinute.trim()) {
      policy.requestsPerMinute = Number(requestsPerMinute);
    }
    updateMutation.mutate(policy);
  };

  return (
    <div className={styles.modal} onClick={(event) => event.target === event.currentTarget && onClose()}>
      <div className={styles.modalContent}>
        <div className={styles.modalHeader}>
          <h2 className={styles.modalTitle}>{t('apiKeys.editPolicy')}</h2>
          <button className={styles.modalClose} onClick={onClose}>✕</button>
        </div>
        <form onSubmit={handleSubmit}>
          <div className={styles.formGroup}>
            <label className={styles.label} htmlFor="policy-name">{t('apiKeys.name')} *</label>
            <input
              id="policy-name"
              className={styles.input}
              value={name}
              onChange={event => setName(event.target.value)}
              required
              maxLength={255}
            />
          </div>
          <div className={styles.formGroup}>
            <label className={styles.label} htmlFor="policy-expiry">
              {t('apiKeys.expiresAt')} {t('common.required')}
            </label>
            <input
              id="policy-expiry"
              type="datetime-local"
              className={styles.input}
              value={expiresAt}
              min={expiryDefaults.minimum}
              onChange={event => setExpiresAt(event.target.value)}
              required
            />
          </div>
          <div className={styles.formGroup}>
            <label className={styles.label} htmlFor="policy-quota">{t('apiKeys.quota')}</label>
            <input
              id="policy-quota"
              type="number"
              className={styles.input}
              min="1"
              max="1000000"
              value={requestsPerMinute}
              onChange={event => setRequestsPerMinute(event.target.value)}
              placeholder={t('apiKeys.quotaPlaceholder')}
            />
            <div className={styles.hint}>{t('apiKeys.quotaHint')}</div>
          </div>
          <CapabilitySelector
            capabilities={capabilities}
            onChange={setCapabilities}
            disabled={principal.role === 'ADMIN'}
          />
          <fieldset className={styles.scopeFieldset}>
            <legend className={styles.label}>{t('apiKeys.collectionAccess')}</legend>
            <label className={styles.scopeOption}>
              <input
                type="radio"
                name="policyCollectionScope"
                checked={!restrictCollections}
                onChange={() => setRestrictCollections(false)}
              />
              <span>
                <strong>{t('apiKeys.allCollections')}</strong>
                <small>{t('apiKeys.allCollectionsHint')}</small>
              </span>
            </label>
            <label className={styles.scopeOption}>
              <input
                type="radio"
                name="policyCollectionScope"
                checked={restrictCollections}
                onChange={() => setRestrictCollections(true)}
              />
              <span>
                <strong>{t('apiKeys.selectedCollections')}</strong>
                <small>{t('apiKeys.selectedCollectionsHint')}</small>
              </span>
            </label>
            {restrictCollections && (
              <div className={styles.collectionSelector}>
                {collectionsQuery.isPending ? (
                  <div className={styles.hint}>{t('common.loading')}</div>
                ) : collectionsQuery.isError ? (
                  <div className={styles.scopeError}>{t('apiKeys.collectionsLoadError')}</div>
                ) : !collectionsQuery.data?.data?.collections?.length ? (
                  <div className={styles.hint}>{t('collections.noCollections')}</div>
                ) : (
                  collectionsQuery.data.data.collections.map(collection => (
                    <label className={styles.collectionOption} key={collection.collectionKey}>
                      <input
                        type="checkbox"
                        checked={selectedCollectionKeys.includes(collection.collectionKey)}
                        onChange={() => toggleCollection(collection.collectionKey)}
                      />
                      <span>{collection.name}</span>
                      <code>{collection.collectionKey}</code>
                    </label>
                  ))
                )}
              </div>
            )}
          </fieldset>
          <div className={styles.modalActions}>
            <button type="button" className={styles.btnSecondary} onClick={onClose}>
              {t('common.cancel')}
            </button>
            <button
              type="submit"
              className={styles.btnPrimary}
              disabled={
                updateMutation.isPending
                || !name.trim()
                || !expiresAt
                || (restrictCollections && selectedCollectionKeys.length === 0)
              }
            >
              {updateMutation.isPending ? t('common.loading') : t('common.save')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ==================== Rotate Key Modal ====================

type RotationMode = 'staged' | 'immediate';

function RotateKeyModal({
  principal,
  onClose,
}: {
  principal: ApiPrincipalResponse;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [mode, setMode] = useState<RotationMode>('staged');
  const [overlapSeconds, setOverlapSeconds] = useState('900');
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const [preparedRotation, setPreparedRotation] =
    useState<ApiKeyRotationResponse | null>(null);
  const [immediateKey, setImmediateKey] =
    useState<ApiKeyCreatedResponse | null>(null);
  const { showToast } = useToast();
  const queryClient = useQueryClient();

  const prepareMutation = useMutation({
    mutationKey: ['prepare-api-key-rotation', principal.principalId],
    mutationFn: ({
      overlap,
      requestKey,
    }: {
      overlap: number | undefined;
      requestKey: string;
    }) => apiKeysApi.prepareRotation(
      principal.currentCredentialId!,
      overlap,
      requestKey,
    ),
    onSuccess: (response) => {
      setPreparedRotation(response.data);
      queryClient.invalidateQueries({ queryKey: ['api-principals'] });
    },
    onError: (error) => {
      showToast(
        formatMutationError(t('apiKeys.rotationPrepareError'), error),
        'error',
      );
    },
  });

  const immediateMutation = useMutation({
    mutationKey: ['immediate-api-key-rotation', principal.principalId],
    mutationFn: () => apiKeysApi.rotateKey(principal.currentCredentialId!),
    onSuccess: (response) => {
      setImmediateKey(response.data);
      queryClient.invalidateQueries({ queryKey: ['api-principals'] });
    },
    onError: (error) => {
      showToast(formatMutationError(t('apiKeys.rotateError'), error), 'error');
    },
  });

  const handleClose = () => {
    setPreparedRotation(null);
    setImmediateKey(null);
    onClose();
  };

  const copyRawKey = async () => {
    const rawKey = preparedRotation?.rawKey ?? immediateKey?.rawKey;
    if (!rawKey) return;
    await navigator.clipboard.writeText(rawKey);
    showToast(t('apiKeys.copied'), 'success');
  };

  const prepare = () => {
    const normalizedOverlap = overlapSeconds.trim()
      ? Number(overlapSeconds)
      : undefined;
    if (normalizedOverlap !== undefined
        && (!Number.isInteger(normalizedOverlap)
          || normalizedOverlap < 1
          || normalizedOverlap > 86400)) {
      return;
    }
    prepareMutation.mutate({
      overlap: normalizedOverlap,
      requestKey: idempotencyKey,
    });
  };

  const shownOnceSecret = preparedRotation?.rawKey ?? immediateKey?.rawKey;
  const completedResult = preparedRotation ?? immediateKey;

  return (
    <div className={styles.modal} onClick={(e) => e.target === e.currentTarget && handleClose()}>
      <div className={styles.modalContent}>
        <div className={styles.modalHeader}>
          <h2 className={styles.modalTitle}>{t('apiKeys.rotateKey')}</h2>
          <button className={styles.modalClose} onClick={handleClose}>✕</button>
        </div>

        {!completedResult ? (
          <div>
            <div className={styles.formGroup}>
              <label className={styles.label}>{t('apiKeys.credential')}</label>
              <div className={styles.mono} style={{ fontSize: '0.8rem' }}>
                {principal.currentCredentialId} (v{principal.currentCredentialVersion})
              </div>
            </div>
            <fieldset className={styles.scopeFieldset}>
              <legend className={styles.label}>{t('apiKeys.rotationMode')}</legend>
              <label className={styles.scopeOption}>
                <input
                  type="radio"
                  name="rotationMode"
                  checked={mode === 'staged'}
                  onChange={() => setMode('staged')}
                />
                <span>
                  <strong>{t('apiKeys.stagedRotation')}</strong>
                  <small>{t('apiKeys.stagedRotationHint')}</small>
                </span>
              </label>
              <label className={styles.scopeOption}>
                <input
                  type="radio"
                  name="rotationMode"
                  checked={mode === 'immediate'}
                  onChange={() => setMode('immediate')}
                />
                <span>
                  <strong>{t('apiKeys.immediateRotation')}</strong>
                  <small>{t('apiKeys.immediateRotationHint')}</small>
                </span>
              </label>
            </fieldset>
            {mode === 'staged' ? (
              <>
                <div className={styles.rotateInfo}>
                  {t('apiKeys.stagedRotationInfo')}
                </div>
                <div className={styles.formGroup}>
                  <label className={styles.label} htmlFor="rotation-overlap">
                    {t('apiKeys.overlapSeconds')}
                  </label>
                  <input
                    id="rotation-overlap"
                    type="number"
                    className={styles.input}
                    min="1"
                    max="86400"
                    step="1"
                    value={overlapSeconds}
                    onChange={event => setOverlapSeconds(event.target.value)}
                  />
                  <div className={styles.hint}>{t('apiKeys.overlapHint')}</div>
                </div>
              </>
            ) : (
              <div className={styles.dangerInfo}>
                {t('apiKeys.rotateInfo')}
              </div>
            )}
            <div className={styles.modalActions}>
              <button type="button" className={styles.btnSecondary} onClick={handleClose}>
                {t('common.cancel')}
              </button>
              {mode === 'staged' ? (
                <button
                  className={styles.btnPrimary}
                  onClick={prepare}
                  disabled={
                    prepareMutation.isPending
                    || !overlapSeconds.trim()
                    || Number(overlapSeconds) < 1
                    || Number(overlapSeconds) > 86400
                    || !Number.isInteger(Number(overlapSeconds))
                  }
                >
                  {prepareMutation.isPending
                    ? t('common.loading')
                    : t('apiKeys.prepareRotation')}
                </button>
              ) : (
                <button
                  className={styles.btnDanger}
                  onClick={() => immediateMutation.mutate()}
                  disabled={immediateMutation.isPending}
                >
                  {immediateMutation.isPending
                    ? t('common.loading')
                    : t('apiKeys.rotateImmediately')}
                </button>
              )}
            </div>
          </div>
        ) : (
          <div>
            <p style={{ marginBottom: '0.75rem', color: 'var(--color-text-secondary)', fontSize: '0.875rem' }}>
              {preparedRotation
                ? shownOnceSecret
                  ? t('apiKeys.rotationPrepared')
                  : t('apiKeys.rotationReplayRecovered')
                : t('apiKeys.keyRotated')}
            </p>
            <div className={styles.rawKeyBox}>
              {immediateKey && (
                <>
                  <div className={styles.rawKeyLabel}>{t('apiKeys.name')}</div>
                  <div className={styles.name}>{immediateKey.name}</div>
                </>
              )}
              {preparedRotation && (
                <>
                  <div className={styles.rawKeyLabel}>{t('apiKeys.rotationId')}</div>
                  <div className={styles.mono}>{preparedRotation.rotationId}</div>
                </>
              )}
              <div className={styles.rawKeyLabel} style={{ marginTop: '0.75rem' }}>
                {t('apiKeys.keyId')}
              </div>
              <div className={styles.mono}>
                {preparedRotation?.keyId ?? immediateKey?.keyId}
              </div>
              {preparedRotation?.rotationExpiresAt && (
                <>
                  <div className={styles.rawKeyLabel} style={{ marginTop: '0.75rem' }}>
                    {t('apiKeys.overlapDeadline')}
                  </div>
                  <div>{formatDateTime(preparedRotation.rotationExpiresAt)}</div>
                </>
              )}
              {shownOnceSecret ? (
                <>
                  <div className={styles.rawKeyLabel} style={{ marginTop: '0.75rem' }}>
                    {t('apiKeys.rawKey')}
                  </div>
                  <div className={styles.rawKeyRow}>
                    <div className={styles.rawKey}>{shownOnceSecret}</div>
                    <button type="button" className={styles.copyBtn} onClick={copyRawKey}>
                      {t('apiKeys.copy')}
                    </button>
                  </div>
                  <div className={styles.warning}>
                    {preparedRotation
                      ? t('apiKeys.stagedSecretWarning')
                      : immediateKey?.warning}
                  </div>
                </>
              ) : (
                <div className={styles.recoveryInfo}>
                  {t('apiKeys.rotationReplayNoSecret')}
                </div>
              )}
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

function CapabilitySelector({
  capabilities,
  onChange,
  disabled = false,
}: {
  capabilities: string[];
  onChange: (capabilities: string[]) => void;
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  const readOnly = capabilities.length === 1 && capabilities[0] === 'RAG_READ';
  return (
    <fieldset className={styles.scopeFieldset}>
      <legend className={styles.label}>{t('apiKeys.capabilities')}</legend>
      <label className={styles.scopeOption}>
        <input
          type="radio"
          name="apiCapability"
          aria-label="RAG_READ"
          checked={readOnly}
          disabled={disabled}
          onChange={() => onChange(READ_ONLY_CAPABILITIES)}
        />
        <span>
          <strong>RAG_READ</strong>
          <small>{t('apiKeys.readOnlyHint')}</small>
        </span>
      </label>
      <label className={styles.scopeOption}>
        <input
          type="radio"
          name="apiCapability"
          aria-label="RAG_READ, RAG_WRITE"
          checked={!readOnly}
          disabled={disabled}
          onChange={() => onChange(FULL_CAPABILITIES)}
        />
        <span>
          <strong>RAG_READ, RAG_WRITE</strong>
          <small>{disabled ? t('apiKeys.adminCapabilitiesHint') : t('apiKeys.readWriteHint')}</small>
        </span>
      </label>
    </fieldset>
  );
}
