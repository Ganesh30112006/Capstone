import { Activity } from 'lucide-react';
import type { Sensor } from '@/types/domain';
import { UNKNOWN } from '@/lib/format';
import { Badge } from './StatusIndicators';

export function SensorRow({ sensor }: { sensor: Sensor }) {
  const tone =
    sensor.health === 'healthy' ? 'good'
    : sensor.health === 'degraded' ? 'warn'
    : sensor.health === 'failed' ? 'bad'
    // Neutral, not red: an uninterpretable status is not a reported failure.
    : 'neutral';
  return (
    <div className="sensor-row" data-testid={`row-sensor-${sensor.stationUid}-${sensor.name.replaceAll(' ', '-')}`}>
      <div className={`sensor-icon ${sensor.health}`}><Activity size={14} /></div>
      <span><strong>{sensor.name}</strong><small>{sensor.stationUid} · {sensor.lastReading}</small></span>
      {/* A probe with no current reading shows nothing, not a zero. */}
      <span className="sensor-value mono">{sensor.value === null ? UNKNOWN : `${sensor.value}${sensor.unit}`}</span>
      <Badge tone={tone}>{sensor.health}</Badge>
      {sensor.failureReason && <span className="failure-reason">{sensor.failureReason}</span>}
    </div>
  );
}
