import { httpClient, absentAsNull } from './httpClient';
import type { StreamStatus } from '@/types/domain';

/** REST status for the real-time branch. Live data itself arrives over the
 * WebSocket client in `@/lib/live-stream`, not through this module. */
export const streamApi = {
  /** Null only on a 404: this deployment has no live-stream branch at all. */
  getStatus: async (): Promise<StreamStatus | null> => {
    try {
      const { data } = await httpClient.get<StreamStatus>('/stream/status');
      return data;
    } catch (error) {
      return absentAsNull(error);
    }
  },
};
