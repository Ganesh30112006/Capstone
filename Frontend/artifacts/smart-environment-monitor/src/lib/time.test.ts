import { describe, expect, it } from 'vitest';
import { parseServerTime } from './time';

describe('reading a server timestamp', () => {
  it('treats a timestamp with no offset as the UTC the services send', () => {
    expect(parseServerTime('2026-10-10T13:30:49')).toBe(Date.UTC(2026, 9, 10, 13, 30, 49));
  });

  it('keeps fractional seconds', () => {
    expect(parseServerTime('2026-10-10T13:30:49.600')).toBe(Date.UTC(2026, 9, 10, 13, 30, 49, 600));
  });

  it('leaves an explicit Z alone', () => {
    expect(parseServerTime('2026-10-10T13:30:49Z')).toBe(Date.UTC(2026, 9, 10, 13, 30, 49));
  });

  it('honours an explicit offset rather than overriding it with UTC', () => {
    expect(parseServerTime('2026-10-10T19:00:49+05:30')).toBe(Date.UTC(2026, 9, 10, 13, 30, 49));
  });

  it('has no time for a missing or unparseable value', () => {
    expect(parseServerTime(null)).toBeNull();
    expect(parseServerTime('')).toBeNull();
    expect(parseServerTime('not a date')).toBeNull();
  });
});
