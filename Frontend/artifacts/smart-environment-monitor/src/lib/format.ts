/**
 * Rendering helpers whose single job is to keep unknown values looking unknown.
 *
 * A missing measurement is not a zero, and a failed fetch is not an empty
 * result. Formatting is where that distinction is usually lost, because
 * `(value ?? 0).toFixed(1)` reads as harmless right up to the moment a station
 * that has never reported is drawn on a map as 0.0 degrees.
 */

import type { ReadingState } from '@/types/domain';

/** The single placeholder for "this value is not known". */
export const UNKNOWN = '\u2014';

/**
 * Formats a measurement, or returns the em dash when there is nothing to
 * format. NaN and Infinity count as nothing: they are arithmetic artefacts,
 * not readings.
 */
export function reading(value: number | null | undefined, digits = 0): string {
  return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(digits) : UNKNOWN;
}

/** Formats a measurement with its unit, keeping the unit off an unknown value. */
export function readingWithUnit(value: number | null | undefined, unit: string, digits = 0): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return UNKNOWN;
  return `${value.toFixed(digits)}${unit}`;
}

/** Formats a yes/no detection, distinguishing "not detected" from "not known". */
export function detection(value: boolean | null | undefined, yes: string, no: string): string {
  if (value === null || value === undefined) return UNKNOWN;
  return value ? yes : no;
}

/**
 * Explains, in a tooltip, why a value is showing as unknown. Returns undefined
 * for a real reading so the attribute is simply omitted.
 */
export function readingNote(state: ReadingState): string | undefined {
  if (state === 'absent') return 'This station has not reported a reading yet.';
  if (state === 'unavailable') return 'The latest reading could not be fetched, so this value is unknown.';
  return undefined;
}

/**
 * Wi-Fi signal as the station reports it: RSSI in dBm, a negative number where
 * closer to zero is stronger. It was printed as a percentage -- "-38% strength"
 * -- which both mislabels the unit and makes a stronger signal read as a
 * smaller number. The bands are the usual RSSI rules of thumb.
 */
export function signalQuality(dbm: number | null | undefined): string {
  if (dbm === null || dbm === undefined || !Number.isFinite(dbm)) return UNKNOWN;
  const band = dbm >= -50 ? 'excellent' : dbm >= -60 ? 'good' : dbm >= -70 ? 'fair' : 'weak';
  return `${Math.round(dbm)} dBm · ${band}`;
}
