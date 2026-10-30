import { useEffect, useState } from 'react';
import { RefreshCw, WifiOff } from 'lucide-react';
import { formatAge } from './ReadingAge';

/** The parts of a React Query result this needs. */
export interface RefreshSource {
  isPending: boolean;
  isError: boolean;
  dataUpdatedAt: number;
}

/**
 * How fresh the public pages are, from the query that actually refreshes them.
 *
 * "Live" belonged to the operator WebSocket, which public visitors do not
 * have. What they do have is a re-read every few seconds, so this says when
 * that last succeeded -- and says so plainly when it is failing, rather than
 * leaving the last good numbers on screen looking current.
 */
export function RefreshStatus({ source, everyMs }: { source: RefreshSource; everyMs: number }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  if (source.isPending) {
    return (
      <span className="refresh-status" data-state="pending" data-testid="refresh-status">
        <RefreshCw size={14} aria-hidden="true" /><span className="refresh-label">Connecting…</span>
      </span>
    );
  }
  if (source.isError) {
    return (
      <span className="refresh-status" data-state="error" role="status" data-testid="refresh-status">
        <WifiOff size={14} aria-hidden="true" /><span className="refresh-label">Network unreachable</span>
      </span>
    );
  }
  const age = source.dataUpdatedAt ? Math.max(0, Math.round((now - source.dataUpdatedAt) / 1000)) : null;
  return (
    <span
      className="refresh-status"
      data-state="ok"
      title={`Re-read every ${Math.round(everyMs / 1000)} seconds`}
      data-testid="refresh-status"
    >
      <span className="live-pulse-dot live" aria-hidden="true" />
      <span className="refresh-label">Updated {formatAge(age)}</span>
    </span>
  );
}
