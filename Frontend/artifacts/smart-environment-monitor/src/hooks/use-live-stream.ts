import { useEffect, useRef, useState } from 'react';
import {
  connectLiveStream,
  type LiveFrame,
  type LiveStatus,
  type LiveStreamHandle,
} from '@/lib/live-stream';
import type { AiPrediction, AiReport } from '@/types/domain';

export interface LiveTelemetryReading {
  deviceUid: string;
  timestamp?: string;
  receivedAt?: string;
  source?: string;
  values: Record<string, number | boolean | string>;
}

export interface LiveStreamState {
  status: LiveStatus;
  /** Most recent telemetry reading per device. */
  telemetry: Record<string, LiveTelemetryReading>;
  /** Most recent heartbeat per device. */
  heartbeats: Record<string, LiveTelemetryReading>;
  /** Latest AI forecast per device (payload as delivered on the bus). */
  predictions: Record<string, AiPrediction>;
  /** Latest AI rolling report per device. */
  reports: Record<string, AiReport>;
  /** Rolling buffer of the most recent anomaly frames, newest first. */
  anomalies: Array<Record<string, unknown>>;
  /** Rolling buffer of device lifecycle events, newest first. */
  deviceEvents: LiveDeviceEvent[];
}

export interface LiveDeviceEvent {
  kind: 'deviceRegistered' | 'firmwareRequested' | 'firmwareCompleted';
  deviceUid: string;
  /** The event's own time when it carried one, else when this client saw it. */
  at?: string;
  /** True when `at` is this client's receipt time rather than the event's. */
  atIsReceipt: boolean;
  details: Record<string, unknown>;
}

const EMPTY: LiveStreamState = {
  status: 'connecting',
  telemetry: {},
  heartbeats: {},
  predictions: {},
  reports: {},
  anomalies: [],
  deviceEvents: [],
};

const ANOMALY_BUFFER = 50;
const DEVICE_EVENT_BUFFER = 50;

/**
 * Subscribes to the live telemetry WebSocket for the lifetime of the component.
 *
 * Pass `enabled = false` (e.g. on public pages or when unauthenticated) to keep
 * the socket closed. Nothing here animates; a consumer that wants a "live" pulse
 * indicator should gate it on `prefers-reduced-motion` itself.
 */
export function useLiveStream(enabled = true): LiveStreamState {
  const [state, setState] = useState<LiveStreamState>(EMPTY);
  const handleRef = useRef<LiveStreamHandle | null>(null);

  useEffect(() => {
    if (!enabled) {
      handleRef.current?.close();
      handleRef.current = null;
      setState((s) => ({ ...s, status: 'closed' }));
      return;
    }

    const onFrame = (frame: LiveFrame) => {
      setState((prev) => applyFrame(prev, frame));
    };
    const onStatus = (status: LiveStatus) => {
      setState((prev) => (prev.status === status ? prev : { ...prev, status }));
    };

    handleRef.current = connectLiveStream({ onFrame, onStatus });

    return () => {
      handleRef.current?.close();
      handleRef.current = null;
    };
  }, [enabled]);

  return state;
}

function toReading(frame: LiveFrame): LiveTelemetryReading {
  return {
    deviceUid: frame.deviceUid ?? 'unknown',
    timestamp: frame.timestamp,
    receivedAt: frame.receivedAt,
    source: frame.source,
    values: (frame.data ?? {}) as Record<string, number | boolean | string>,
  };
}

function applyFrame(prev: LiveStreamState, frame: LiveFrame): LiveStreamState {
  const uid = frame.deviceUid ?? 'unknown';
  switch (frame.type) {
    case 'telemetry':
      return { ...prev, telemetry: { ...prev.telemetry, [uid]: toReading(frame) } };
    case 'heartbeat':
      return { ...prev, heartbeats: { ...prev.heartbeats, [uid]: toReading(frame) } };
    // Bus payloads are untyped JSON; this is the single place they are given a shape.
    case 'prediction':
      return { ...prev, predictions: { ...prev.predictions, [uid]: frame.data as unknown as AiPrediction } };
    case 'report':
      return { ...prev, reports: { ...prev.reports, [uid]: frame.data as unknown as AiReport } };
    case 'anomaly':
      return {
        ...prev,
        anomalies: [frame.data ?? {}, ...prev.anomalies].slice(0, ANOMALY_BUFFER),
      };
    case 'deviceRegistered':
    case 'firmwareRequested':
    case 'firmwareCompleted':
      return {
        ...prev,
        deviceEvents: [toDeviceEvent(frame), ...prev.deviceEvents].slice(0, DEVICE_EVENT_BUFFER),
      };
    case 'hello':
    case 'pong':
    default:
      return prev;
  }
}

function toDeviceEvent(frame: LiveFrame): LiveDeviceEvent {
  // `timestamp` is the event's own clock and may be absent; `receivedAt` is
  // when this client saw it. They are not the same claim, so which one is being
  // shown is recorded rather than quietly substituted.
  const own = frame.timestamp;
  return {
    kind: frame.type as LiveDeviceEvent['kind'],
    deviceUid: frame.deviceUid ?? 'unknown',
    at: own ?? frame.receivedAt,
    atIsReceipt: !own && Boolean(frame.receivedAt),
    details: frame.data ?? {},
  };
}
