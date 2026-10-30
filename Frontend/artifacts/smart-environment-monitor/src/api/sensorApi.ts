import { httpClient, absentAsNull, type ApiResponse } from './httpClient';
import type { CalibrationStatus, Sensor, SensorCalibrationSummary, SensorCondition, SensorHealth, SensorHealthScore } from '@/types/domain';
import { formatServerTime } from '@/lib/time';

/** Mirrors sensor-service `SensorDataResponse`: readings are flat, not nested. */
export interface SensorReading {
  id?: number;
  deviceUid?: string;
  temperature?: number;
  humidity?: number;
  airQuality?: number;
  pressure?: number;
  rain?: boolean;
  oxygen?: number;
  latitude?: number | null;
  longitude?: number | null;
  recordedAt?: string;
  createdAt?: string;
  /** Before calibration; absent on readings stored before calibration existed. */
  rawTemperature?: number;
  rawHumidity?: number;
  rawAirQuality?: number;
  rawPressure?: number;
  rawOxygen?: number;
}

export interface SensorStatusDetail {
  status: 'HEALTHY' | 'WARNING' | 'FAILED';
  details?: string;
  lastUpdated?: string;
  condition?: SensorCondition | null;
  lastHealthyAt?: string | null;
  flatlined?: boolean | null;
  flatlineSince?: string | null;
  expectedMin?: number | null;
  expectedMax?: number | null;
  health?: SensorHealthScore | null;
  calibration?: SensorCalibrationSummary | null;
}

/** One calibration record, as sensor-service returns it. */
export interface CalibrationRecord {
  id: number;
  deviceUid: string;
  sensorType: string;
  referenceValue: number;
  sensorValue: number;
  offset: number;
  calibratedAt: string;
  validUntil: string;
  performedBy: string | null;
  notes: string | null;
  status: CalibrationStatus;
}

/**
 * A side-by-side observation. Send only `referenceValue` to pair it with the
 * sensor's latest raw reading; send `sensorValue` and `calibratedAt` together to
 * record one done earlier. The offset is computed by the service.
 */
export interface CalibrationInput {
  sensorType: string;
  referenceValue: number;
  sensorValue?: number;
  calibratedAt?: string;
  notes?: string;
}

/**
 * Mirrors sensor-service `SensorHealthResponse`.
 *
 * `overallStatus` carries a fourth value the per-probe status does not:
 * `UNKNOWN`, meaning the station has no diagnostics on file at all. A probe
 * status only exists once a check has run, so those three are exhaustive.
 *
 * `lastCheckedAt` is null until a check has actually run. It dates the
 * diagnostics, not the request that read them.
 */
export interface SensorHealthReport {
  deviceUid: string;
  overallStatus: 'HEALTHY' | 'WARNING' | 'FAILED' | 'UNKNOWN';
  sensors: Record<string, SensorStatusDetail>;
  lastCheckedAt?: string | null;
}

/**
 * Fleet-wide sensor diagnostics, plus the stations that could not be read.
 *
 * The two travel together on purpose. Returning only the rows would quietly
 * drop every station whose diagnostics failed, and a shorter list of healthy
 * probes is indistinguishable from a healthy fleet — the page needs to be able
 * to say "and four stations did not answer".
 */
export interface SensorInventory {
  sensors: Sensor[];
  /** UIDs whose diagnostics could not be fetched. Their probes are missing below, not healthy. */
  unreadable: string[];
  /**
   * UIDs the service answered for but has never evaluated.
   *
   * Distinct from `unreadable`: the question was answered, and the answer was
   * "no checks have run on this station". Neither list may be folded into the
   * healthy count.
   */
  unevaluated: string[];
}

type SensorDescriptor = { type: string; label: string; unit: string; reading: keyof SensorReading; raw: keyof SensorReading };

