/**
 * Client for the live telemetry WebSocket exposed by iot-stream-service through
 * the API Gateway.
 *
 *   ADMIN browser --wss--> API Gateway --/ws/live--> iot-stream-service
 *
 * The socket is receive-only. It carries live device readings plus the AI
 * service's output (predictions, reports, anomalies) as they are produced. It
 * exposes no way to send anything toward a device — fleet control goes through
 * the REST API, never this socket.
 */

// Imported rather than redeclared. This was a second hardcoded copy of the
// gateway URL, which meant the REST calls and the live socket could be pointed
// at different hosts by changing only one of them — and only one of them was
// ever going to be remembered.
import { API_BASE_URL } from '@/api/httpClient';

export type LiveFrameType =
  | 'telemetry'
  | 'heartbeat'
  | 'prediction'
  | 'report'
  | 'anomaly'
  // Device lifecycle, mirrored from device-service. These were published from
  // the day that service was written and bound to nothing, so the broker
  // discarded every one; the console could only learn a station had been
  // registered by being asked again.
  | 'deviceRegistered'
  | 'firmwareRequested'
  | 'firmwareCompleted'
  | 'hello'
  | 'pong';

export interface LiveFrame {
  type: LiveFrameType;
  deviceUid?: string;
  timestamp?: string;
  receivedAt?: string;
  source?: string;
  data?: Record<string, unknown>;
}

export type LiveStatus = 'connecting' | 'open' | 'closed' | 'error';

interface LiveStreamOptions {
  onFrame: (frame: LiveFrame) => void;
  onStatus?: (status: LiveStatus) => void;
  /** Overrides the token lookup; defaults to localStorage 'auth-token'. */
  token?: string;
}

export interface LiveStreamHandle {
  close: () => void;
  status: () => LiveStatus;
}

function websocketUrl(token: string): string {
  // http(s)://host:8443/api  ->  ws(s)://host:8443/ws/live
  const httpBase = API_BASE_URL.replace(/\/api\/?$/, '');
  const wsBase = httpBase.replace(/^http/, 'ws');
  return `${wsBase}/ws/live?access_token=${encodeURIComponent(token)}`;
}

/**
 * Opens the live stream and keeps it open, reconnecting with capped backoff.
 * Returns a handle; call `close()` on unmount.
 */
export function connectLiveStream(options: LiveStreamOptions): LiveStreamHandle {
  const token = options.token ?? localStorage.getItem('auth-token') ?? '';
  let socket: WebSocket | null = null;
  let heartbeat: ReturnType<typeof setInterval> | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let backoffMs = 1000;
  let closedByCaller = false;
  let currentStatus: LiveStatus = 'connecting';

  const setStatus = (status: LiveStatus) => {
    currentStatus = status;
    options.onStatus?.(status);
  };

  const clearTimers = () => {
    if (heartbeat) { clearInterval(heartbeat); heartbeat = null; }
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
  };

  const scheduleReconnect = () => {
    if (closedByCaller) return;
    reconnectTimer = setTimeout(open, backoffMs);
    backoffMs = Math.min(backoffMs * 2, 30_000);
  };

  const open = () => {
    if (closedByCaller) return;
    if (!token) { setStatus('error'); return; }

    setStatus('connecting');
    try {
      socket = new WebSocket(websocketUrl(token));
    } catch {
      setStatus('error');
      scheduleReconnect();
      return;
    }

    socket.onopen = () => {
      backoffMs = 1000;
      setStatus('open');
      heartbeat = setInterval(() => {
        try { socket?.send(JSON.stringify({ type: 'ping' })); } catch { /* closing */ }
      }, 25_000);
    };

    socket.onmessage = (event) => {
      let frame: LiveFrame;
      try {
        frame = JSON.parse(event.data as string);
      } catch {
        return;
      }
      if (frame.type === 'pong') return;
      options.onFrame(frame);
    };

    socket.onerror = () => setStatus('error');

    socket.onclose = () => {
      clearTimers();
      if (closedByCaller) {
        setStatus('closed');
        return;
      }
      setStatus('closed');
      scheduleReconnect();
    };
  };

  open();

  return {
    close: () => {
      closedByCaller = true;
      clearTimers();
      try { socket?.close(); } catch { /* already closed */ }
      socket = null;
    },
    status: () => currentStatus,
  };
}
