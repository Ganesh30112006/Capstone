import { describe, it, expect } from 'vitest';
import { ageSeconds, formatAge } from './ReadingAge';

const NOW = Date.parse('2026-09-20T14:00:00.000Z');

describe('how old the displayed reading is', () => {
  it('counts from the time the station took the reading', () => {
    expect(ageSeconds('2026-09-20T13:59:58.000Z', NOW)).toBe(2);
    expect(ageSeconds('2026-09-20T13:55:00.000Z', NOW)).toBe(300);
  });

  it('has no age for a station that has not reported', () => {
    // Not "0 seconds ago", which would read as a reading taken this instant.
    expect(ageSeconds(null, NOW)).toBeNull();
    expect(formatAge(null)).toBe('—');
  });

  it('has no age for an unparseable timestamp', () => {
    expect(ageSeconds('not a date', NOW)).toBeNull();
  });

  it('does not report a reading from the future', () => {
    // A station clock running ahead of this browser's would otherwise count
    // backwards from a negative age.
    expect(ageSeconds('2026-09-20T14:00:30.000Z', NOW)).toBe(0);
  });

  it('reads in whatever unit keeps the number small', () => {
    expect(formatAge(3)).toBe('3s ago');
    expect(formatAge(59)).toBe('59s ago');
    expect(formatAge(60)).toBe('1m ago');
    expect(formatAge(7200)).toBe('2h ago');
    expect(formatAge(172_800)).toBe('2d ago');
  });
});
