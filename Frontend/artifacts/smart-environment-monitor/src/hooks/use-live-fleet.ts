import { useEffect, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useLiveStream } from './use-live-stream';
import { mergeLiveIntoFleet, mergeLiveReading } from '@/lib/live-merge';
import type { LiveStatus } from '@/lib/live-stream';
import type { Station } from '@/types/domain';

/** How often to re-ask when the socket is not carrying readings. */
export const FALLBACK_POLL_MS = 10_000;

export interface LiveFleetState {
  status: LiveStatus;
  /** True while readings are arriving by push rather than by asking again. */
  streaming: boolean;
}

/**
 * Keeps the cached fleet current from the live telemetry socket.
 *
 * Mounted once for the whole public shell rather than per page. Every page
 * reads the fleet through the same two query keys, so writing arriving readings
 * into the cache updates all of them at once, and no page has to know that a
 * socket exists.
 *
 * Push is the only mechanism that can be current to the second without asking
 * the gateway every second: a reading reaches this client about 30ms after the
 * station's own clock stamped it, and costs no request at all. Polling is kept
 * only for when the socket is not open, and is deliberately slow, because that
 * is the case where the gateway is most likely to be the thing that is unwell.
 */
export function useLiveFleet(enabled = true): LiveFleetState {
  const queryClient = useQueryClient();
  const live = useLiveStream(enabled);
  const telemetry = live.telemetry;
  const seen = useRef<Record<string, string | undefined>>({});

  useEffect(() => {
    const arrived = Object.entries(telemetry).filter(
      ([uid, reading]) => seen.current[uid] !== reading.receivedAt,
    );
    if (!arrived.length) return;
    for (const [uid, reading] of arrived) {
      seen.current[uid] = reading.receivedAt;
      queryClient.setQueryData<Station>(['station', uid], (previous) =>
        previous ? mergeLiveReading(previous, reading) : previous,
      );
    }
    queryClient.setQueryData<Station[]>(['stations'], (previous) =>
      previous ? mergeLiveIntoFleet(previous, telemetry) : previous,
    );
  }, [telemetry, queryClient]);

  const streaming = live.status === 'open';

  useEffect(() => {
    if (!enabled || streaming) return;
    const id = window.setInterval(() => {
      queryClient.invalidateQueries({ queryKey: ['stations'] });
      queryClient.invalidateQueries({ queryKey: ['station'] });
    }, FALLBACK_POLL_MS);
    return () => window.clearInterval(id);
  }, [enabled, streaming, queryClient]);

  return { status: live.status, streaming };
}
