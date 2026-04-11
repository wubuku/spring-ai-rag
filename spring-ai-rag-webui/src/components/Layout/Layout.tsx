import { useState, useEffect } from 'react';
import { Outlet, NavLink } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ErrorBoundary } from '../ErrorBoundary';
import { ThemeToggle } from '../ThemeToggle';
import styles from './Layout.module.css';

const NAV_ITEMS = [
  { to: '/dashboard', labelKey: 'nav.dashboard', icon: '📊' },
  { to: '/documents', labelKey: 'nav.documents', icon: '📄' },
  { to: '/collections', labelKey: 'nav.collections', icon: '📚' },
  { to: '/chat', labelKey: 'nav.chat', icon: '💬' },
  { to: '/search', labelKey: 'nav.search', icon: '🔍' },
  { to: '/metrics', labelKey: 'nav.metrics', icon: '📈' },
  { to: '/alerts', labelKey: 'nav.alerts', icon: '🔔' },
  { to: '/abtest', labelKey: 'nav.abtest', icon: '🧪' },
  { to: '/api-keys', labelKey: 'nav.apiKeys', icon: '🔑' },
  { to: '/settings', labelKey: 'nav.settings', icon: '⚙️' },
];

const MOBILE_BREAKPOINT = 768;

export function Layout() {
  const { t } = useTranslation();
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [isMobile, setIsMobile] = useState(window.innerWidth < MOBILE_BREAKPOINT);

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

  // Close sidebar when navigating on mobile
  const handleNavClick = () => {
    if (isMobile) {
      setSidebarOpen(false);
    }
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
        <nav>
          {NAV_ITEMS.map(item => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => `${styles.navItem} ${isActive ? styles.active : ''}`}
              onClick={handleNavClick}
            >
              <span className={styles.icon}>{item.icon}</span>
              {t(item.labelKey)}
            </NavLink>
          ))}
        </nav>
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
        <main className={styles.main}>
          <ErrorBoundary>
            <Outlet />
          </ErrorBoundary>
        </main>
      </div>
    </div>
  );
}
