import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Outlet, NavLink, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ErrorBoundary } from '../ErrorBoundary';
import { ThemeToggle } from '../ThemeToggle';
import { useApiKeyAuth } from '../../auth/ApiKeyAuthContext';
import {
  rememberRoute,
  rememberedRoute,
  type TopLevelRoute,
} from '../../utils/workspaceState';
import styles from './Layout.module.css';

const NAV_ITEMS = [
  { to: '/dashboard', labelKey: 'nav.dashboard', icon: '📊' },
  { to: '/documents', labelKey: 'nav.documents', icon: '📄' },
  { to: '/collections', labelKey: 'nav.collections', icon: '📚' },
  { to: '/chat', labelKey: 'nav.chat', icon: '💬' },
  { to: '/search', labelKey: 'nav.search', icon: '🔍' },
  { to: '/metrics', labelKey: 'nav.metrics', icon: '📈' },
  { to: '/evaluation', labelKey: 'nav.evaluation', icon: '✅' },
  { to: '/embeddings', labelKey: 'nav.embeddings', icon: '🧬' },
  { to: '/alerts', labelKey: 'nav.alerts', icon: '🔔' },
  { to: '/abtest', labelKey: 'nav.abtest', icon: '🧪' },
  { to: '/api-keys', labelKey: 'nav.apiKeys', icon: '🔑' },
  { to: '/files', labelKey: 'nav.files', icon: '📦' },
  { to: '/settings', labelKey: 'nav.settings', icon: '⚙️' },
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
        <div className={styles.overlay} onClick={() => setSidebarOpen(false)} />
      )}

      <aside className={`${styles.sidebar} ${isMobile && sidebarOpen ? styles.sidebarOpen : ''}`}>
        <div className={styles.sidebarHeader}>
          <div className={styles.logo}>spring-ai-rag</div>
          {isMobile && (
            <button
              className={styles.closeBtn}
              onClick={() => setSidebarOpen(false)}
              aria-label="Close sidebar"
            >
              ✕
            </button>
          )}
        </div>
        <div className={styles.themeToggle}>
          <ThemeToggle />
        </div>
        <nav className={styles.nav}>
          {NAV_ITEMS.map(item => (
            <NavLink
              key={item.to}
              to={rememberedRouteFor(item.to)}
              className={({ isActive }) => `${styles.navItem} ${isActive ? styles.active : ''}`}
              onClick={handleNavClick}
            >
              <span className={styles.icon}>{item.icon}</span>
              {t(item.labelKey)}
            </NavLink>
          ))}
        </nav>
        <div className={styles.consoleActions}>
          <button type="button" className={styles.logoutBtn} onClick={logout}>
            {t('unlock.logout')}
          </button>
        </div>
      </aside>

      <div className={styles.mainWrapper}>
        {isMobile && (
          <button
            className={styles.menuBtn}
            onClick={() => setSidebarOpen(true)}
            aria-label="Open sidebar"
          >
            ☰
          </button>
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
