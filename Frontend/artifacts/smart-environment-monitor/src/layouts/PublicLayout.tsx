import { Link, Outlet, useLocation } from 'react-router-dom';
import { Activity, ArrowRight, BrainCircuit, Home, LayoutGrid, Map as MapIcon } from 'lucide-react';
import type { ComponentType } from 'react';
import type { LucideProps } from 'lucide-react';
import { RefreshStatus } from '@/components/RefreshStatus';
import { PUBLIC_REFRESH_MS } from '@/api/publicApi';
import { usePublicStations } from '@/hooks/use-public-data';
import { useDocumentTheme } from '@/hooks/use-document-theme';

const PUBLIC_LINKS: Array<[string, string, ComponentType<LucideProps>]> = [
  ['/', 'Overview', Home],
  ['/monitor', 'Live monitor', Activity],
  ['/stations', 'Stations', LayoutGrid],
  ['/map', 'Map', MapIcon],
  ['/ai-check', 'AI check', BrainCircuit],
];

/** The bottom bar has room for one word per tab. */
const SHORT_LABELS: Record<string, string> = { 'Live monitor': 'Live', 'AI check': 'AI' };

function isActive(pathname: string, href: string): boolean {
  return href === '/' ? pathname === '/' : pathname === href || pathname.startsWith(`${href}/`);
}

export function PublicLayout() {
  useDocumentTheme('public');
  const location = useLocation();
  const stations = usePublicStations();

  return (
    <div className="public-shell">
      <a className="visually-hidden" href="#main">Skip to content</a>
      <header className="public-header">
        <div className="public-header-inner">
          <Link to="/" className="brand" data-testid="link-public-home">
            <span className="brand-mark"><Activity size={18} /></span>
            <span>FIELD<span className="brand-light">/SENSE</span></span>
          </Link>
          <nav className="public-nav" aria-label="Main">
            {PUBLIC_LINKS.map(([href, label]) => (
              <Link
                key={href}
                to={href}
                className={isActive(location.pathname, href) ? 'active' : ''}
                aria-current={isActive(location.pathname, href) ? 'page' : undefined}
                data-testid={`link-public-${label.toLowerCase().replaceAll(' ', '-')}`}
              >
                {label}
              </Link>
            ))}
          </nav>
          <div className="public-header-actions">
            <RefreshStatus source={stations} everyMs={PUBLIC_REFRESH_MS} />
          </div>
        </div>
      </header>

      <main id="main"><Outlet /></main>

      {/*
        The footer's freshness line is computed from the mechanism that
        actually refreshes the page. It once promised "Data refreshes every 60
        seconds" in an app with no refresh at all.
      */}
      <footer className="site-foot">
        <div className="site-foot-inner">
          <Link to="/" className="brand foot-brand"><span className="brand-mark"><Activity size={16} /></span>FIELD/SENSE</Link>
          <div className="foot-cols">
            <nav className="foot-col" aria-label="Observe">
              <strong>Observe</strong>
              <Link to="/monitor">Live monitor</Link>
              <Link to="/map">Map</Link>
              <Link to="/ai-check">AI check</Link>
              <Link to="/weather">Forecast</Link>
            </nav>
            <nav className="foot-col" aria-label="Stations">
              <strong>Stations</strong>
              <Link to="/stations">Directory</Link>
              <Link to="/">Home</Link>
            </nav>
            <nav className="foot-col" aria-label="About the data">
              <strong>About the data</strong>
              <a href="/#reading-the-numbers">Reading the numbers</a>
            </nav>
            <nav className="foot-col" aria-label="Operators">
              <strong>Operators</strong>
              <Link to="/admin/login">Console sign in</Link>
            </nav>
            {/*
              The freshness line is computed from the mechanism that actually
              refreshes the page. It once promised "Data refreshes every 60
              seconds" in an app with no refresh at all.
            */}
            <div className="foot-contact" data-testid="text-freshness-note">
              <span>Open environmental observatory, Andhra Pradesh, India.</span>
              <span>Readings re-read every {Math.round(PUBLIC_REFRESH_MS / 1000)} seconds while this page is open.</span>
            </div>
          </div>
          <div className="foot-base">
            <span>Measured, not manufactured.</span>
            <Link to="/admin/login" className="foot-button" data-testid="link-admin-login-footer">Operator sign in <ArrowRight size={16} aria-hidden="true" /></Link>
          </div>
        </div>
      </footer>

      <nav className="tabbar" aria-label="Sections">
        {PUBLIC_LINKS.map(([href, label, Icon]) => (
          <Link
            key={href}
            to={href}
            className={isActive(location.pathname, href) ? 'active' : ''}
            aria-current={isActive(location.pathname, href) ? 'page' : undefined}
            data-testid={`tab-public-${label.toLowerCase().replaceAll(' ', '-')}`}
          >
            <Icon size={20} aria-hidden="true" />
            {SHORT_LABELS[label] ?? label}
          </Link>
        ))}
      </nav>
    </div>
  );
}
