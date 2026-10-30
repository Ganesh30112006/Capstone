/**
 * Domain types shared across the public observatory and the operator console.
 *
 * These describe the shapes the frontend works with after api/* has mapped a
 * backend response onto them — not the wire format itself, which the api/*
 * modules own.
 */

export type StationStatus = 'online' | 'warning' | 'offline';

/**
 * Whether a station's latest measurements are actually known.
 *
 * The three cases are deliberately separate, because collapsing them is how a
 * console comes to draw a confident zero for a station it has heard nothing
 * from:
 *  - `ok`           the station reported, and the values alongside are real;
 *  - `absent`       the station is registered but has never sent a reading;
 *  - `unavailable`  the reading could not be fetched, so the values are unknown.
 *
 * Only `ok` licenses rendering a number. The other two render as an em dash.
 */
export type ReadingState = 'ok' | 'absent' | 'unavailable';
export type Severity = 'critical' | 'warning' | 'info';
/**
 * `unknown` is not a verdict — it is the absence of one. A probe whose status
 * this console cannot interpret must not be counted as healthy.
 */
export type SensorHealth = 'healthy' | 'degraded' | 'failed' | 'unknown';

/**
 * Where a station is being drawn, and on whose authority.
 *
 * `registered` is the position someone typed into the device registry.
 * `reported` is the position the station's own GPS put on its latest reading.
 * They are different claims and the map says which one it is showing: a pin
 * from the registry is where the station is *supposed* to be, which is not
 * evidence of where it is.
 */
export interface StationPosition {
  latitude: number;
  longitude: number;
  source: 'reported' | 'registered';
}

export interface Station {
  uid: string;
  name: string;
  location: string;
  status: StationStatus;
  lastSeen: string;
  /**
   * When the displayed reading was taken, as the station's own clock reported
   * it. Null when no reading has been received, so an age is never computed
   * from a substitute timestamp. `lastSeen` is a formatted string for display;
   * this is the value freshness is judged against.
   */
  readingAt: string | null;
  /** The registry's position: what was configured, not what was observed. */
  latitude: number | null;
  longitude: number | null;
  /**
   * The position to plot, preferring what the hardware reported. Null when
   * neither source gives a usable coordinate — such a station is absent from
   * the map and counted as unlocated, never drawn at the centre of the view.
   */
  position: StationPosition | null;
  /**
   * Metres between the reported and registered positions, when both exist.
   * Null when only one does, because there is nothing to compare.
   */
  positionDriftMetres: number | null;
  /**
   * Measurements are nullable because a station that has not reported has no
   * temperature — it does not have a temperature of zero. These were once
   * defaulted to 0 and rendered as "0.0 °C", which is a fabricated reading.
   */
  temperature: number | null;
  humidity: number | null;
  airQuality: number | null;
  oxygen: number | null;
  pressure: number | null;
  /** The station reports rain as detected or not; it has no rain gauge depth. Null when unknown. */
  rain: boolean | null;
  /** Null when the station has never reported one — render as unknown, never as a number. */
  battery: number | null;
  firmware: string | null;
  wifiSignal: number | null;
  /** Whether the measurements above are known at all. */
  reading: ReadingState;
}

export type CalibrationStatus = 'NOT_CALIBRATED' | 'VALID' | 'DUE_SOON' | 'EXPIRED' | 'SUPERSEDED';
export type SensorCondition = 'NONE' | 'MISSING' | 'OUT_OF_RANGE' | 'RAPID_CHANGE' | 'FLATLINE';

/** The share of a sensor's evaluations over the last day that passed, with the counts behind it. */
export interface SensorHealthScore {
  percent: number;
  passed: number;
  warned: number;
  failed: number;
  windowStart: string;
}

export interface SensorCalibrationSummary {
  status: CalibrationStatus;
  lastCalibratedAt: string | null;
  validUntil: string | null;
  offset: number | null;
  /** Null unless two calibrations a week or more apart exist to estimate from. */
  driftEstimate: number | null;
  driftBaselineFrom: string | null;
}

export interface Sensor {
  name: string;
  stationUid: string;
  /** Null when the probe has no current reading to report. This is the corrected value. */
  value: number | null;
  unit: string;
  health: SensorHealth;
  lastReading: string;
  failureReason?: string;
  /*
   * Everything below is optional, and absent means not known -- rendered as a
   * dash, never as zero, healthy or valid.
   */
  /** The sensor-service key: temperature, humidity, airQuality, pressure, oxygen. */
  type?: string;
  /** What the sensor itself reported before calibration. */
  rawValue?: number | null;
  condition?: SensorCondition | null;
  lastHealthyAt?: string | null;
  flatlined?: boolean | null;
  flatlineSince?: string | null;
  expectedMin?: number | null;
  expectedMax?: number | null;
  /** Null when the sensor had no evaluations in the window. */
  healthScore?: SensorHealthScore | null;
  /** Null for sensors that cannot be calibrated. */
  calibration?: SensorCalibrationSummary | null;
}

export interface Alert {
  id: string;
  stationUid: string;
  title: string;
  parameter: string;
  severity: Severity;
  status: 'active' | 'acknowledged' | 'resolved';
  timestamp: string;
  summary: string;
}

export interface Anomaly {
  id: string;
  stationUid: string;
  parameter: string;
  actual: string;
  expected: string;
  /** A distance-from-expectation measure, not a calibrated probability. Never display this as "confidence". */
  score: number;
  severity: Severity;
  explanation: string;
  timestamp: string;
}

export interface ServiceHealth {
  name: string;
  status: 'operational' | 'degraded' | 'outage';
  version: string;
  lastCheck: string;
}

/**
 * One day of network averages. A metric is null on a day with no readings for
 * it: plotted as a gap and left out of any average, never drawn as zero.
 */
export interface TrendPoint {
  timestamp: string;
  temperature: number | null;
  humidity: number | null;
  airQuality: number | null;
  oxygen: number | null;
  pressure: number | null;
}

export type UserRole = 'ADMIN' | 'USER';

export interface AuthUser {
  id: number;
  /** auth-service returns a single display name field, not firstName/lastName. */
  name: string;
  email: string;
  role: UserRole;
}

/** The closed set of operations device-service will forward to AWS IoT Core. */
export type DeviceCommandName = 'PING' | 'TELEMETRY_SYNC' | 'REBOOT' | 'OTA_CHECK';

export interface HeatmapPoint {
  latitude: number;
  longitude: number;
  value: number;
}

export interface HeatmapData {
  parameter: string;
  timestamp: string;
  points: HeatmapPoint[];
}

export interface FleetReport {
  reportId: string;
  generatedAt: string;
  summary: {
    totalDevices: number;
    activeAlerts: number;
    averageTemperature: number;
  };
}

export interface AiPrediction {
  eventId: string;
  deviceUid: string;
  generatedAt: string;
  horizon: number;
  method: string;
  modelName: string;
  predictions: Record<string, number[]>;
  reasons: string[];
}

export interface AiReport {
  eventId: string;
  deviceUid: string;
  generatedAt: string;
  windowStart: string;
  windowEnd: string;
  sampleCount: number;
  anomalyCount: number;
  metrics: Record<string, { min: number; max: number; avg: number; last: number; samples: number }>;
  forecastSummary: Record<string, number>;
  narrative: string;
}

export interface StreamStatus {
  source: 'aws' | 'rabbit';
  connected: boolean;
  activeClients: number;
  fanout: { publishToRabbit: boolean; broadcastToWebSocket: boolean; forwardAiEvents: boolean };
  topicPrefix: string;
}
