import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Outlet, NavLink, useLocation, useNavigationType } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Bell,
  ChartColumn,
  CircleCheck,
  Dna,
  FileText,
  FlaskConical,
  KeyRound,
  LayoutDashboard,
  Library,
  LogOut,
  Menu,
  MessageSquare,
  Package,
  Search,
  Settings,
  X,
  type LucideIcon,
} from 'lucide-react';
import { ErrorBoundary } from '../ErrorBoundary';
import { ThemeToggle } from '../ThemeToggle';
import { UpdateAvailableBanner } from '../UpdateAvailableBanner/UpdateAvailableBanner';
import { IconButton } from '../ui/IconButton';
import { Tooltip } from '../ui/Tooltip';
import { useApiKeyAuth } from '../../auth/ApiKeyAuthContext';
import {
  rememberRoute,
  rememberedRoute,
  type TopLevelRoute,
} from '../../utils/workspaceState';
import styles from './Layout.module.css';

// Navigation icons come from one tree-shaken icon set. Emoji rendered at
// different metrics and weights across platforms, which made the sidebar
// unstable; every icon is decorative and the link text carries the name.
const NAV_ITEMS: { to: string; labelKey: string; Icon: LucideIcon }[] = [
  { to: '/dashboard', labelKey: 'nav.dashboard', Icon: LayoutDashboard },
  { to: '/documents', labelKey: 'nav.documents', Icon: FileText },
  { to: '/collections', labelKey: 'nav.collections', Icon: Library },
  { to: '/chat', labelKey: 'nav.chat', Icon: MessageSquare },
  { to: '/search', labelKey: 'nav.search', Icon: Search },
  { to: '/metrics', labelKey: 'nav.metrics', Icon: ChartColumn },
  { to: '/evaluation', labelKey: 'nav.evaluation', Icon: CircleCheck },
  { to: '/embeddings', labelKey: 'nav.embeddings', Icon: Dna },
  { to: '/alerts', labelKey: 'nav.alerts', Icon: Bell },
  { to: '/abtest', labelKey: 'nav.abtest', Icon: FlaskConical },
  { to: '/api-keys', labelKey: 'nav.apiKeys', Icon: KeyRound },
  { to: '/files', labelKey: 'nav.files', Icon: Package },
  { to: '/settings', labelKey: 'nav.settings', Icon: Settings },
];

const MOBILE_BREAKPOINT = 768;

