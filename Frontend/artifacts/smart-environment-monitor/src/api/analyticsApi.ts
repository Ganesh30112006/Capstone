import { httpClient, absentAsNull, type ApiResponse } from './httpClient';
import type { FleetReport, HeatmapData, TrendPoint } from '@/types/domain';
import { toTrendPoints } from './trend';

export const analyticsApi = {
  getNetworkTrend: async (): Promise<TrendPoint[]> => {
    const { data } = await httpClient.get<ApiResponse<Array<Record<string, unknown>>>>('/analytics/network/trend');
    return toTrendPoints(data.data ?? []);
  },

  /**
   * The old fallback returned an empty point set stamped with `new Date()` —
   * a fabricated "checked just now" for a check that never happened. A failure
   * now propagates so the page can say the surface is unknown.
   */
  getHeatmap: async (parameter: string): Promise<HeatmapData> => {
    const { data } = await httpClient.get<ApiResponse<HeatmapData>>('/analytics/heatmap', { params: { parameter } });
    return data.data ?? { points: [], parameter, timestamp: '' };
  },

  /** Null only on a 404: no report exists for this station and period yet. */
  getDeviceReport: async (deviceUid: string, type: 'daily' | 'weekly' | 'monthly' = 'daily') => {
    try {
      const { data } = await httpClient.get(`/reports/${deviceUid}/${type}`);
      return data.data ?? null;
    } catch (error) {
      return absentAsNull(error);
    }
  },

  /** Null only on a 404: no fleet rollup has been generated for this period. */
  getFleetReport: async (type: 'daily' | 'weekly' = 'daily'): Promise<FleetReport | null> => {
    try {
      const { data } = await httpClient.get<ApiResponse<FleetReport>>(`/reports/fleet/${type}`);
      return data.data ?? null;
    } catch (error) {
      return absentAsNull(error);
    }
  },
};

