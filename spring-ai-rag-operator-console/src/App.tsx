import { useCallback, useEffect, useState } from 'react';
import {
  Activity,
  Building2,
  ChevronDown,
  FileClock,
  Gauge,
  KeyRound,
  Moon,
  PanelLeft,
  Search,
  ShieldCheck,
  Sun,
  Wrench,
} from 'lucide-react';
import { operatorClient } from './domain/clientFactory';
import { TENANT_MODE_LABEL, ACCESS_PRINCIPAL_LABEL } from './domain/glossary';
import type { TenantSummary } from './domain/operatorContract';
import { CommandPalette, type NavRoute } from './components/CommandPalette';
import { DevPanel } from './components/DevPanel';
import { TenantDrawer } from './components/TenantDrawer';
import { TenantsPage } from './pages/TenantsPage';
import { CredentialsPage } from './pages/CredentialsPage';
import { QuotasPage } from './pages/QuotasPage';
import { KnowledgeHealthPage } from './pages/KnowledgeHealthPage';
import { CompliancePage } from './pages/CompliancePage';
import { AuditSupportPage } from './pages/AuditSupportPage';
import { AttentionStrip, Badge, Button, StatusDot, ToastViewport, type Tone } from './ui/primitives';

const NAV_ITEMS: Array<{ id: NavRoute; label: string; icon: typeof Building2 }> = [
  { id: 'tenants', label: '租户目录', icon: Building2 },
  { id: 'credentials', label: '凭据治理', icon: KeyRound },
  { id: 'quotas', label: '配额与用量', icon: Gauge },
  { id: 'knowledge-health', label: '知识库健康', icon: Activity },
  { id: 'compliance', label: '模式与合规', icon: ShieldCheck },
  { id: 'audit-support', label: '审计与支持', icon: FileClock },
];