export function Layout() {
  const { t } = useTranslation();
  const { identity, logout } = useApiKeyAuth();
  const location = useLocation();
  const navigationType = useNavigationType();
  const mainRef = useRef<HTMLElement>(null);
  // 每条浏览历史记录（location.key）各自记住滚动位置。前进/后退（POP）
  // 回到一条旧记录时恢复；普通跳转（PUSH/REPLACE）回到顶部。
  const activeHistoryKeyRef = useRef(location.key);
  const scrollPositionsRef = useRef<Map<string, number>>(new Map());
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [isMobile, setIsMobile] = useState(window.innerWidth < MOBILE_BREAKPOINT);
  // Bumped after each route memory write so nav link hrefs re-read the
  // freshly stored query instead of rendering a stale snapshot.
  const [routeMemoryVersion, setRouteMemoryVersion] = useState(0);

  useEffect(() => {
    const handleResize = () => {
      const mobile = window.innerWidth < MOBILE_BREAKPOINT;
      setIsMobile(mobile);
      if (!mobile) {
        setSidebarOpen(false); // Close sidebar when resizing to desktop
      }
    };

    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  useEffect(() => {
    rememberRoute(location.pathname, location.search);
    setRouteMemoryVersion(version => version + 1);
  }, [location.pathname, location.search]);

  // 记录当前历史条目的滚动位置。滚动容器是 <main>；window 只在页面
  // 本身可滚动时才有值，两处都记以防不同布局下滚动条落在不同元素上。
  useEffect(() => {
    const main = mainRef.current;
    if (!main) return;
    const record = () => {
      scrollPositionsRef.current.set(activeHistoryKeyRef.current, main.scrollTop);
    };
    main.addEventListener('scroll', record, { passive: true });
    return () => main.removeEventListener('scroll', record);
  }, []);

  useLayoutEffect(() => {
    const main = mainRef.current;
    if (!main) return;

    const restore = navigationType === 'POP'
      ? scrollPositionsRef.current.get(location.key) ?? 0
      : 0;

    const apply = () => {
      main.scrollTop = restore;
      main.scrollLeft = 0;
      document.documentElement.scrollTop = 0;
      document.documentElement.scrollLeft = 0;
      document.body.scrollTop = 0;
      document.body.scrollLeft = 0;
    };
    activeHistoryKeyRef.current = location.key;
    apply();
    // 后退/前进时页面数据可能来自缓存并同步渲染，也可能要等一拍才撑开高度；
    // 再补一帧让 `scrollTop = restore` 不被较小的瞬时高度截断。
    const frame = window.requestAnimationFrame(apply);
    return () => window.cancelAnimationFrame(frame);
  }, [location.key, location.pathname, location.search, navigationType]);

  // Close sidebar when navigating on mobile
  const handleNavClick = () => {
    if (isMobile) {
      setSidebarOpen(false);
    }
  };

  const rememberedRouteFor = (route: string) => {
    // routeMemoryVersion 仅作为依赖，确保 sessionStorage 更新后重算 href。
    void routeMemoryVersion;
    return rememberedRoute(route as TopLevelRoute);
  };
  return (
    <div className={styles.layout}>
      {/* Mobile overlay */}
      {isMobile && sidebarOpen && (
        // 纯点击遮罩：关闭侧栏的可键盘路径是侧栏内的 IconButton，
        // 这个 div 只是鼠标用户的点外面快捷方式，对读屏没有任何信息量。
        <div className={styles.overlay} onClick={() => setSidebarOpen(false)} aria-hidden="true" />
      )}

      <aside
        id="app-sidebar"
        inert={isMobile && !sidebarOpen}
        className={`${styles.sidebar} ${isMobile && sidebarOpen ? styles.sidebarOpen : ''}`}
      >
        <div className={styles.sidebarHeader}>
          <div className={styles.logo}>spring-ai-rag</div>
          {isMobile && (
            <IconButton
              label={t('nav.closeSidebar', 'Close sidebar')}
              variant="ghost"
              onClick={() => setSidebarOpen(false)}
            >
              <X size={18} aria-hidden="true" />
            </IconButton>
          )}
        </div>
        <div className={styles.themeToggle}>
          <ThemeToggle />
        </div>
        <nav className={styles.nav}>
          {NAV_ITEMS.map(({ to, labelKey, Icon }) => (
            <NavLink
              key={to}
              to={rememberedRouteFor(to)}
              className={({ isActive }) => `${styles.navItem} ${isActive ? styles.active : ''}`}
              onClick={handleNavClick}
            >
              <Icon className={styles.icon} size={18} aria-hidden="true" />
              {t(labelKey)}
            </NavLink>
          ))}
        </nav>
        {identity && (
          // WP9：只读展示当前凭据绑定的租户、运行模式与租户状态。
          // 不做租户切换器 / 邀请码（规划 §6 WP9 明确排除）；缺省值保证旧后端响应不炸。
          <div className={styles.tenantInfo} data-testid="tenant-info">
            {t('tenant.readOnly', {
              tenantId: identity.tenantId ?? 'default',
              mode: identity.tenantMode ?? 'SINGLE',
              status: identity.tenantStatus ?? 'ACTIVE',
            })}
          </div>
        )}
        <div className={styles.consoleActions}>
          <button type="button" className={styles.logoutBtn} onClick={logout}>
            <LogOut size={16} aria-hidden="true" />
            {t('unlock.logout')}
          </button>
        </div>
      </aside>

      <div className={styles.mainWrapper}>
        {isMobile && (
          <Tooltip content={t('nav.openSidebar', 'Open sidebar')} placement="bottom">
            <IconButton
              className={styles.menuBtn}
              label={t('nav.openSidebar', 'Open sidebar')}
              variant="secondary"
              aria-expanded={sidebarOpen}
              aria-controls="app-sidebar"
              onClick={() => setSidebarOpen(true)}
            >
              <Menu size={18} aria-hidden="true" />
            </IconButton>
          </Tooltip>
        )}
        <UpdateAvailableBanner />
        <main ref={mainRef} className={styles.main}>
          <ErrorBoundary resetKey={location.pathname}>
            <Outlet />
          </ErrorBoundary>
        </main>
      </div>
    </div>
  );
}