const SENSOR_LABELS: Record<string, SensorDescriptor> = {
  temperature: { type: 'temperature', label: 'Temperature', unit: '°C', reading: 'temperature', raw: 'rawTemperature' },
  humidity: { type: 'humidity', label: 'Relative humidity', unit: '%', reading: 'humidity', raw: 'rawHumidity' },
  airquality: { type: 'airQuality', label: 'Air quality', unit: ' AQI', reading: 'airQuality', raw: 'rawAirQuality' },
  pressure: { type: 'pressure', label: 'Barometric pressure', unit: ' hPa', reading: 'pressure', raw: 'rawPressure' },
  oxygen: { type: 'oxygen', label: 'Oxygen', unit: '%', reading: 'oxygen', raw: 'rawOxygen' },
};

/**
 * sensor-service keys its probes `temperature`, `airQuality`; this table was
 * keyed `TEMPERATURE`, `AIR_QUALITY`. Nothing ever matched, so every probe on
 * the Sensors page showed its value as a dash and no unit. Either spelling now
 * finds its descriptor.
 */
function describeSensor(key: string): SensorDescriptor | undefined {
  return SENSOR_LABELS[key.toLowerCase().replaceAll('_', '')];
}

function finiteOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

export const sensorApi = {
  /**
   * Latest reading for one station. Null means the station has none — a 404
   * from sensor-service. Any other failure is rethrown, because "we could not
   * fetch it" is not the same claim as "there is none".
   */
  getLatestReading: async (deviceUid: string): Promise<SensorReading | null> => {
    try {
      const { data } = await httpClient.get<ApiResponse<SensorReading>>(`/sensors/${deviceUid}/latest`);
      return data.data ?? null;
    } catch (error) {
      return absentAsNull(error);
    }
  },

  /**
   * Latest reading for many stations in one request.
   *
   * The per-station endpoint is still there for the single-device pages, but
   * anything rendering a fleet uses this: the console used to issue one
   * `/sensors/{uid}/latest` per station, so a 500-station deployment cost 501
   * round trips to draw one page.
   *
   * A station that has never reported is **absent from the returned object**,
   * not present with a zeroed reading — that absence is what lets the caller
   * mark it `absent` rather than inventing a measurement for it.
   */
  getLatestForEach: async (deviceUids: string[]): Promise<Record<string, SensorReading>> => {
    if (!deviceUids.length) return {};
    const { data } = await httpClient.get<ApiResponse<Record<string, SensorReading>>>('/sensors/latest', {
      params: { deviceUids: deviceUids.join(',') },
    });
    return data.data ?? {};
  },

  /** Null only on a 404: this station has no health report on file. */
  getHealth: async (deviceUid: string): Promise<SensorHealthReport | null> => {
    try {
      const { data } = await httpClient.get<ApiResponse<SensorHealthReport>>(`/sensors/${deviceUid}/health`);
      return data.data ?? null;
    } catch (error) {
      return absentAsNull(error);
    }
  },

  /**
   * Diagnostics for many stations in one request.
   *
   * The counterpart to `getLatestForEach`. A station with no diagnostics on
   * file is **absent from the returned object** rather than present with an
   * empty, healthy-looking report — an empty report would say "no probe is
   * failing", which reads as an all-clear for checks that never ran.
   */
  getHealthForEach: async (deviceUids: string[]): Promise<Record<string, SensorHealthReport>> => {
    if (!deviceUids.length) return {};
    const { data } = await httpClient.get<ApiResponse<Record<string, SensorHealthReport>>>('/sensors/health', {
      params: { deviceUids: deviceUids.join(',') },
    });
    return data.data ?? {};
  },

  /**
   * Per-sensor diagnostics across the fleet, used by the Sensors admin page.
   *
   * Three requests regardless of fleet size: the device list, one batched
   * reading fetch and one batched diagnostics fetch. It used to cost 1 + 2N,
   * so a 500-station deployment made 1001 round trips to draw this page.
   *
   * The device list is fetched without a catch — if the fleet itself cannot be
   * read there is nothing honest to show. The other two are caught separately
   * because they fail to different effect: without diagnostics nothing can be
   * said about any probe, while without readings the probes are still worth
   * listing with their values unknown.
   */
  getSensors: async (): Promise<SensorInventory> => {
    const { data: devicesRes } = await httpClient.get<ApiResponse<Array<{ deviceUid: string }>>>('/devices');
    const devices = devicesRes.data ?? [];
    if (!devices.length) return { sensors: [], unreadable: [], unevaluated: [] };

    const uids = devices.map((device) => device.deviceUid);
    const [readings, health] = await Promise.all([
      sensorApi.getLatestForEach(uids).catch(() => null),
      sensorApi.getHealthForEach(uids).catch(() => null),
    ]);

    // Null, not {}: an empty map would mean "asked, and no station has any
    // diagnostics", which is a different claim from "could not ask".
    if (health === null) {
      return { sensors: [], unreadable: uids, unevaluated: [] };
    }

    const sensors: Sensor[] = [];
    const unevaluated: string[] = [];
    for (const uid of uids) {
      const report = health[uid];
      if (!report) {
        // Answered for, never evaluated. Contributes no probe rows, and is
        // named rather than quietly dropped.
        unevaluated.push(uid);
        continue;
      }
      sensors.push(...toSensors(uid, report, readings?.[uid] ?? null));
    }

    return { sensors, unreadable: [], unevaluated };
  },
};