export function App() {
  const [route, setRoute] = useState<NavRoute>('tenants');
  const [theme, setTheme] = useState<'light' | 'dark'>('light');
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [selectedTenantId, setSelectedTenantId] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  const [tenantsCache, setTenantsCache] = useState<TenantSummary[]>([]);
  const [pageError, setPageError] = useState<string | null>(null);
  const [toasts, setToasts] = useState<Array<{ id: number; tone: Tone; text: string }>>([]);
  const [devOpen, setDevOpen] = useState(false);

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
  }, [theme]);

  const notify = useCallback((tone: Tone, text: string) => {
    const id = Date.now() + Math.floor(Math.random() * 1000);
    setToasts((prev) => [...prev, { id, tone, text }]);
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 3600);
  }, []);

  const triggerRefresh = useCallback(() => {
    setPageError(null);
    setRefreshTick((t) => t + 1);
  }, []);

  const handleError = useCallback((err: unknown) => {
    setPageError(err instanceof Error ? err.message : '请求失败，请重试');
  }, []);

  useEffect(() => {
    operatorClient
      .listTenants({ includeDeleted: true })
      .then((rows) => {
        setTenantsCache(rows);
        setPageError(null);
      })
      .catch(handleError);
  }, [refreshTick, handleError]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((v) => !v);
        return;
      }
      const tag = (e.target as HTMLElement | null)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
      if (e.key === '[') {
        setSidebarOpen((v) => !v);
      } else if (e.key === 'Escape' && paletteOpen) {
        setPaletteOpen(false);
      } else if (e.key === 'Escape' && devOpen) {
        setDevOpen(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [paletteOpen, devOpen]);

  return (
    <div className="flex h-full min-h-screen flex-col bg-[var(--background)] text-[var(--foreground)]">
      {/* Top Bar (48px) */}
      <header className="relative z-30 flex h-12 shrink-0 items-center justify-between gap-3 border-b border-[var(--border)] bg-[var(--card)] px-3.5">
        <div className="flex min-w-0 items-center gap-2.5">
          <Button variant="ghost" size="sm" onClick={() => setSidebarOpen((v) => !v)} aria-label="切换导航栏">
            <PanelLeft className="h-4 w-4" />
          </Button>
          <span className="truncate text-sm font-semibold">Spring AI RAG · 运营方控制台</span>
          <span className="hidden items-center gap-1.5 text-[11px] text-[var(--muted-foreground)] sm:inline-flex" data-testid="platform-health">
            <StatusDot tone="success" />
            平台正常
          </span>
          <Badge tone="neutral" data-testid="tenant-mode-badge" title="当前运行模式">
            {TENANT_MODE_LABEL.SINGLE}
          </Badge>
          {operatorClient.mode === 'mock' ? (
            <Badge tone="warning" >
              <span data-testid="mock-honesty-badge">原型 · mock · 未接真</span>
            </Badge>
          ) : (
            <Badge tone="success">已接真实控制面</Badge>
          )}
        </div>
        <div className="flex items-center gap-2">
          <Button size="sm" onClick={() => setPaletteOpen(true)} aria-label="打开命令面板">
            <Search className="h-3.5 w-3.5" />
            <span className="hidden sm:inline">命令面板</span>
            <kbd className="hidden sm:inline rounded border border-[var(--border)] px-1 font-mono text-[10px]">⌘K</kbd>
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setTheme((t) => (t === 'light' ? 'dark' : 'light'))}
            aria-label="切换亮暗主题"
          >
            {theme === 'light' ? <Moon className="h-4 w-4" /> : <Sun className="h-4 w-4" />}
          </Button>
          {operatorClient.mode === 'mock' && (
            <button
              type="button"
              data-testid="dev-panel-toggle"
              aria-label="原型调试开关"
              aria-expanded={devOpen}
              onClick={() => setDevOpen((v) => !v)}
              className={`inline-flex h-8 w-8 items-center justify-center rounded-[4px] text-[var(--muted-foreground)] hover:bg-[var(--muted)] hover:text-[var(--foreground)] cursor-pointer ${devOpen ? 'bg-[var(--muted)] text-[var(--foreground)]' : ''}`}
            >
              <Wrench className="h-4 w-4" />
            </button>
          )}
          <div
            className="hidden items-center gap-2 border-l border-[var(--border)] pl-3 sm:flex"
            title="运营署名仅用于展示；接真后由控制面鉴权会话解析"
            data-testid="operator-signature"
          >
            <span aria-hidden className="grid h-7 w-7 place-items-center rounded-[4px] bg-[var(--muted)] text-xs font-semibold text-[var(--foreground)]">运</span>
            <span className="max-w-[10rem] truncate text-xs text-[var(--foreground)]">{ACCESS_PRINCIPAL_LABEL} · environment-root</span>
            <ChevronDown aria-hidden className="h-3.5 w-3.5 text-[var(--muted-foreground)]" />
          </div>
        </div>
        {operatorClient.mode === 'mock' && devOpen && (
          <DevPanel onRefresh={triggerRefresh} />
        )}
      </header>

      <div className="flex min-h-0 flex-1 overflow-hidden">
        {/* Sidebar (224px) */}
        {sidebarOpen && (
          <aside className="hidden w-[224px] shrink-0 flex-col border-r border-[var(--border)] bg-[var(--card)] p-2.5 md:flex">
            <nav className="space-y-1" aria-label="运营方控制台导航">
              {NAV_ITEMS.map((item) => {
                const Icon = item.icon;
                const active = route === item.id;
                return (
                  <button
                    key={item.id}
                    data-testid={`nav-${item.id}`}
                    onClick={() => {
                      setPageError(null);
                      setRoute(item.id);
                    }}
                    className={`flex w-full items-center gap-2.5 rounded-[4px] px-2.5 py-2 text-left text-xs font-medium transition-colors cursor-pointer ${
                      active
                        ? 'bg-[var(--primary-soft)] text-[var(--primary-soft-foreground)]'
                        : 'text-[var(--muted-foreground)] hover:bg-[var(--muted)] hover:text-[var(--foreground)]'
                    }`}
                  >
                    <Icon className="h-4 w-4 shrink-0" />
                    <span>{item.label}</span>
                  </button>
                );
              })}
            </nav>
            <div className="mt-auto border-t border-[var(--border)] pt-3 text-[11px] leading-4 text-[var(--muted-foreground)]">
              <div className="font-semibold text-[var(--foreground)]">边界守则</div>
              <div className="mt-1">仅租户粒度管理；不浏览文档正文、分块或聊天明细。</div>
            </div>
          </aside>
        )}

        {/* Main Content */}
        <main className="flex-1 overflow-y-auto p-4 md:p-6">
          {/* Mobile Compact Nav */}
          <div className="mb-4 flex gap-1.5 overflow-x-auto pb-1 md:hidden">
            {NAV_ITEMS.map((item) => (
              <button
                key={item.id}
                data-testid={`mobile-nav-${item.id}`}
                onClick={() => {
                  setPageError(null);
                  setRoute(item.id);
                }}
                className={`shrink-0 rounded-[4px] border px-2.5 py-1 text-xs font-medium ${
                  route === item.id
                    ? 'border-[var(--primary)] bg-[var(--primary-soft)] text-[var(--primary-soft-foreground)]'
                    : 'border-[var(--border)] bg-[var(--card)] text-[var(--muted-foreground)]'
                }`}
              >
                {item.label}
              </button>
            ))}
          </div>

          <div className="mx-auto max-w-[1280px]">
            {pageError && (
              <AttentionStrip
                tone="danger"
                message={pageError}
                action={
                  <Button size="sm" onClick={triggerRefresh}>
                    重试
                  </Button>
                }
              />
            )}

            {route === 'tenants' && (
              <TenantsPage
                refreshTick={refreshTick}
                onSelectTenant={setSelectedTenantId}
                onMutated={triggerRefresh}
                onNotify={notify}
                onError={handleError}
              />
            )}
            {route === 'credentials' && (
              <CredentialsPage
                refreshTick={refreshTick}
                tenants={tenantsCache}
                onMutated={triggerRefresh}
                onNotify={notify}
                onError={handleError}
              />
            )}
            {route === 'quotas' && (
              <QuotasPage
                refreshTick={refreshTick}
                onMutated={triggerRefresh}
                onNotify={notify}
                onError={handleError}
              />
            )}
            {route === 'knowledge-health' && (
              <KnowledgeHealthPage refreshTick={refreshTick} onError={handleError} />
            )}
            {route === 'compliance' && (
              <CompliancePage refreshTick={refreshTick} onError={handleError} />
            )}
            {route === 'audit-support' && (
              <AuditSupportPage
                refreshTick={refreshTick}
                tenants={tenantsCache}
                onMutated={triggerRefresh}
                onNotify={notify}
                onError={handleError}
              />
            )}
          </div>
        </main>
      </div>

      <TenantDrawer
        tenantId={selectedTenantId}
        onClose={() => setSelectedTenantId(null)}
        onMutated={triggerRefresh}
        onNotify={notify}
      />

      <CommandPalette
        open={paletteOpen}
        tenants={tenantsCache}
        onClose={() => setPaletteOpen(false)}
        onSelectRoute={setRoute}
        onSelectTenant={setSelectedTenantId}
      />

      <ToastViewport
        items={toasts}
        onDismiss={(id) => setToasts((prev) => prev.filter((t) => t.id !== id))}
      />
    </div>
  );
}
