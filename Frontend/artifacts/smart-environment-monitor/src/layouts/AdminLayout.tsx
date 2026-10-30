import { useState } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Activity, BarChart3, Bell, ChevronRight, CircleHelp, Cpu, FileText,
  Gauge, Globe2, Layers3, LogOut, Map as MapIcon, Menu, Network, RefreshCw, Server, Settings,
  ShieldAlert, Sparkles, UploadCloud, UsersRound,
} from 'lucide-react';
import { authApi } from '@/api/authApi';
import { useLiveStream } from '@/hooks/use-live-stream';
import { LiveStatusPulse } from '@/components/LiveStatusPulse';
import type { IconType } from '@/components/Metric';
import { useDocumentTheme } from '@/hooks/use-document-theme';

const NAV_GROUPS: Array<{ label: string; links: Array<[string, string, IconType]> }> = [
  { label: 'Observe', links: [['/admin/dashboard', 'Overview', Gauge], ['/admin/devices', 'Devices', Cpu], ['/admin/sensors', 'Sensors', Activity], ['/admin/map', 'Map', MapIcon], ['/admin/heatmap', 'Heatmap', Layers3]] },
  { label: 'Understand', links: [['/admin/analytics', 'Analytics', BarChart3], ['/admin/reports', 'Reports', FileText], ['/admin/alerts', 'Alerts', Bell], ['/admin/ai', 'AI insights', Sparkles]] },
  { label: 'Operate', links: [['/admin/digital-twin', 'Digital twin', Network], ['/admin/firmware', 'Firmware', UploadCloud], ['/admin/system', 'System health', Server], ['/admin/users', 'Users', UsersRound], ['/admin/settings', 'Settings', Settings]] },
];

export function AdminLayout() {
  useDocumentTheme('console');
  const location = useLocation();
  const navigate = useNavigate();
  const [mobile, setMobile] = useState(false);
  const { data: me } = useQuery({ queryKey: ['me'], queryFn: authApi.me, retry: false });
  // auth-service returns a single `name` field, never firstName/lastName.
  const user = me?.data ?? { name: 'Operator', email: '', role: 'ADMIN' as const };
  const initials = user.name ? user.name.split(' ').map((p) => p[0]).slice(0, 2).join('').toUpperCase() : 'OP';
  const liveStream = useLiveStream(true);

  const isActive = (href: string) => location.pathname === href || (href === '/admin/dashboard' && location.pathname === '/admin');

  const signOut = () => {
    authApi.logout();
    navigate('/admin/login', { replace: true });
  };

  return (
    <div className="admin-shell">
      <aside className={`admin-sidebar ${mobile ? 'mobile-open' : ''}`}>
        <div className="admin-brand">
          <Link to="/admin/dashboard" className="brand" onClick={() => setMobile(false)} data-testid="link-admin-brand">
            <span className="brand-mark"><Activity size={16} /></span>FIELD<span className="brand-light">/SENSE</span>
          </Link>
          <span className="console-label">OPERATOR CONSOLE</span>
        </div>
        <div className="sidebar-session">
          <span className="avatar">{initials}</span>
          <span><strong>{user.name}</strong><small>{user.role}</small></span>
          <LiveStatusPulse status={liveStream.status} />
        </div>
        <nav className="admin-nav" aria-label="Console">
          {NAV_GROUPS.map((group) => (
            <div className="nav-group" key={group.label}>
              <div className="nav-label">{group.label}</div>
              {group.links.map(([href, label, Icon]) => (
                <Link
                  to={href}
                  key={href}
                  className={isActive(href) ? 'active' : ''}
                  onClick={() => setMobile(false)}
                  data-testid={`link-admin-${label.toLowerCase().replaceAll(' ', '-')}`}
                >
                  <Icon size={17} aria-hidden="true" />{label}
                </Link>
              ))}
            </div>
          ))}
          <Link
            to="/admin/hardware-debug"
            className={isActive('/admin/hardware-debug') ? 'active special-access' : 'special-access'}
            onClick={() => setMobile(false)}
            data-testid="link-admin-hardware-debug"
          >
            <ShieldAlert size={17} aria-hidden="true" />Hardware debug (secure)
          </Link>
        </nav>
        <div className="sidebar-bottom">
          <Link to="/" data-testid="link-back-public"><Globe2 size={17} aria-hidden="true" />Public observatory</Link>
        </div>
      </aside>
      {/* Tapping outside the open drawer closes it, as on every other phone app. */}
      {mobile && <div className="sidebar-scrim" onClick={() => setMobile(false)} aria-hidden="true" />}
      <div className="admin-main">
        <header className="admin-topbar">
          <button className="icon-button mobile-menu admin-menu" aria-label="Toggle navigation" aria-expanded={mobile} onClick={() => setMobile(!mobile)} data-testid="button-admin-mobile-menu"><Menu size={18} /></button>
          <div className="crumb">Operator console <ChevronRight size={14} /><span>{location.pathname.replace('/admin/', '').replace('/admin', 'overview') || 'overview'}</span></div>
          <div className="topbar-actions">
            <button className="icon-button" title="Refresh" onClick={() => window.location.reload()} data-testid="button-refresh"><RefreshCw size={15} /></button>
            <button className="icon-button" title="Help is not available in this console yet." aria-label="Help" disabled data-testid="button-help"><CircleHelp size={16} /></button>
            <div className="topbar-user">
              <span className="avatar small">{initials}</span>
              <span>{user.name}</span>
              <button className="icon-button" aria-label="Sign out" title="Sign out" onClick={signOut} data-testid="button-sign-out"><LogOut size={14} /></button>
            </div>
          </div>
        </header>
        <main className="admin-content"><Outlet /></main>
      </div>
    </div>
  );
}
