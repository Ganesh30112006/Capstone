import { useEffect, useState } from 'react';
import type { LiveStatus } from '@/lib/live-stream';

function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(false);
  useEffect(() => {
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    setReduced(query.matches);
    const onChange = () => setReduced(query.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);
  return reduced;
}

const LABELS: Record<LiveStatus, string> = {
  connecting: 'Connecting to live stream…',
  open: 'Live',
  closed: 'Reconnecting…',
  error: 'Live stream unavailable',
};


export function LiveStatusPulse({ status }: { status: LiveStatus }) {
  const reducedMotion = usePrefersReducedMotion();
  const isLive = status === 'open';
  return (
    <span className="live-status-pulse" data-status={status} title={LABELS[status]}>
      <span className={`live-pulse-dot ${isLive ? 'live' : 'idle'} ${reducedMotion ? 'no-motion' : ''}`} aria-hidden="true" />
      <span className="live-pulse-label">{LABELS[status]}</span>
    </span>
  );
}
