import { parseServerTime } from '@/lib/time';
import { useEffect, useState } from 'react';
import { UNKNOWN } from '@/lib/format';

/** Seconds elapsed, or null when there is no reading time to measure from. */
export function ageSeconds(readingAt: string | null | undefined, now: number): number | null {
  if (!readingAt) return null;
  const taken = parseServerTime(readingAt);
  if (taken === null) return null;
  // A station clock running ahead of this browser's would otherwise produce a
  // negative age, which reads as a reading from the future.
  return Math.max(0, Math.round((now - taken) / 1000));
}

export function formatAge(seconds: number | null): string {
  if (seconds === null) return UNKNOWN;
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86_400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86_400)}d ago`;
}

/**
 * How old the displayed reading is, counted from the station's own clock and
 * recomputed every second.
 *
 * An absolute timestamp answers "when was this taken"; on a page that claims to
 * be live, the question a reader actually has is "is this still true", and only
 * an age answers that. It ticks so that a stalled feed is visible as a number
 * climbing rather than as a time that looks recent because it was recent when
 * the page was opened.
 */
export function ReadingAge({ readingAt, prefix }: { readingAt: string | null; prefix?: string }) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);

  const seconds = ageSeconds(readingAt, now);
  return (
    <span className="reading-age" data-testid="reading-age" data-fresh={seconds !== null && seconds <= 10}>
      {prefix ? `${prefix} ` : ''}
      {formatAge(seconds)}
    </span>
  );
}
