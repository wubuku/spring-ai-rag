import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { lazy, Suspense } from 'react';
import { Layout } from './components/Layout/Layout';
import { ToastProvider } from './components/Toast';

import './styles/global.css';

// Route-level code splitting: each page becomes a separate chunk.
// Lazy-loaded pages are split into their own JS files by Vite,
// dramatically reducing the initial bundle size (721KB → per-route chunks).
const Dashboard = lazy(() => import('./pages/Dashboard').then(m => ({ default: m.Dashboard })));
const Documents = lazy(() => import('./pages/Documents').then(m => ({ default: m.Documents })));
const Collections = lazy(() => import('./pages/Collections').then(m => ({ default: m.Collections })));
const Chat = lazy(() => import('./pages/Chat').then(m => ({ default: m.Chat })));
const Search = lazy(() => import('./pages/Search').then(m => ({ default: m.Search })));
const Metrics = lazy(() => import('./pages/Metrics').then(m => ({ default: m.Metrics })));
const Alerts = lazy(() => import('./pages/Alerts').then(m => ({ default: m.Alerts })));
const ABTest = lazy(() => import('./pages/ABTest').then(m => ({ default: m.ABTest })));
const ApiKeys = lazy(() => import('./pages/ApiKeys').then(m => ({ default: m.ApiKeys })));
const Settings = lazy(() => import('./pages/Settings').then(m => ({ default: m.Settings })));
const Files = lazy(() => import('./pages/Files').then(m => ({ default: m.Files })));

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
});

/**
 * Fallback rendered while a lazy page chunk is loading.
 * Simple spinner for minimal JS overhead during initial load.
 */
function PageFallback() {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: '320px',
        color: 'var(--color-text-muted, #6b7280)',
        fontSize: '0.875rem',
        gap: '0.5rem',
      }}
    >
      <span>Loading…</span>
    </div>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <BrowserRouter basename="/webui">
          <Routes>
            <Route path="/" element={<Layout />}>
              <Route index element={<Navigate to="dashboard" replace />} />
              <Route
                path="dashboard"
                element={
                  <Suspense fallback={<PageFallback />}>
                    <Dashboard />
                  </Suspense>
                }
              />
              <Route
                path="documents"
                element={
                  <Suspense fallback={<PageFallback />}>
                    <Documents />
                  </Suspense>
                }
              />
              <Route
                path="collections"
                element={
                  <Suspense fallback={<PageFallback />}>
                    <Collections />
                  </Suspense>
                }
              />
              <Route
                path="chat"
                element={
                  <Suspense fallback={<PageFallback />}>
                    <Chat />
                  </Suspense>
                }
              />
              <Route
                path="chat/:sessionId"
                element={
                  <Suspense fallback={<PageFallback />}>
                    <Chat />
                  </Suspense>
                }
              />
              <Route
                path="search"
                element={
                  <Suspense fallback={<PageFallback />}>
                    <Search />
                  </Suspense>
                }
              />
              <Route
                path="metrics"
                element={
                  <Suspense fallback={<PageFallback />}>
                    <Metrics />
                  </Suspense>
                }
              />
              <Route
                path="alerts"
                element={
                  <Suspense fallback={<PageFallback />}>
                    <Alerts />
                  </Suspense>
                }
              />
              <Route
                path="abtest"
                element={
                  <Suspense fallback={<PageFallback />}>
                    <ABTest />
                  </Suspense>
                }
              />
              <Route
                path="api-keys"
                element={
                  <Suspense fallback={<PageFallback />}>
                    <ApiKeys />
                  </Suspense>
                }
              />
              <Route
                path="files"
                element={
                  <Suspense fallback={<PageFallback />}>
                    <Files />
                  </Suspense>
                }
              />
              <Route
                path="settings"
                element={
                  <Suspense fallback={<PageFallback />}>
                    <Settings />
                  </Suspense>
                }
              />
            </Route>
          </Routes>
        </BrowserRouter>
      </ToastProvider>
    </QueryClientProvider>
  );
}
