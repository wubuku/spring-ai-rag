import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Outlet, NavLink, useLocation } from 'react-router-dom';
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
  const { logout } = useApiKeyAuth();
  const location = useLocation();
  const mainRef = useRef<HTMLElement>(null);
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

  useLayoutEffect(() => {
    const main = mainRef.current;
    if (!main) return;

    const resetScroll = () => {
      main.scrollTop = 0;
      main.scrollLeft = 0;
      document.documentElement.scrollTop = 0;
      document.documentElement.scrollLeft = 0;
      document.body.scrollTop = 0;
      document.body.scrollLeft = 0;
    };
    resetScroll();
    const frame = window.requestAnimationFrame(resetScroll);
    return () => window.cancelAnimationFrame(frame);
  }, [location.pathname]);

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

      <aside className={`${styles.sidebar} ${isMobile && sidebarOpen ? styles.sidebarOpen : ''}`}>
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
              onClick={() => setSidebarOpen(true)}
            >
              <Menu size={18} aria-hidden="true" />
            </IconButton>
          </Tooltip>
        )}
        <main ref={mainRef} className={styles.main}>
          <ErrorBoundary>
            <Outlet />
          </ErrorBoundary>
        </main>
      </div>
    </div>
  );
}
