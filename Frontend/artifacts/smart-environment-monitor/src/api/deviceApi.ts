import { formatServerTime } from '@/lib/time';
import { httpClient, type ApiResponse } from './httpClient';
import { sensorApi } from './sensorApi';
import type { DeviceCommandName, ReadingState, Station, StationPosition } from '@/types/domain';
import type { SensorReading } from './sensorApi';
import { distanceMetres, isPlausibleCoordinate } from '@/lib/geo';

interface RawDevice {
  deviceUid: string;
  deviceName?: string;
  name?: string;
  locationName?: string;
  status?: string;
  lastSeenAt?: string;
  batteryLevel?: number;
  firmwareVersion?: string;
  wifiSignalStrength?: number;
  latitude?: number | null;
  longitude?: number | null;
}

function numberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/**
 * Decides which position to plot, preferring what the station reported.
 *
 * Every reading already carries the device's own latitude and longitude, and
 * this client fetched them on every fleet load and then threw them away — the
 * map has only ever drawn the registry's copy. That is the weaker claim of the
 * two: it says where someone recorded the station as being, which stays put
 * when the station does not.
 *
 * Neither source is trusted blindly. A GPS module without a fix reports (0, 0),
 * which is a real place in the Gulf of Guinea and would put a coastal Andhra
 * station in the Atlantic, so implausible pairs are rejected rather than drawn.
 * When both are usable the distance between them is kept, so a station that has
 * moved — or been mis-registered — can be seen rather than silently plotted
 * somewhere it is not.
 */
function positionOf(raw: RawDevice, latest: SensorReading | null): {
  position: StationPosition | null;
  positionDriftMetres: number | null;
} {
  const reported = isPlausibleCoordinate(latest?.latitude, latest?.longitude)
    ? ([latest!.latitude as number, latest!.longitude as number] as [number, number])
    : null;
  const registered = isPlausibleCoordinate(raw.latitude, raw.longitude)
    ? ([raw.latitude as number, raw.longitude as number] as [number, number])
    : null;

  const chosen = reported ?? registered;
  if (!chosen) {
    return { position: null, positionDriftMetres: null };
  }

  return {
    position: {
      latitude: chosen[0],
      longitude: chosen[1],
      source: reported ? 'reported' : 'registered',
    },
    positionDriftMetres:
      reported && registered ? distanceMetres(reported, registered) : null,
  };
}

/**
 * Assembles a station from its device record and whatever is known about its
 * latest reading, keeping three outcomes apart.
 *
 * A station record can load while its reading does not, and that is a
 * per-station degradation rather than a fleet outage: the row still belongs in
 * the table, with its measurements marked unknown. What it must never do is
 * fall back to zeros, which is what the old `NO_READING` constant did — its
 * comment promised the values were "never presented as a measurement" while
 * every table, map pin, and metric tile rendered them as exactly that.
 */
function toStation(raw: RawDevice, latest: SensorReading | null, reading: ReadingState): Station {
  return {
    uid: raw.deviceUid,
    name: raw.deviceName || raw.name || 'Unknown',
    location: raw.locationName || 'Unknown location',
    status: (raw.status?.toLowerCase() as Station['status']) || 'offline',
    lastSeen: formatServerTime(raw.lastSeenAt) ?? 'Unknown',
    readingAt: latest?.recordedAt ?? null,
    latitude: raw.latitude ?? null,
    longitude: raw.longitude ?? null,
    ...positionOf(raw, latest),
    temperature: numberOrNull(latest?.temperature),
    humidity: numberOrNull(latest?.humidity),
    airQuality: numberOrNull(latest?.airQuality),
    oxygen: numberOrNull(latest?.oxygen),
    pressure: numberOrNull(latest?.pressure),
    rain: typeof latest?.rain === 'boolean' ? latest.rain : null,
    battery: raw.batteryLevel ?? null,
    firmware: raw.firmwareVersion ?? null,
    wifiSignal: raw.wifiSignalStrength ?? null,
    reading,
  };
}

/** Fetches one station's reading on its own, for the single-device pages. */
async function attachOwnReading(raw: RawDevice): Promise<Station> {
  try {
    const latest = await sensorApi.getLatestReading(raw.deviceUid);
    return toStation(raw, latest, latest ? 'ok' : 'absent');
  } catch {
    return toStation(raw, null, 'unavailable');
  }
}

export const deviceApi = {
  /**
   * The fleet, with each station's latest reading attached.
   *
   * This used to fetch readings one station at a time — `GET /devices` and then
   * `GET /sensors/{uid}/latest` once per device — so a 500-station deployment
   * made 501 round trips through the gateway to draw a single page, on six
   * different pages. It is now two requests regardless of fleet size.
   *
   * The two calls fail independently on purpose. If the device list fails there
   * is no fleet to show and the error propagates. If only the readings call
   * fails, every station is still listed with its measurements marked
   * `unavailable`: the stations are deployed, we just could not read them.
   */
  list: async (): Promise<Station[]> => {
    const { data } = await httpClient.get<ApiResponse<RawDevice[]>>('/devices');
    const devices = data.data ?? [];
    if (!devices.length) return [];

    let readings: Record<string, SensorReading> | null = null;
    try {
      readings = await sensorApi.getLatestForEach(devices.map((device) => device.deviceUid));
    } catch {
      readings = null;
    }

    return devices.map((device) => {
      if (readings === null) {
        return toStation(device, null, 'unavailable');
      }
      // Absent from the keyed response means the station has never reported —
      // sensor-service omits it rather than returning a zeroed row.
      const latest = readings[device.deviceUid] ?? null;
      return toStation(device, latest, latest ? 'ok' : 'absent');
    });
  },

  get: async (deviceUid: string): Promise<Station> => {
    const { data } = await httpClient.get<ApiResponse<RawDevice>>(`/devices/${deviceUid}`);
    if (!data.data) {
      throw new Error('Station not found');
    }
    return attachOwnReading(data.data);
  },

  getDigitalTwin: async (deviceUid: string) => {
    const { data } = await httpClient.get(`/devices/${deviceUid}/digital-twin`);
    return data;
  },

  getSupportedCommands: async (deviceUid: string): Promise<DeviceCommandName[]> => {
    const { data } = await httpClient.get<ApiResponse<DeviceCommandName[]>>(`/devices/${deviceUid}/commands`);
    return data.data ?? [];
  },

  /**
   * The only way to instruct a station: a closed command name, dispatched by
   * device-service over AWS IoT Core. `PUBLISHED` means delivered to the
   * broker — not that the device has acknowledged or executed it yet.
   */
  sendCommand: async (deviceUid: string, command: DeviceCommandName) => {
    const { data } = await httpClient.post<ApiResponse<{ status: string }>>(
      `/devices/${deviceUid}/commands`,
      { command },
    );
    return data;
  },
};
