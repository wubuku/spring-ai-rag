import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useApiKeyAuth } from './ApiKeyAuthContext';

export function ProtectedRoute() {
  const { isUnlocked } = useApiKeyAuth();
  const location = useLocation();

  if (!isUnlocked) {
    return (
      <Navigate
        to="/unlock"
        replace
        state={{ from: `${location.pathname}${location.search}` }}
      />
    );
  }

  return <Outlet />;
}
