import React, { useEffect, useState } from 'react';
import { AlertTriangle, Check, Copy, X } from 'lucide-react';
import type { IssuedApiKeyResult } from '../domain/operatorContract';

export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger';

const TONE_CLASSES: Record<Tone, string> = {
  neutral: 'bg-[var(--muted)] text-[var(--muted-foreground)] border-[var(--border)]',
  primary: 'bg-[var(--primary-soft)] text-[var(--primary-soft-foreground)] border-[var(--primary)]/30',
  success: 'bg-[var(--success-soft)] text-[var(--success)] border-[var(--success)]/30',
  warning: 'bg-[var(--warning-soft)] text-[var(--warning)] border-[var(--warning)]/30',
  danger: 'bg-[var(--accent-soft)] text-[var(--destructive)] border-[var(--destructive)]/30',
};

export function Badge({
  tone = 'neutral',
  children,
  ...rest
}: React.HTMLAttributes<HTMLSpanElement> & { tone?: Tone }) {
  return (
    <span {...rest} className={`inline-flex items-center gap-1 rounded-[4px] border px-1.5 py-0.5 text-[11px] font-medium leading-4 ${TONE_CLASSES[tone]} ${rest.className ?? ''}`}>
      {children}
    </span>
  );
}

export function StatusDot({ tone }: { tone: Tone }) {
  const colors: Record<Tone, string> = {
    neutral: 'bg-[var(--muted-foreground)]',
    primary: 'bg-[var(--primary)]',
    success: 'bg-[var(--success)]',
    warning: 'bg-[var(--warning)]',
    danger: 'bg-[var(--destructive)]',
  };
  return <span className={`inline-block h-2 w-2 rounded-full ${colors[tone]}`} />;
}

export function Bar({ ratio }: { ratio: number }) {
  const clamped = Math.max(0, Math.min(100, Math.round(ratio * 100)));
  const color = ratio >= 1 ? 'bg-[var(--destructive)]' : ratio >= 0.8 ? 'bg-[var(--warning)]' : 'bg-[var(--primary)]';
  return (
    <div className="h-1.5 w-full overflow-hidden rounded-[2px] bg-[var(--muted)]">
      <div className={`h-full transition-all ${color}`} style={{ width: `${clamped}%` }} />
    </div>
  );
}

export function Button({
  variant = 'default',
  size = 'md',
  className = '',
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'default' | 'primary' | 'danger' | 'ghost';
  size?: 'sm' | 'md';
}) {
  const base = 'inline-flex items-center justify-center gap-1.5 rounded-[4px] font-medium transition-colors disabled:opacity-45 disabled:cursor-not-allowed cursor-pointer';
  const sizes = size === 'sm' ? 'px-2.5 py-1 text-xs' : 'px-3 py-1.5 text-xs';
  const variants = {
    default: 'border border-[var(--border-strong)] bg-[var(--card)] text-[var(--foreground)] hover:bg-[var(--muted)]',
    primary: 'border border-transparent bg-[var(--primary)] text-[var(--primary-foreground)] hover:bg-[var(--primary-hover)]',
    danger: 'border border-transparent bg-[var(--destructive)] text-[var(--destructive-foreground)] hover:opacity-90',
    ghost: 'border border-transparent bg-transparent text-[var(--muted-foreground)] hover:bg-[var(--muted)] hover:text-[var(--foreground)]',
  };
  return <button className={`${base} ${sizes} ${variants[variant]} ${className}`} {...props} />;
}

