import { describe, it, expect } from 'vitest';
import { mergeLiveIntoFleet, mergeLiveReading, readingTimeOf } from './live-merge';
import type { LiveTelemetryReading } from '@/hooks/use-live-stream';
import type { Station } from '@/types/domain';

const BASE: Station = {
  uid: 'ESP32-CHN-001',
  name: 'North Ridge Station',
  location: 'Visakhapatnam',
  status: 'online',
  lastSeen: '20/09/2026, 14:00:00',
  readingAt: '2026-09-20T14:00:00.000Z',
  latitude: 17.6868,
  longitude: 83.2185,
  position: { latitude: 17.6868, longitude: 83.2185, source: 'registered' },
  positionDriftMetres: null,
  temperature: 30,
  humidity: 60,
  airQuality: 40,
  oxygen: 21,
  pressure: 1010,
  rain: false,
  battery: 80,
  firmware: '1.4.2',
  wifiSignal: 62,
  reading: 'ok',
};

function frame(values: Record<string, number | boolean | string>, timestamp = '2026-09-20T14:00:05.000Z'): LiveTelemetryReading {
  return { deviceUid: BASE.uid, timestamp, receivedAt: timestamp, source: 'rabbit', values };
}

describe('folding a live reading into a station', () => {
  it('takes the measurements from the frame', () => {
    const merged = mergeLiveReading(BASE, frame({ temperature: 31.75, humidity: 74.5, airQuality: 15, pressure: 1010.77, oxygen: 21.32, rain: true }));

    expect(merged.temperature).toBe(31.75);
    expect(merged.humidity).toBe(74.5);
    expect(merged.rain).toBe(true);
    expect(merged.reading).toBe('ok');
    expect(merged.readingAt).toBe('2026-09-20T14:00:05.000Z');
  });

  it('does not keep an older frame\'s value for a metric this frame lacks', () => {
    // One frame is one reading. Carrying the previous temperature forward beside
    // a new humidity would publish a row that was never measured.
    const merged = mergeLiveReading(BASE, frame({ humidity: 71 }));

    expect(merged.humidity).toBe(71);
    expect(merged.temperature).toBeNull();
    expect(merged.airQuality).toBeNull();
    expect(merged.rain).toBeNull();
  });

  it('ignores a frame older than the reading already shown', () => {
    // A reconnect replays, or two readings cross on the wire. Accepting the
    // older one walks the station's clock backwards.
    const merged = mergeLiveReading(BASE, frame({ temperature: 12 }, '2026-09-20T13:59:00.000Z'));

    expect(merged).toBe(BASE);
    expect(merged.temperature).toBe(30);
  });

  it('leaves the registry to decide whether a station is online', () => {
    const offline: Station = { ...BASE, status: 'offline' };
    expect(mergeLiveReading(offline, frame({ temperature: 31 })).status).toBe('offline');
  });

  it('plots the position the station reported and measures the drift', () => {
    const merged = mergeLiveReading(BASE, frame({ temperature: 31, latitude: 17.7, longitude: 83.25 }));

    expect(merged.position).toEqual({ latitude: 17.7, longitude: 83.25, source: 'reported' });
    expect(merged.positionDriftMetres).toBeGreaterThan(1_000);
  });

  it('keeps the registered position when the frame has no usable fix', () => {
    // (0, 0) is a GPS without a fix, not a station in the Gulf of Guinea.
    const merged = mergeLiveReading(BASE, frame({ temperature: 31, latitude: 0, longitude: 0 }));

    expect(merged.position).toEqual({ latitude: 17.6868, longitude: 83.2185, source: 'registered' });
    expect(merged.positionDriftMetres).toBeNull();
  });

  it('falls back to receipt time only when the frame carries no clock of its own', () => {
    expect(readingTimeOf({ deviceUid: 'x', receivedAt: '2026-09-20T14:00:09.000Z', values: {} })).toBe('2026-09-20T14:00:09.000Z');
    expect(readingTimeOf({ deviceUid: 'x', values: {} })).toBeNull();
  });
});

describe('folding readings into the fleet', () => {
  const fleet: Station[] = [BASE, { ...BASE, uid: 'ESP32-CHN-002', name: 'Lakeside' }];

  it('updates only the stations a reading arrived for', () => {
    const next = mergeLiveIntoFleet(fleet, { 'ESP32-CHN-002': frame({ temperature: 44 }) });

    expect(next[0]).toBe(fleet[0]);
    expect(next[1].temperature).toBe(44);
  });

  it('returns the same array when nothing applied, so no page re-renders', () => {
    expect(mergeLiveIntoFleet(fleet, {})).toBe(fleet);
    expect(mergeLiveIntoFleet(fleet, { 'ESP32-CHN-999': frame({ temperature: 44 }) })).toBe(fleet);
    // A replayed frame applies to nothing either.
    expect(mergeLiveIntoFleet(fleet, { 'ESP32-CHN-001': frame({ temperature: 9 }, '2026-09-20T13:00:00.000Z') })).toBe(fleet);
  });
});
