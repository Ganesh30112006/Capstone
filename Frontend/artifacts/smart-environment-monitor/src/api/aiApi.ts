import { httpClient, absentAsNull, type ApiResponse } from './httpClient';
import type { AiPrediction, AiReport, Anomaly, Severity } from '@/types/domain';

interface RawAnomaly {
  id?: string;
  stationUid?: string;
  parameter?: string;
  actual?: string;
  expected?: string;
  /** [0, 1]. Not a calibrated confidence — never rename this to "confidence" in the UI. */
  score?: number;
  severity?: string;
  explanation?: string;
  timestamp?: string;
}

export const aiApi = {
  /**
   * Network-wide AI-detected anomalies, served by alert-service.
   *
   * Does not catch: "no anomalies" and "the detector could not be reached" are
   * different claims, and only the caller knows how to draw the difference.
   */
  getAnomalies: async (): Promise<Anomaly[]> => {
    const { data } = await httpClient.get<ApiResponse<RawAnomaly[]>>('/alerts/anomalies');
    return (data.data ?? []).map(toAnomaly);
  },

  getForecast: async (deviceUid: string, history: unknown[] = []) => {
    const { data } = await httpClient.post('/ai/forecast', { deviceUid, history });
    return data;
  },

  /**
   * Every recent forecast receipt as CSV, the input to
   * ai-service/benchmarks/forecast_accountability.py. Operator-only: it goes
   * through the authenticated gateway route, unlike the public skill summary.
   */
  downloadForecastReceipts: async (): Promise<Blob> => {
    const { data } = await httpClient.get<Blob>('/ai/forecast-receipts', { responseType: 'blob' });
    return data;
  },

  getModelInfo: async () => {
    const { data } = await httpClient.get('/ai/model-info');
    return data;
  },

  // ---- Live-stream branch: forecasts/reports from the direct AWS IoT worker ----

  getPredictions: async (): Promise<AiPrediction[]> => {
    const { data } = await httpClient.get<{ predictions: AiPrediction[] }>('/ai/predictions');
    return data.predictions ?? [];
  },

  /** Null only on a 404: this station has no prediction yet. See `absentAsNull`. */
  getPrediction: async (deviceUid: string): Promise<AiPrediction | null> => {
    try {
      const { data } = await httpClient.get<AiPrediction>(`/ai/predictions/${deviceUid}`);
      return data;
    } catch (error) {
      return absentAsNull(error);
    }
  },

  /** Null only on a 404: no report has been built for this station yet. */
  getReport: async (deviceUid: string): Promise<AiReport | null> => {
    try {
      const { data } = await httpClient.get<AiReport>(`/ai/reports/${deviceUid}`);
      return data;
    } catch (error) {
      return absentAsNull(error);
    }
  },

  /** Explicit action only — never generated silently on page load. */
  generateReport: async (deviceUid: string): Promise<AiReport> => {
    const { data } = await httpClient.post<AiReport>(`/ai/reports/${deviceUid}`);
    return data;
  },
};

function toAnomaly(a: RawAnomaly): Anomaly {
  const severity = (a.severity?.toLowerCase() as Severity) || 'info';
  return {
    id: a.id ?? crypto.randomUUID(),
    stationUid: a.stationUid ?? 'Unknown',
    parameter: a.parameter ?? 'Unknown',
    actual: a.actual ?? '—',
    expected: a.expected ?? '—',
    score: typeof a.score === 'number' ? a.score : 0,
    severity,
    explanation: a.explanation ?? '',
    timestamp: a.timestamp ? new Date(a.timestamp).toLocaleString() : 'recently',
  };
}
