import type { TrendPoint } from '@/types/domain';

/**
 * A day with no readings comes back with null averages. They were read as
 * `Number(x ?? 0)`, which drew a 0 °C day that never happened on every trend
 * chart and pulled each average toward zero.
 */
export function toTrendPoints(rows: Array<Record<string, unknown>>): TrendPoint[] {
  return rows.map((t) => ({
    timestamp: formatTimestamp(t.timestamp as string | undefined),
    temperature: numberOrNull(t.temperature),
    humidity: numberOrNull(t.humidity),
    airQuality: numberOrNull(t.airQuality),
    oxygen: numberOrNull(t.oxygen),
    pressure: numberOrNull(t.pressure),
  }));
}

function numberOrNull(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function formatTimestamp(raw: string | undefined): string {
  // A label with no timestamp behind it is a dash, not midnight.
  if (!raw) return '—';
  return raw.includes('T') ? raw.split('T')[1].substring(0, 5) : raw;
}
