import { httpClient, type ApiResponse } from './httpClient';
import type { Alert, Severity } from '@/types/domain';

interface RawAlert {
  id?: string | number;
  deviceUid?: string;
  title?: string;
  metricType?: string;
  severity?: string;
  status?: string;
  triggeredAt?: string;
  message?: string;
  triggerValue?: number;
}

export const alertApi = {
  /**
   * Does not catch. An empty alert queue is good news; an unreachable
   * alert-service is not, and the two must never render as the same thing.
   */
  list: async (): Promise<Alert[]> => {
    const { data } = await httpClient.get<ApiResponse<RawAlert[]>>('/alerts');
    return (data.data ?? []).map(toAlert);
  },

  acknowledge: async (alertId: string) => {
    const { data } = await httpClient.patch(`/alerts/${alertId}/status`, { status: 'ACKNOWLEDGED' });
    return data;
  },

  resolve: async (alertId: string) => {
    const { data } = await httpClient.patch(`/alerts/${alertId}/status`, { status: 'RESOLVED' });
    return data;
  },
};

function toAlert(a: RawAlert): Alert {
  const severity = (a.severity?.toLowerCase() as Severity) || (a.triggerValue && a.triggerValue > 50 ? 'critical' : 'warning');
  return {
    id: String(a.id ?? crypto.randomUUID()),
    stationUid: a.deviceUid || 'Unknown',
    title: a.title || `${a.metricType || 'General'} Alert`,
    parameter: a.metricType || 'General',
    severity,
    status: (a.status?.toLowerCase() as Alert['status']) || 'active',
    timestamp: a.triggeredAt ? new Date(a.triggeredAt).toLocaleString() : 'recently',
    summary: a.message || '',
  };
}