function toSensors(deviceUid: string, health: SensorHealthReport | null, latest: SensorReading | null): Sensor[] {
  const statuses = health?.sensors ?? {};
  return Object.entries(statuses).map(([name, detail]) => {
    const descriptor = describeSensor(name);
    const value = descriptor && latest ? latest[descriptor.reading] : undefined;
    // A reading stored before calibration existed has no raw field; its main
    // value is the raw one.
    const raw = descriptor && latest ? (latest[descriptor.raw] ?? latest[descriptor.reading]) : undefined;
    return {
      name: descriptor?.label ?? name,
      type: descriptor?.type ?? name,
      stationUid: deviceUid,
      // Null, not zero: a probe with no current reading has no value to show.
      value: finiteOrNull(value),
      rawValue: finiteOrNull(raw),
      unit: descriptor?.unit ?? '',
      health: toSensorHealth(detail?.status),
      lastReading: formatServerTime(latest?.recordedAt) ?? 'Unknown',
      failureReason: detail?.status === 'HEALTHY' ? undefined : detail?.details,
      condition: detail?.condition ?? null,
      lastHealthyAt: detail?.lastHealthyAt ?? null,
      flatlined: detail?.flatlined ?? null,
      flatlineSince: detail?.flatlineSince ?? null,
      expectedMin: detail?.expectedMin ?? null,
      expectedMax: detail?.expectedMax ?? null,
      healthScore: detail?.health ?? null,
      calibration: detail?.calibration ?? null,
    };
  });
}

/**
 * The backend's WARNING maps onto the console's "degraded" band.
 *
 * Anything unrecognised is `unknown`, not `healthy`. Defaulting to healthy
 * meant a status this console could not interpret was shown as a passed check.
 */
function toSensorHealth(status: string | undefined): SensorHealth {
  switch (status) {
    case 'HEALTHY':
      return 'healthy';
    case 'WARNING':
      return 'degraded';
    case 'FAILED':
      return 'failed';
    default:
      return 'unknown';
  }
}

/** Calibration records: history for review, and recording a new one (admin only). */
export const calibrationApi = {
  history: async (deviceUid: string): Promise<CalibrationRecord[]> => {
    const { data } = await httpClient.get<ApiResponse<CalibrationRecord[]>>(`/sensors/${encodeURIComponent(deviceUid)}/calibrations`);
    return data.data ?? [];
  },

  /** Never retried automatically: recording twice would be two calibrations. */
  record: async (deviceUid: string, input: CalibrationInput): Promise<CalibrationRecord> => {
    const { data } = await httpClient.post<ApiResponse<CalibrationRecord>>(
      `/sensors/${encodeURIComponent(deviceUid)}/calibrations`,
      input,
    );
    if (!data.data) throw new Error(data.message || 'The calibration was not recorded.');
    return data.data;
  },
};
