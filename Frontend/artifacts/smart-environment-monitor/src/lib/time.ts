/**
 * Timestamps from this platform's services are UTC, but Spring serialises them
 * without an offset: "2026-10-10T13:30:49.6". JavaScript parses a date-time
 * with no offset as *local* time, so in India that reading was taken five and a
 * half hours earlier than it really was, and its age read "5h ago" a second
 * after it arrived.
 *
 * Every server timestamp goes through here. One that already carries an offset
 * or a Z is left exactly as it is.
 */
const HAS_OFFSET = /(Z|[+-]\d{2}:?\d{2})$/i;

export function parseServerTime(value: string | null | undefined): number | null {
  if (!value) return null;
  const trimmed = value.trim();
  const isDateTime = trimmed.includes('T');
  const normalised = isDateTime && !HAS_OFFSET.test(trimmed) ? `${trimmed}Z` : trimmed;
  const parsed = Date.parse(normalised);
  return Number.isNaN(parsed) ? null : parsed;
}

/** A server timestamp in the viewer's own time zone, or null when there is none. */
export function formatServerTime(
  value: string | null | undefined,
  options: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' },
): string | null {
  const parsed = parseServerTime(value);
  return parsed === null ? null : new Intl.DateTimeFormat(undefined, options).format(parsed);
}
