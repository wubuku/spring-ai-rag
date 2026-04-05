import { Outlet, NavLink } from 'react-router-dom';
import styles from './Layout.module.css';

const NAV_ITEMS = [
  { to: '/dashboard', label: 'Dashboard', icon: '📊' },
  { to: '/documents', label: 'Documents', icon: '📄' },
  { to: '/collections', label: 'Collections', icon: '📚' },
  { to: '/chat', label: 'Chat', icon: '💬' },
  { to: '/search', label: 'Search', icon: '🔍' },
  { to: '/metrics', label: 'Metrics', icon: '📈' },
  { to: '/alerts', label: 'Alerts', icon: '🔔' },
  { to: '/settings', label: 'Settings', icon: '⚙️' },
];

export function Layout() {
  return (
    <div className={styles.layout}>
      <aside className={styles.sidebar}>
        <div className={styles.logo}>spring-ai-rag</div>
        <nav>
          {NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `${styles.navItem} ${isActive ? styles.active : ''}`
              }
            >
              <span className={styles.icon}>{item.icon}</span>
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <main className={styles.main}>
        <Outlet />
      </main>
    </div>
  );
}
