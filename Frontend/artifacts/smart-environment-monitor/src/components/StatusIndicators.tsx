import type { ReactNode } from 'react';
import type { StationStatus } from '@/types/domain';

type AnyStatus = StationStatus | 'operational' | 'degraded' | 'outage' | 'healthy' | 'failed';

/**
 * A status shown as a shape as well as a colour (see components.css). On its
 * own it is an image with a name; beside a word that already says the status,
 * pass `decorative` so screen readers do not hear it twice.
 */
export function StatusDot({ status, decorative = false }: { status: AnyStatus; decorative?: boolean }) {
  const tone = status === 'online' || status === 'operational' || status === 'healthy'
    ? 'good'
    : status === 'warning' || status === 'degraded'
      ? 'warn'
      : 'bad';
  return decorative
    ? <span className={`status-dot ${tone}`} aria-hidden="true" />
    : <span className={`status-dot ${tone}`} role="img" aria-label={status} />;
}

export type BadgeTone = 'good' | 'warn' | 'bad' | 'info' | 'neutral';

/**
 * The dot takes the badge's own tone. It used to borrow a station status, so a
 * neutral badge drew the amber "degraded" ring and an info badge the green
 * "online" disc -- two signals the badge did not mean.
 */
export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: BadgeTone }) {
  return (
    <span className={`badge badge-${tone}`}>
      <span className={`status-dot ${tone}`} aria-hidden="true" />
      {children}
    </span>
  );
}

export function toneForStationStatus(status: StationStatus): BadgeTone {
  return status === 'online' ? 'good' : status === 'warning' ? 'warn' : 'bad';
}

export function toneForSeverity(severity: 'critical' | 'warning' | 'info'): BadgeTone {
  return severity === 'critical' ? 'bad' : severity === 'warning' ? 'warn' : 'info';
}