export function PageHeader({
  title,
  description,
  meta,
  actions,
}: {
  title: string;
  description: string;
  meta?: React.ReactNode;
  actions?: React.ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-4 pb-4">
      <div className="min-w-0">
        <h1 className="text-[20px] font-semibold leading-7 text-[var(--foreground)]">{title}</h1>
        <p className="mt-1 max-w-[760px] text-[13px] leading-5 text-[var(--muted-foreground)]">{description}</p>
        {meta && <div className="mt-2 flex flex-wrap items-center gap-2">{meta}</div>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

export function OverviewBand({ items }: { items: Array<{ label: string; value: string; note: string; tone?: Tone }> }) {
  return (
    <div className="overview-band mb-4">
      {items.map((item) => (
        <div key={item.label} className="overview-cell">
          <div className="text-[11px] font-medium text-[var(--muted-foreground)]">{item.label}</div>
          <div
            className={`mt-2 font-mono text-[24px] leading-none tabular-nums ${
              item.tone === 'danger'
                ? 'text-[var(--destructive)]'
                : item.tone === 'warning'
                  ? 'text-[var(--warning)]'
                  : 'text-[var(--foreground)]'
            }`}
          >
            {item.value}
          </div>
          <div className="mt-2 text-[12px] leading-4 text-[var(--muted-foreground)]">{item.note}</div>
        </div>
      ))}
    </div>
  );
}

export function AttentionStrip({
  tone = 'warning',
  message,
  action,
}: {
  tone?: 'warning' | 'danger';
  message: string;
  action?: React.ReactNode;
}) {
  const cls =
    tone === 'danger'
      ? 'border-[var(--destructive)]/40 bg-[var(--accent-soft)] text-[var(--destructive)]'
      : 'border-[var(--warning)]/40 bg-[var(--warning-soft)] text-[var(--warning)]';
  return (
    <div className={`mb-4 flex flex-wrap items-center justify-between gap-3 rounded-[4px] border px-3.5 py-2.5 text-xs ${cls}`}>
      <div className="flex items-center gap-2">
        <AlertTriangle className="h-4 w-4 shrink-0" />
        <span>{message}</span>
      </div>
      {action}
    </div>
  );
}

export function Drawer({
  open,
  title,
  subtitle,
  onClose,
  children,
}: {
  open: boolean;
  title: string;
  subtitle?: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-40 flex justify-end bg-black/35" onClick={onClose}>
      <div
        role="dialog"
        aria-label={title}
        className="flex h-full w-full max-w-[540px] flex-col border-l border-[var(--border)] bg-[var(--card)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-[var(--border)] px-5 py-4">
          <div className="min-w-0">
            <h2 className="truncate text-base font-semibold text-[var(--foreground)]">{title}</h2>
            {subtitle && <p className="mt-0.5 font-mono text-xs text-[var(--muted-foreground)]">{subtitle}</p>}
          </div>
          <Button variant="ghost" size="sm" onClick={onClose} aria-label="关闭抽屉">
            <X className="h-4 w-4" />
          </Button>
        </div>
        <div className="flex-1 overflow-y-auto px-5 py-4">{children}</div>
      </div>
    </div>
  );
}

export function ConfirmModal({
  open,
  title,
  description,
  confirmTarget,
  confirmLabel,
  danger = true,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  title: string;
  description: string;
  confirmTarget?: string;
  confirmLabel: string;
  danger?: boolean;
  onCancel: () => void;
  onConfirm: (reason: string) => Promise<void> | void;
}) {
  const [typed, setTyped] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (open) {
      setTyped('');
      setReason('');
      setBusy(false);
    }
  }, [open]);

  if (!open) return null;
  const targetOk = !confirmTarget || typed.trim() === confirmTarget;
  const canSubmit = targetOk && reason.trim().length >= 2 && !busy;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4" onClick={onCancel}>
      <div
        role="dialog"
        aria-modal="true"
        className="w-full max-w-[460px] rounded-[8px] border border-[var(--border)] bg-[var(--card)] p-5"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-base font-semibold text-[var(--foreground)]">{title}</h3>
        <p className="mt-1.5 text-xs leading-5 text-[var(--muted-foreground)]">{description}</p>
        {confirmTarget && (
          <div className="mt-4">
            <label className="block text-xs font-medium text-[var(--foreground)]">
              请输入目标标识 <code className="rounded bg-[var(--muted)] px-1 py-0.5 font-mono">{confirmTarget}</code> 以确认
            </label>
            <input
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              placeholder={confirmTarget}
              className="mt-1.5 w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1.5 font-mono text-xs text-[var(--foreground)]"
            />
          </div>
        )}
        <div className="mt-3">
          <label className="block text-xs font-medium text-[var(--foreground)]">操作原因（写入平台审计流水，必填）</label>
          <input
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="例如：工单 #INC-4821 客户申请暂停服务"
            className="mt-1.5 w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1.5 text-xs text-[var(--foreground)]"
          />
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <Button onClick={onCancel} disabled={busy}>取消</Button>
          <Button
            variant={danger ? 'danger' : 'primary'}
            disabled={!canSubmit}
            onClick={async () => {
              setBusy(true);
              try {
                await onConfirm(reason.trim());
              } finally {
                setBusy(false);
              }
            }}
          >
            {busy ? '执行中…' : confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}

export function OneTimeKeyModal({
  issued,
  onClose,
}: {
  issued: IssuedApiKeyResult | null;
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    if (!issued) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [issued, onClose]);
  if (!issued) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4">
      <div className="w-full max-w-[500px] rounded-[8px] border border-[var(--border)] bg-[var(--card)] p-5">
        <h3 className="text-base font-semibold text-[var(--foreground)]">API Key 已签发（仅本次展示明文）</h3>
        <p className="mt-1.5 text-xs leading-5 text-[var(--warning)]">
          请立即复制并交付给租户 <code className="font-mono">{issued.tenantId}</code>。关闭此弹窗后系统不再存储或展示明文密钥。
        </p>
        <div className="mt-3 rounded-[4px] border border-[var(--border-strong)] bg-[var(--muted)] p-3 font-mono text-xs break-all text-[var(--foreground)]">
          {issued.rawKeyOnce}
        </div>
        <div className="mt-2 flex flex-wrap gap-4 text-[11px] text-[var(--muted-foreground)] font-mono">
          <span>访问主体：{issued.principalId}</span>
          <span>keyId: {issued.keyId}</span>
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <Button
            onClick={() => {
              navigator.clipboard?.writeText(issued.rawKeyOnce).catch(() => undefined);
              setCopied(true);
              setTimeout(() => setCopied(false), 1500);
            }}
          >
            {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
            {copied ? '已复制' : '复制明文 Key'}
          </Button>
          <Button variant="primary" onClick={onClose}>我已保存，关闭</Button>
        </div>
      </div>
    </div>
  );
}

export function ToastViewport({ items, onDismiss }: { items: Array<{ id: number; tone: Tone; text: string }>; onDismiss: (id: number) => void }) {
  if (items.length === 0) return null;
  return (
    <div className="fixed right-4 bottom-4 z-50 flex max-w-[360px] flex-col gap-2">
      {items.map((item) => (
        <div
          key={item.id}
          className={`flex items-center justify-between gap-3 rounded-[4px] border px-3 py-2 text-xs shadow-sm ${TONE_CLASSES[item.tone]}`}
        >
          <span>{item.text}</span>
          <button onClick={() => onDismiss(item.id)} className="opacity-70 hover:opacity-100 cursor-pointer" aria-label="关闭通知">
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      ))}
    </div>
  );
}

export function formatNumber(n: number | null | undefined): string {
  if (n === null || n === undefined) return '无上限';
  return n.toLocaleString('zh-CN');
}

export function formatShortTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  return iso.replace('T', ' ').replace('Z', '').slice(0, 16);
}

/**
 * 统一弹窗原语：支持 Esc 关闭、遮罩点击关闭与打开时焦点管理。
 * 所有业务弹窗都应使用它，避免每页各自实现一套键盘交互。
 */
export function Modal({
  open,
  title,
  description,
  onClose,
  children,
  footer,
  testId,
}: {
  open: boolean;
  title: string;
  description?: React.ReactNode;
  onClose: () => void;
  children: React.ReactNode;
  footer?: React.ReactNode;
  testId?: string;
}) {
  const panelRef = React.useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onClose();
      }
    };
    window.addEventListener('keydown', onKey, true);
    const previous = document.activeElement as HTMLElement | null;
    // 焦点进入弹窗第一个可交互元素，保证 Esc / Tab 都在弹窗内闭环。
    const focusTimer = window.setTimeout(() => {
      const target = panelRef.current?.querySelector<HTMLElement>(
        'input, select, textarea, button:not([aria-label="关闭弹窗"])',
      );
      (target ?? panelRef.current)?.focus();
    }, 0);
    return () => {
      window.clearTimeout(focusTimer);
      window.removeEventListener('keydown', onKey, true);
      previous?.focus();
    };
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4"
      onClick={onClose}
      data-testid={testId}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        className="w-full max-w-[460px] rounded-[8px] border border-[var(--border)] bg-[var(--card)] p-5 text-xs outline-none"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h3 className="text-base font-semibold text-[var(--foreground)]">{title}</h3>
            {description && (
              <div className="mt-1.5 leading-5 text-[var(--muted-foreground)]">{description}</div>
            )}
          </div>
          <Button variant="ghost" size="sm" onClick={onClose} aria-label="关闭弹窗">
            <X className="h-4 w-4" />
          </Button>
        </div>
        <div className="mt-4">{children}</div>
        {footer && <div className="mt-5 flex justify-end gap-2">{footer}</div>}
      </div>
    </div>
  );
}

