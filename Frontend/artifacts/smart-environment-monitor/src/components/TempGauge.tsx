import { UNKNOWN } from '@/lib/format';

/*
 * Temperature as a dial. The arc sweeps 240 degrees, like a speedometer, from
 * MIN to MAX °C, and is coloured in the heat-index bands lib/interpret.ts uses
 * for its advice, so the colour under the needle and the words beside it say
 * the same thing.
 *
 * An unknown reading draws no needle. A needle parked at the bottom of the
 * scale would read as a cold day, which is a measurement nobody took.
 */

const MIN = 10;
const MAX = 50;
const START = 150; // degrees, SVG coordinates (y down): bottom-left
const SWEEP = 240;
const CX = 100;
const CY = 100;
const R = 80;

const BANDS: Array<{ from: number; to: number; tone: string }> = [
  { from: 10, to: 20, tone: 'cool' },
  { from: 20, to: 27, tone: 'mild' },
  { from: 27, to: 32, tone: 'warm' },
  { from: 32, to: 41, tone: 'hot' },
  { from: 41, to: 50, tone: 'danger' },
];

const clamp = (v: number) => Math.min(MAX, Math.max(MIN, v));
const angleOf = (v: number) => START + ((clamp(v) - MIN) / (MAX - MIN)) * SWEEP;

function point(angle: number, radius: number): [number, number] {
  const rad = (angle * Math.PI) / 180;
  return [CX + radius * Math.cos(rad), CY + radius * Math.sin(rad)];
}

function arc(from: number, to: number, radius: number): string {
  const a1 = angleOf(from);
  const a2 = angleOf(to);
  const [x1, y1] = point(a1, radius);
  const [x2, y2] = point(a2, radius);
  return `M ${x1.toFixed(2)} ${y1.toFixed(2)} A ${radius} ${radius} 0 ${a2 - a1 > 180 ? 1 : 0} 1 ${x2.toFixed(2)} ${y2.toFixed(2)}`;
}

const known = (v: number | null | undefined): v is number => typeof v === 'number' && Number.isFinite(v);

export function TempGauge({
  celsius, feelsLike, size = 'large',
}: {
  celsius: number | null | undefined;
  /** Shown as a marker on the rim, only when it differs from the reading. */
  feelsLike?: number | null;
  size?: 'large' | 'small';
}) {
  const hasValue = known(celsius);
  const showFeel = hasValue && known(feelsLike) && Math.abs(feelsLike - celsius) >= 0.5;
  const label = hasValue
    ? `Temperature ${celsius.toFixed(1)} °C${showFeel ? `, feels like ${Math.round(feelsLike as number)} °C` : ''}`
    : 'Temperature not known';
  const ticks = Array.from({ length: (MAX - MIN) / 5 + 1 }, (_, i) => MIN + i * 5);

  return (
    <svg className={`gauge gauge-${size}`} viewBox="0 0 200 172" role="img" aria-label={label}>
      <path className="gauge-track" d={arc(MIN, MAX, R)} />
      {BANDS.map((band) => <path key={band.tone} className={`gauge-band band-${band.tone}`} d={arc(band.from + 0.3, band.to - 0.3, R)} />)}

      {size === 'large' && ticks.map((t) => {
        const a = angleOf(t);
        // Only the inner labels: 10 and 50 would sit where the reading is printed.
        const major = t % 10 === 0 && t > MIN && t < MAX;
        const [x1, y1] = point(a, R - 13);
        const [x2, y2] = point(a, R - (major ? 22 : 18));
        const [lx, ly] = point(a, R - 32);
        return (
          <g key={t}>
            <line className="gauge-tick" x1={x1} y1={y1} x2={x2} y2={y2} />
            {major && <text className="gauge-tick-label" x={lx} y={ly + 3.5} textAnchor="middle">{t}</text>}
          </g>
        );
      })}

      {showFeel && (() => {
        const [x, y] = point(angleOf(feelsLike as number), R + 11);
        return <circle className="gauge-feel" cx={x} cy={y} r={4.5}><title>Feels like {Math.round(feelsLike as number)} °C</title></circle>;
      })()}

      {hasValue && (
        <g className="gauge-needle" style={{ transform: `rotate(${angleOf(celsius)}deg)` }}>
          <path d={`M ${CX - 10} ${CY - 3.5} L ${CX + R - 16} ${CY} L ${CX - 10} ${CY + 3.5} Z`} />
        </g>
      )}
      <circle className="gauge-hub" cx={CX} cy={CY} r={size === 'large' ? 7 : 9} />

      <text className="gauge-value" x={CX} y={size === 'large' ? 160 : 150} textAnchor="middle">
        {hasValue ? celsius.toFixed(1) : UNKNOWN}
        {hasValue && <tspan className="gauge-unit" dx="2">°C</tspan>}
      </text>
    </svg>
  );
}
