import { Activity, Gauge as GaugeIcon } from 'lucide-react';
import type { CalibrationStatus, Sensor } from '@/types/domain';
import { UNKNOWN } from '@/lib/format';
import { formatServerTime } from '@/lib/time';
import { Badge, type BadgeTone } from './StatusIndicators';
import { ReadingAge } from './ReadingAge';

/** Sensors whose readings can be offset. Rain is a yes/no; GPS is not a measurement. */
export const CALIBRATABLE = new Set(['temperature', 'humidity', 'airQuality', 'pressure', 'oxygen']);

const CALIBRATION_LABEL: Record<CalibrationStatus, [string, BadgeTone]> = {
  VALID: ['Valid', 'good'],
  DUE_SOON: ['Due soon', 'warn'],
  EXPIRED: ['Expired', 'bad'],
  NOT_CALIBRATED: ['Not calibrated', 'neutral'],
  SUPERSEDED: ['Superseded', 'neutral'],
};

const CONDITION_LABEL: Record<string, string> = {
  NONE: 'All checks passing',
  MISSING: 'No value in the latest reading',
  OUT_OF_RANGE: 'Outside the range the sensor can report',
  RAPID_CHANGE: 'Changing faster than is plausible',
  FLATLINE: 'Value frozen',
};

const HEALTH_TONE: Record<Sensor['health'], BadgeTone> = { healthy: 'good', degraded: 'warn', failed: 'bad', unknown: 'neutral' };

function unitText(unit: string): string {
  return unit.trim();
}

function number(value: number | null | undefined, digits: number): string {
  return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(digits) : UNKNOWN;
}

function signed(value: number | null | undefined, digits: number, unit: string): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return UNKNOWN;
  return `${value > 0 ? '+' : value < 0 ? '−' : '±'}${Math.abs(value).toFixed(digits)} ${unitText(unit)}`.trim();
}

/** The air-quality index is a whole number; every other quantity is stored to two decimals. */
function digitsFor(type: string | undefined): number {
  return type === 'airQuality' ? 0 : 2;
}

/**
 * One sensor, and everything the platform actually knows about it.
 *
 * Every figure here traces to a record: the health percentage to the hourly
 * evaluation counts beside it, the calibration to a stored reference-vs-sensor
 * observation, the drift to the change between two of those. Where there is no
 * record the card says so with a dash -- a sensor nobody has checked reads
 * "Not calibrated", never "Valid", and one with no evaluations has no score,
 * not 100%.
 */
export function SensorCard({ sensor, onCalibrate }: { sensor: Sensor; onCalibrate?: (sensor: Sensor) => void }) {
  const digits = digitsFor(sensor.type);
  const score = sensor.healthScore ?? null;
  const band = score === null ? undefined : score.percent >= 98 ? 'good' : score.percent >= 90 ? 'warn' : 'bad';
  const calibration = sensor.calibration;
  const [calibrationText, calibrationTone] = calibration ? CALIBRATION_LABEL[calibration.status] : [UNKNOWN, 'neutral' as BadgeTone];
  const corrected = typeof sensor.value === 'number' && typeof sensor.rawValue === 'number' && sensor.value !== sensor.rawValue;
  const canCalibrate = !!onCalibrate && !!sensor.type && CALIBRATABLE.has(sensor.type);

  return (
    <article
      className="sensor-card"
      data-health={sensor.health}
      data-testid={`row-sensor-${sensor.stationUid}-${sensor.name.replaceAll(' ', '-')}`}
    >
      <header className="sensor-card-head">
        <span className={`sensor-icon ${sensor.health}`}><Activity size={16} aria-hidden="true" /></span>
        <div>
          <h3>{sensor.name}</h3>
          <small>{sensor.stationUid}</small>
        </div>
        <Badge tone={HEALTH_TONE[sensor.health]}>{sensor.health}</Badge>
      </header>

      <div className="sensor-card-value">
        {/* A probe with no current reading shows nothing, not a zero. */}
        <strong>{sensor.value === null ? UNKNOWN : number(sensor.value, digits)}</strong>
        {sensor.value !== null && <span>{unitText(sensor.unit)}</span>}
        {corrected && <span className="raw" title="What the sensor reported before its calibration offset">raw {number(sensor.rawValue, digits)}</span>}
      </div>

      <div className="health-meter">
        <div className="health-meter-top">
          <span>Health · last 24 h</span>
          <strong>{score ? `${score.percent.toFixed(1)}%` : UNKNOWN}</strong>
        </div>
        <div className="health-meter-bar" role="img" aria-label={score ? `${score.percent}% of evaluations passed` : 'No evaluations in the last 24 hours'}>
          <span data-band={band} style={{ width: score ? `${score.percent}%` : '0%' }} />
        </div>
        <small>
          {score
            ? `${score.passed.toLocaleString()} of ${(score.passed + score.warned + score.failed).toLocaleString()} readings passed every check`
            : 'No evaluations in the last 24 hours'}
        </small>
      </div>

      <dl className="sensor-facts">
        <div><dt>Calibration</dt><dd><Badge tone={calibrationTone}>{calibrationText}</Badge></dd></div>
        <div><dt>Last calibrated</dt><dd>{formatServerTime(calibration?.lastCalibratedAt, { dateStyle: 'medium' }) ?? UNKNOWN}</dd></div>
        <div><dt>Offset</dt><dd>{signed(calibration?.offset, digits, sensor.unit)}</dd></div>
        <div>
          <dt title="Extrapolated from how the offset moved between the last two calibrations">Drift estimate</dt>
          <dd>{signed(calibration?.driftEstimate, digits, sensor.unit)}</dd>
        </div>
        <div><dt>Expected range</dt><dd>{sensor.expectedMin != null && sensor.expectedMax != null ? `${sensor.expectedMin} to ${sensor.expectedMax}` : UNKNOWN}</dd></div>
        <div><dt>Last good reading</dt><dd>{sensor.lastHealthyAt ? <ReadingAge readingAt={sensor.lastHealthyAt} /> : UNKNOWN}</dd></div>
        <div><dt>Failures · 24 h</dt><dd>{score ? score.failed.toLocaleString() : UNKNOWN}</dd></div>
        <div>
          <dt>Flatline</dt>
          <dd>{sensor.flatlined === true ? <>since <ReadingAge readingAt={sensor.flatlineSince ?? null} /></> : sensor.flatlined === false ? 'No' : UNKNOWN}</dd>
        </div>
      </dl>

      <footer className="sensor-card-foot">
        <span className={sensor.health === 'healthy' ? 'muted-cell' : 'warn-text'} style={{ fontSize: 12 }}>
          {sensor.failureReason ?? (sensor.condition ? CONDITION_LABEL[sensor.condition] : 'Condition not recorded')}
        </span>
        {canCalibrate && (
          <button className="button button-secondary button-sm" onClick={() => onCalibrate?.(sensor)} data-testid={`button-calibrate-${sensor.stationUid}-${sensor.type}`}>
            <GaugeIcon size={14} aria-hidden="true" /> Calibrate
          </button>
        )}
      </footer>
    </article>
  );
}
