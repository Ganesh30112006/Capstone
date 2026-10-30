import { absentAsNull, httpClient, type ApiResponse } from './httpClient';
import { toTrendPoints } from './trend';
import { isPlausibleCoordinate } from '@/lib/geo';
import { formatServerTime } from '@/lib/time';
import type { ReadingState, Station, StationStatus, TrendPoint } from '@/types/domain';

/**
 * The public observatory's data, from the anonymous /api/public/* endpoints.
 *
 * The public pages used to read the operator API, so they only ever worked in
 * a browser that happened to hold an operator's token -- everyone else, and any
 * operator whose token had expired, got "could not be loaded" on every page.
 *
 * The public projection carries no battery, firmware or Wi-Fi figures. Those
 * fields are null here, which the pages render as not reported, never as zero.
 */

/** How often public pages re-read. Live push is operator-only. */
export const PUBLIC_REFRESH_MS = 5_000;

interface PublicStationRaw {
  stationId: string;
  name?: string;
  location?: string;
  latitude?: number | null;
  longitude?: number | null;
  status?: string;
  lastReportedAt?: string | null;
}

interface PublicReadingRaw {
  stationId: string;
  temperature?: number | null;
  humidity?: number | null;
  airQuality?: number | null;
  oxygen?: number | null;
  pressure?: number | null;
  rain?: boolean | null;
  recordedAt?: string | null;
}

export type ForecastMetric = 'temperature' | 'humidity' | 'airQuality' | 'oxygen' | 'pressure';

/**
 * The model's expected next readings for one station. The horizon is counted
 * in readings because that is what the model forecasts -- at one reading a
 * second, a few seconds ahead. It is not a weather forecast for coming days.
 */
export interface PublicForecast {
  stationId: string;
  generatedAt: string | null;
  horizonReadings: number;
  historyPoints: number | null;
  method: string | null;
  predictions: Partial<Record<ForecastMetric, number[]>>;
}

/** One group of forecast receipts, scored against persistence (see ai-service forecast_ledger.py). */
export interface SkillStats {
  scored: number;
  /** False until enough receipts exist; every figure below is then null or absent. */
  enough: boolean;
  maeModel?: number;
  maePersistence?: number;
  maeLatestValue?: number;
  /** 1 - MAE(model) / MAE(persistence at issue). 0 = no better than repeating the last value. */
  skill?: number | null;
  winRate?: number | null;
  meanErrorReduction?: number | null;
  meanErrorReductionCi95?: [number, number] | null;
  /**
   * The model against each simple forecaster, each on the receipts where that
   * forecaster had a value. "daily" has none until a day of readings exists.
   */
  baselines?: Partial<Record<BaselineName, PairStats>>;
}

export type BaselineName = 'persistence' | 'trend' | 'daily';

export interface PairStats {
  scored: number;
  enough: boolean;
  maeModel?: number;
  maeBaseline?: number;
  skill?: number | null;
  winRate?: number | null;
  meanErrorReductionCi95?: [number, number] | null;
}

export interface MethodSkill {
  metrics: Partial<Record<ForecastMetric, SkillStats>>;
  byRegime: Partial<Record<ForecastMetric, Record<string, SkillStats>>>;
  byStep: Partial<Record<ForecastMetric, Record<string, SkillStats>>>;
  byStation: Record<string, Partial<Record<ForecastMetric, SkillStats>>>;
}

export interface ForecastSkill {
  totalScored: number;
  /** The oldest receipt behind these scores; null when there are none. */
  since?: string | null;
  baselines?: Partial<Record<BaselineName, string>>;
  /** Whether receipts are being kept across restarts, as the service reports it. */
  storage?: { enabled: boolean; state: string; pending?: number; dropped?: number };
  minScoredForSkill: number;
  baseline: string;
  ciNote: string;
  methods: Record<string, MethodSkill>;
}

export interface ForecastReceipt {
  recordedAt: string;
  issuedAt: string;
  step: number;
  regime: string;
  method: string;
  actual: number;
  forecast: number;
  persistenceAtIssue: number;
  latestValue: number;
}

function numberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function toStatus(raw: string | undefined): StationStatus {
  const status = raw?.toLowerCase();
  return status === 'online' || status === 'warning' ? status : 'offline';
}

export function toPublicStation(raw: PublicStationRaw, latest: PublicReadingRaw | null, reading: ReadingState): Station {
  const located = isPlausibleCoordinate(raw.latitude, raw.longitude);
  return {
    uid: raw.stationId,
    name: raw.name || raw.stationId,
    location: raw.location || 'Location not published',
    status: toStatus(raw.status),
    lastSeen: formatServerTime(raw.lastReportedAt) ?? 'Never reported',
    readingAt: latest?.recordedAt ?? null,
    latitude: raw.latitude ?? null,
    longitude: raw.longitude ?? null,
    position: located ? { latitude: raw.latitude as number, longitude: raw.longitude as number, source: 'registered' } : null,
    positionDriftMetres: null,
    temperature: numberOrNull(latest?.temperature),
    humidity: numberOrNull(latest?.humidity),
    airQuality: numberOrNull(latest?.airQuality),
    oxygen: numberOrNull(latest?.oxygen),
    pressure: numberOrNull(latest?.pressure),
    rain: typeof latest?.rain === 'boolean' ? latest.rain : null,
    battery: null,
    firmware: null,
    wifiSignal: null,
    reading,
  };
}

async function latestFor(ids: string[]): Promise<Record<string, PublicReadingRaw> | null> {
  if (!ids.length) return {};
  try {
    const { data } = await httpClient.get<ApiResponse<Record<string, PublicReadingRaw>>>('/public/readings/latest', {
      params: { stationIds: ids.join(',') },
    });
    return data.data ?? {};
  } catch {
    // The stations are still deployed; their readings just could not be read.
    return null;
  }
}

function combine(raws: PublicStationRaw[], readings: Record<string, PublicReadingRaw> | null): Station[] {
  return raws.map((raw) => {
    if (readings === null) return toPublicStation(raw, null, 'unavailable');
    const latest = readings[raw.stationId] ?? null;
    return toPublicStation(raw, latest, latest ? 'ok' : 'absent');
  });
}

export const publicApi = {
  /** Two requests for the whole network: the stations, then every latest reading at once. */
  listStations: async (): Promise<Station[]> => {
    const { data } = await httpClient.get<ApiResponse<PublicStationRaw[]>>('/public/stations');
    const raws = data.data ?? [];
    return combine(raws, await latestFor(raws.map((r) => r.stationId)));
  },

  getStation: async (uid: string): Promise<Station> => {
    const { data } = await httpClient.get<ApiResponse<PublicStationRaw>>(`/public/stations/${encodeURIComponent(uid)}`);
    if (!data.data) throw new Error(`No public record for ${uid}`);
    return combine([data.data], await latestFor([uid]))[0];
  },

  getTrend: async (): Promise<TrendPoint[]> => {
    const { data } = await httpClient.get<ApiResponse<Array<Record<string, unknown>>>>('/public/environment/trend');
    return toTrendPoints(data.data ?? []);
  },

  /** How the live forecasts have scored against the readings they predicted. */
  getForecastSkill: async (): Promise<ForecastSkill> => {
    const { data } = await httpClient.get<ForecastSkill>('/public/forecasts/skill');
    return data;
  },

  getForecastReceipts: async (uid: string, metric: ForecastMetric): Promise<ForecastReceipt[]> => {
    const { data } = await httpClient.get<{ receipts: ForecastReceipt[] }>(
      `/public/forecasts/${encodeURIComponent(uid)}/receipts`,
      { params: { metric, limit: 60 } },
    );
    return data.receipts ?? [];
  },

  /** Null -- an answer, not a failure -- when the model has not forecast this station yet. */
  getForecast: async (uid: string): Promise<PublicForecast | null> => {
    try {
      const { data } = await httpClient.get<PublicForecast>(`/public/forecasts/${encodeURIComponent(uid)}`);
      return data;
    } catch (error) {
      // A 404 is the service's answer that there is none yet; anything else is a failure.
      return absentAsNull(error);
    }
  },
};
