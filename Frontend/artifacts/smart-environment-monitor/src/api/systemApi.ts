import { httpClient } from './httpClient';
import type { ServiceHealth } from '@/types/domain';

interface RawServiceEntry {
  name: string;
  status: 'operational' | 'degraded' | 'outage';
  latencyMs: number;
}

interface SystemHealthResponse {
  overallStatus: 'operational' | 'degraded' | 'outage';
  checkedAt: string;
  data: RawServiceEntry[];
}

export const systemApi = {
  /**
   * Live probe results for each backend service's own /actuator/health. Never
   * exposes hostnames, ports, credentials, or other infrastructure detail —
   * only name, coarse status, and latency.
   */
  /**
   * Deliberately does not catch. Every other reader on this page treats an
   * empty list as "nothing is wrong", so swallowing the error here turned an
   * unreachable gateway into a green "All services operational" banner — a
   * false negative on the one screen whose entire job is to report failure.
   * The caller distinguishes "could not ask" from "asked, nothing wrong".
   */
  getServiceHealth: async (): Promise<ServiceHealth[]> => {
    const { data } = await httpClient.get<SystemHealthResponse>('/system/health');
    return (data.data ?? []).map((s) => ({
      name: s.name,
      status: s.status,
      version: '—',
      lastCheck: `${s.latencyMs} ms`,
    }));
  },
};
