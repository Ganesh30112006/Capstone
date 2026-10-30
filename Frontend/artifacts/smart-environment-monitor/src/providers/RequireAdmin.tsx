import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { authApi } from '@/api/authApi';
import { useDocumentTheme } from '@/hooks/use-document-theme';

function Spinner() {
  return (
    <div
      className="console-spinner"
      role="status"
      aria-label="Loading"
    />
  );
}

/**
 * Gate for every /admin route. The backend is the real authority — the gateway
 * re-checks the token and the role on every request — but the console should
 * not render operator surfaces it cannot use, so it checks here too.
 */
export function RequireAdmin() {
  // Dark from the first frame: the session check otherwise flashed the light
  // public theme on the way into the console.
  useDocumentTheme('console');
  const location = useLocation();
  const { data: me, isLoading, isError } = useQuery({ queryKey: ['me'], queryFn: authApi.me, retry: false });

  if (isLoading) {
    return (
      <div className="utility-page" style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <div><Spinner /><h2>Authenticating operator…</h2></div>
      </div>
    );
  }

  if (isError || !me?.success) {
    return <Navigate to="/admin/login" replace state={{ from: location.pathname }} />;
  }

  if (me.data?.role !== 'ADMIN') {
    return <Navigate to="/403" replace />;
  }

  return <Outlet />;
}
