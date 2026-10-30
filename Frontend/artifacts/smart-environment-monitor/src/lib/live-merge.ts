import type { LiveTelemetryReading } from '@/hooks/use-live-stream';
import type { Station, StationPosition } from '@/types/domain';
import { distanceMetres, isPlausibleCoordinate } from './geo';

/**
 * Folds a reading that arrived on the live socket into the station it belongs
 * to.
 *
 * The public pages used to ask once, when a page was opened, and then show that
 * answer for as long as the tab stayed open — a reading from four minutes ago
 * rendered exactly like one from four seconds ago. The socket already carried
 * every reading as it landed; nothing was listening on this side.
 *
 * A frame is one reading, so it is folded in as one: every measurement comes
 * from the frame, and a metric the frame does not carry becomes unknown rather
 * than keeping the value from an older frame. Mixing them would produce a row
 * that never existed — this station's temperature at 14:02 beside its humidity
 * at 13:58, stamped with the newer time.
 *
 * What the frame is not allowed to decide is whether the station is online.
 * That is the registry's judgement, made against its own thresholds; receiving
 * telemetry is evidence of life, but "online" here means what device-service
 * says it means.
 */

function numberOrNull(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  return null;
}

/** The device's own clock where it gave one, else when this client saw it. */
export function readingTimeOf(reading: LiveTelemetryReading): string | null {
  const own = reading.timestamp ?? reading.values?.recordedAt;
  if (typeof own === 'string' && own) return own;
  return reading.receivedAt ?? null;
}

function positionFrom(
  station: Station,
  latitude: unknown,
  longitude: unknown,
): { position: StationPosition | null; positionDriftMetres: number | null } {
  const reported = isPlausibleCoordinate(numberOrNull(latitude), numberOrNull(longitude))
    ? ([latitude as number, longitude as number] as [number, number])
    : null;
  const registered = isPlausibleCoordinate(station.latitude, station.longitude)
    ? ([station.latitude as number, station.longitude as number] as [number, number])
    : null;

  const chosen = reported ?? registered;
  if (!chosen) return { position: null, positionDriftMetres: null };

  return {
    position: {
      latitude: chosen[0],
      longitude: chosen[1],
      source: reported ? 'reported' : 'registered',
    },
    positionDriftMetres: reported && registered ? distanceMetres(reported, registered) : null,
  };
}

export function mergeLiveReading(station: Station, reading: LiveTelemetryReading): Station {
  const values = reading.values ?? {};
  const readingAt = readingTimeOf(reading);

  // An older frame can arrive after a newer one — a reconnect replays, or two
  // readings cross on the wire. Showing it would walk the station's clock
  // backwards, so the newer reading stands.
  if (station.readingAt && readingAt && Date.parse(readingAt) < Date.parse(station.readingAt)) {
    return station;
  }

  return {
    ...station,
    ...positionFrom(station, values.latitude, values.longitude),
    temperature: numberOrNull(values.temperature),
    humidity: numberOrNull(values.humidity),
    airQuality: numberOrNull(values.airQuality),
    oxygen: numberOrNull(values.oxygen),
    pressure: numberOrNull(values.pressure),
    rain: typeof values.rain === 'boolean' ? values.rain : null,
    readingAt,
    lastSeen: readingAt ? new Date(readingAt).toLocaleString() : station.lastSeen,
    reading: 'ok',
  };
}

export function mergeLiveIntoFleet(
  stations: Station[],
  telemetry: Record<string, LiveTelemetryReading>,
): Station[] {
  let changed = false;
  const next = stations.map((station) => {
    const reading = telemetry[station.uid];
    if (!reading) return station;
    const merged = mergeLiveReading(station, reading);
    if (merged !== station) changed = true;
    return merged;
  });
  // Returning the same array where nothing applied keeps the query cache from
  // publishing an update, and every subscribed page from re-rendering, on a
  // frame for a station that is not on screen.
  return changed ? next : stations;
}
