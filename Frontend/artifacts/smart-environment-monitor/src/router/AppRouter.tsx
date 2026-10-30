import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { ErrorBoundary } from '@/components/error-boundary';
import { PublicLayout } from '@/layouts/PublicLayout';
import { RequireAdmin } from '@/providers/RequireAdmin';

import { Home } from '@/pages/public/Home';
import { Monitor } from '@/pages/public/Monitor';
import { StationDirectory } from '@/pages/public/StationDirectory';
import { StationDetail } from '@/pages/public/StationDetail';
import { Weather } from '@/pages/public/Weather';
import { LoginPage } from '@/pages/auth/LoginPage';
import { ForbiddenPage, NotFoundPage } from '@/pages/errors/UtilityPage';

// The operator console and the mapping stack are the two heaviest parts of the
// bundle; a public visitor should never download either.
// The AI check carries the charting library; only its visitors download it.
const AiCheck = lazy(() => import('@/pages/public/AiCheck').then((m) => ({ default: m.AiCheck })));
const MapPage = lazy(() => import('@/pages/public/MapPage').then((m) => ({ default: m.MapPage })));
const AdminLayout = lazy(() => import('@/layouts/AdminLayout').then((m) => ({ default: m.AdminLayout })));
const AdminOverview = lazy(() => import('@/pages/admin/AdminOverview').then((m) => ({ default: m.AdminOverview })));
const Devices = lazy(() => import('@/pages/admin/Devices').then((m) => ({ default: m.Devices })));
const DeviceDetail = lazy(() => import('@/pages/admin/DeviceDetail').then((m) => ({ default: m.DeviceDetail })));
const Sensors = lazy(() => import('@/pages/admin/Sensors').then((m) => ({ default: m.Sensors })));
const AdminMap = lazy(() => import('@/pages/admin/AdminMap').then((m) => ({ default: m.AdminMap })));
const Heatmap = lazy(() => import('@/pages/admin/Heatmap').then((m) => ({ default: m.Heatmap })));
const Analytics = lazy(() => import('@/pages/admin/Analytics').then((m) => ({ default: m.Analytics })));
const Reports = lazy(() => import('@/pages/admin/Reports').then((m) => ({ default: m.Reports })));
const Alerts = lazy(() => import('@/pages/admin/Alerts').then((m) => ({ default: m.Alerts })));
const AIInsights = lazy(() => import('@/pages/admin/AIInsights').then((m) => ({ default: m.AIInsights })));
const DigitalTwin = lazy(() => import('@/pages/admin/DigitalTwin').then((m) => ({ default: m.DigitalTwin })));
const Firmware = lazy(() => import('@/pages/admin/Firmware').then((m) => ({ default: m.Firmware })));
const SystemHealth = lazy(() => import('@/pages/admin/SystemHealth').then((m) => ({ default: m.SystemHealth })));
const Users = lazy(() => import('@/pages/admin/Users').then((m) => ({ default: m.Users })));
const Settings = lazy(() => import('@/pages/admin/Settings').then((m) => ({ default: m.Settings })));
const HardwareDebug = lazy(() => import('@/pages/admin/HardwareDebug').then((m) => ({ default: m.HardwareDebug })));

function RouteFallback() {
  return <div className="empty-inline" style={{ padding: '2rem' }}>Loading…</div>;
}

export function AppRouter() {
  const location = useLocation();
  return (
    // Remounting on navigation clears an error from a page the operator has left.
    <ErrorBoundary resetKey={location.pathname}>
      <Suspense fallback={<RouteFallback />}>
        <Routes>
          <Route element={<PublicLayout />}>
            <Route path="/" element={<Home />} />
            <Route path="/monitor" element={<Monitor />} />
            <Route path="/stations" element={<StationDirectory />} />
            <Route path="/stations/:uid" element={<StationDetail />} />
            <Route path="/map" element={<MapPage />} />
            <Route path="/weather" element={<Weather />} />
            <Route path="/ai-check" element={<AiCheck />} />
          </Route>

          <Route path="/admin/login" element={<LoginPage />} />

          <Route element={<RequireAdmin />}>
            <Route path="/admin" element={<AdminLayout />}>
              <Route index element={<AdminOverview />} />
              <Route path="dashboard" element={<AdminOverview />} />
              <Route path="devices" element={<Devices />} />
              <Route path="devices/:deviceUid" element={<DeviceDetail />} />
              <Route path="sensors" element={<Sensors />} />
              <Route path="map" element={<AdminMap />} />
              <Route path="heatmap" element={<Heatmap />} />
              <Route path="analytics" element={<Analytics />} />
              <Route path="reports" element={<Reports />} />
              <Route path="alerts" element={<Alerts />} />
              <Route path="ai" element={<AIInsights />} />
              <Route path="digital-twin" element={<DigitalTwin />} />
              <Route path="firmware" element={<Firmware />} />
              <Route path="system" element={<SystemHealth />} />
              <Route path="users" element={<Users />} />
              <Route path="settings" element={<Settings />} />
              <Route path="hardware-debug" element={<HardwareDebug />} />
              <Route path="*" element={<Navigate to="/admin/dashboard" replace />} />
            </Route>
          </Route>

          <Route path="/403" element={<ForbiddenPage />} />
          <Route path="/404" element={<NotFoundPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </Suspense>
    </ErrorBoundary>
  );
}