/** 加载骨架：让运营人员明确区分「正在加载」与「筛选为空」。 */
export function SkeletonTable({ rows = 6, columns = 5 }: { rows?: number; columns?: number }) {
  return (
    <div className="space-y-2 p-3.5" aria-label="加载中" data-testid="skeleton-table">
      {Array.from({ length: rows }, (_, row) => (
        <div key={row} className="flex gap-3">
          {Array.from({ length: columns }, (_, col) => (
            <div
              key={col}
              className="h-7 flex-1 animate-pulse rounded-[4px] bg-[var(--muted)]"
            />
          ))}
        </div>
      ))}
    </div>
  );
}

/** 空态：支持一键清除筛选。 */
export function EmptyState({
  title,
  detail,
  action,
}: {
  title: string;
  detail?: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex min-h-48 flex-col items-center justify-center px-6 py-10 text-center" data-testid="empty-state">
      <div aria-hidden className="mb-3 grid h-9 w-9 place-items-center rounded-[4px] border border-[var(--border)] bg-[var(--muted)] text-[var(--muted-foreground)]">
        —
      </div>
      <p className="text-sm font-medium text-[var(--foreground)]">{title}</p>
      {detail && <p className="mt-1.5 max-w-md text-xs leading-5 text-[var(--muted-foreground)]">{detail}</p>}
      {action && <div className="mt-3">{action}</div>}
    </div>
  );
}

/** 排序图标：三态循环提示（未排序 / 升序 / 降序）。 */
export function SortIcon({ active, direction }: { active: boolean; direction: 'asc' | 'desc' }) {
  return <span aria-hidden>{!active ? '↕' : direction === 'asc' ? '↑' : '↓'}</span>;
}
